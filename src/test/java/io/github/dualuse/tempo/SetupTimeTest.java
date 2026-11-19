package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.Task;
import io.github.dualuse.tempo.Tempo.Name;
import io.github.dualuse.tempo.Tempo.Options;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static io.github.dualuse.tempo.Tempo.SPAN;
import static io.github.dualuse.tempo.Tempo.STEP;
import static io.github.dualuse.tempo.Tempo.NOOP;
import static io.github.dualuse.tempo.TempoTest.lines;
import static io.github.dualuse.tempo.TempoTest.onThread;
import static io.github.dualuse.tempo.TempoTest.tree;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Where Tempo's own setup time goes: the root starts at the trace's zero point, so the setup (and the
 * JVM's one-time CPU-clock setup) is the root's own time, never hidden and never a task's queue wait.
 */
class SetupTimeTest {

    static final Name SEARCH = TempoTest.SEARCH, DECODE = TempoTest.DECODE, LOAD = TempoTest.LOAD,
            MATCH = TempoTest.MATCH;

    @AfterEach
    void noTraceLeftOnThread() {
        TempoTest.assertNothingLeftOpen();
    }

    /** A clock that advances by {@code step} on every read, and by whatever {@link #add} says. */
    static final class Stepping implements LongSupplier {
        final AtomicLong t = new AtomicLong(1_000_000_000L);
        final long step;

        Stepping(long step) {
            this.step = step;
        }

        @Override
        public long getAsLong() {
            return t.addAndGet(step);
        }

        void add(long nanos) {
            t.addAndGet(nanos);
        }
    }

    /** {@code start+duration} of every span, and the labelled phases of {@code forEachTop}, of the trace and its snapshot. */
    static String starts(Trace t) {
        StringBuilder b = new StringBuilder();
        t.forEachSpan(v -> b.append(v.name()).append(' ').append(v.startNanos()).append('+').append(v.durationNanos()).append('\n'));
        t.forEachTop((name, label, start, dur) -> b.append("top ").append(name).append(' ').append(start).append('+').append(dur).append('\n'));
        Tempo.Snapshot s = t.snapshot();
        StringBuilder c = new StringBuilder();
        s.forEachSpan(v -> c.append(v.name()).append(' ').append(v.startNanos()).append('+').append(v.durationNanos()).append('\n'));
        s.forEachTop((name, label, start, dur) -> c.append("top ").append(name).append(' ').append(start).append('+').append(dur).append('\n'));
        assertEquals(b.toString(), c.toString(), "the snapshot reads as the trace");
        return b.toString();
    }

    @Test
    void theRootStartsAtZeroAndThePhasesCountFromIt() {
        for (int level : new int[] {SPAN, STEP}) {
            Stepping clock = new Stepping(10);
            Trace t = Tempo.trace(SEARCH, new Options().clock(clock).cpu(false)).records(level);
            Span s = t;
            s.phase(DECODE).label("Decode"); // the zero point is the first read; the phase starts at 10
            s.span(LOAD).close();            // from 20 to 30
            s.done();                        // at 40
            clock.add(100);
            t.close();                       // at 150
            assertEquals(lines(
                    "search 0+150",
                    "decode 10+30",
                    "load 20+10",
                    "top decode 10+30"), starts(t), "level " + level);
            assertEquals(150, t.elapsedNanos());
            assertEquals(150, t.snapshot().elapsedNanos());
            assertEquals("0.000000150s = 0.000000030s (Decode)", t.toRuntime(9));
            assertTrue(t.toHeader().contains(";S=00.0.0/"), t.toHeader()); // the root's wire start is 0 too
        }
    }

    /**
     * The zero point is the first thing {@code Tempo.start} does, before it constructs anything (and so
     * before the classes it loads on first use in a JVM): the clock's first read comes from {@code start}
     * itself, not from a constructor it calls.
     */
    @Test
    void theZeroPointIsReadBeforeStartConstructsAnything() {
        List<String> firstRead = new ArrayList<>();
        Stepping steps = new Stepping(10);
        LongSupplier clock = () -> {
            if (firstRead.isEmpty()) {
                StackWalker.getInstance().forEach(f -> {
                    if (f.getClassName().startsWith(Tempo.class.getName())) firstRead.add(f.getClassName() + "." + f.getMethodName());
                });
            }
            return steps.getAsLong();
        };
        Trace t = Tempo.trace(SEARCH, new Options().clock(clock).cpu(false));
        t.close();
        assertEquals(List.of(Tempo.class.getName() + ".trace"), firstRead.subList(0, 1), String.valueOf(firstRead));
        assertFalse(firstRead.stream().anyMatch(f -> f.endsWith(".<init>")), String.valueOf(firstRead));
        assertEquals(lines("search 0+10"), starts(t)); // the zero point, then the close
    }

    @Test
    void theRootStartsAtZeroWithTheSystemClock() {
        for (int level : new int[] {SPAN, STEP}) {
            Trace t = Tempo.trace(SEARCH, new Options()).records(level);
            t.done(DECODE); // a phase named after the fact, from the root's start
            t.close();
            List<Long> starts = new ArrayList<>();
            t.forEachSpan(v -> starts.add(v.startNanos()));
            t.forEachTop((name, label, start, dur) -> starts.add(start));
            t.snapshot().forEachSpan(v -> starts.add(v.startNanos()));
            t.snapshot().forEachTop((name, label, start, dur) -> starts.add(start));
            assertEquals(List.of(0L, 0L, 0L, 0L), starts, "level " + level);
        }
    }

    @Test
    void setupTimeIsTheRootsOwnTime() {
        // The CPU clock's first read costs a millisecond of wall time, as the JVM's one-time setup of the
        // real one does: it is root time no child covers, never subtracted.
        Stepping clock = new Stepping(10);
        boolean[] cold = {true};
        LongSupplier cpu = () -> {
            if (cold[0]) clock.add(1_000_000);
            cold[0] = false;
            return 0;
        };
        Trace t = Tempo.trace(SEARCH, new Options().clock(clock).cpuClock(cpu)).records(STEP);
        t.span(LOAD).close();
        t.close();
        assertEquals(lines(
                "search 0+1000030",
                "load 1000010+10"), starts(t));
        assertEquals("total;dur=1.000", t.toServerTiming());
        assertEquals("0.001s", t.toRuntime(3));
    }

    @Test
    void theRootsCpuTimeStartsRightAfterTheZeroPoint() {
        TempoTest.Clock clock = new TempoTest.Clock(), cpu = new TempoTest.Clock();
        Trace t = Tempo.trace(SEARCH, new Options().clock(clock).cpuClock(cpu)).records(STEP);
        cpu.add(40);
        clock.add(50);
        t.close();
        List<String> got = new ArrayList<>();
        t.forEachSpan(v -> got.add(v.name() + " " + v.startNanos() + "+" + v.durationNanos() + " cpu=" + v.cpuNanos()));
        assertEquals(List.of("search 0+50 cpu=40"), got);
    }

    /** A CPU clock that records the thread of each read. */
    static final class Recording implements LongSupplier {
        final List<String> threads = Collections.synchronizedList(new ArrayList<>());

        @Override
        public long getAsLong() {
            threads.add(Thread.currentThread().getName());
            return 0;
        }
    }

    @Test
    void theFirstCpuReadIsOnTheThreadThatStartsOrRaisesTheTrace() throws Exception {
        String me = Thread.currentThread().getName();

        Recording started = new Recording(); // STEP from the start: the start reads it
        Trace a = Tempo.trace(SEARCH, new Options().cpuClock(started)).records(STEP);
        assertEquals(List.of(me), started.threads);
        Task h = Tempo.task(MATCH);
        assertNull(onThread("worker", () -> h.tryBegin().close()));
        a.close();
        assertEquals(List.of(me, "worker", "worker", me), started.threads);

        Recording raised = new Recording(); // SPAN, then raised by the handler: the raise reads it
        Trace b = Tempo.trace(SEARCH, new Options().cpuClock(raised));
        assertEquals(List.of(), raised.threads);
        b.records(STEP);                // before the first task: a task counts like a step
        assertEquals(STEP, b.records());
        assertEquals(List.of(me), raised.threads);
        Task later = Tempo.task(MATCH);
        assertNull(onThread("worker", () -> later.tryBegin().close()));
        b.close();
        assertEquals(me, raised.threads.get(0));
        assertEquals(List.of(me, "worker", "worker", me), raised.threads); // the root's own from the widening on

        Recording off = new Recording(); // CPU off, or already measured: a raise reads nothing
        Trace c = Tempo.trace(SEARCH, new Options().cpuClock(off).cpu(false));
        c.records(STEP);
        c.close();
        Trace d = Tempo.trace(SEARCH, new Options().cpuClock(off).cpu(true));
        d.records(STEP);
        d.close();
        assertEquals(List.of(me, me), off.threads); // d's root: at the start and at the close

        Recording coarse = new Recording(); // SPAN throughout: never
        Trace e = Tempo.trace(SEARCH, new Options().cpuClock(coarse));
        Task g = Tempo.task(MATCH);
        assertNull(onThread("worker", () -> g.tryBegin().close()));
        e.close();
        assertEquals(List.of(), coarse.threads);
    }

    @Test
    void aTasksQueueWaitEndsBeforeItsCpuRead() throws Exception {
        for (int budget : new int[] {512, 0}) { // a recorded handoff, and one folded past the budget
            TempoTest.Clock clock = new TempoTest.Clock();
            long[] cpu = {0};
            LongSupplier costly = () -> { // every CPU read costs 7 ns of wall time
                clock.add(7);
                return cpu[0];
            };
            Trace t = Tempo.trace(SEARCH, new Options().clock(clock).cpuClock(costly).budget(budget)).records(STEP);
            clock.add(10);
            Task h = Tempo.task(MATCH); // submitted at 17: the root's CPU read came first
            clock.add(30);
            assertNull(onThread("worker", () -> {
                Span m = h.tryBegin();              // begins at 47, then reads CPU: 54
                cpu[0] += 3;
                clock.add(5);
                m.close();                       // ends at 59, then reads CPU: 66
            }));
            t.close();                           // 66
            Tempo.Total a = t.total(MATCH);
            assertEquals(30, a.waitSum(), "budget " + budget + ": the wait is the time in the queue, no read in it");
            assertEquals(12, a.sum(), "budget " + budget + ": the start's CPU read is the task's run time");
            if (budget > 0) {
                assertEquals(lines(
                        "search 0+66",
                        "  match 17+12 handoff~30 @worker"), tree(t));
            }
            List<Long> cpus = new ArrayList<>();
            t.forEachSpan(v -> {
                if (v.name() == MATCH) cpus.add(v.cpuNanos());
            });
            assertEquals(List.of(3L), cpus);
        }
    }

    /**
     * The queue wait ends as the task wins its handoff, before Tempo looks up or sets up the worker's lane:
     * on a thread's first task in a trace, setting its lane up is the task's run time, not queue wait. The
     * fake clock makes the setup visible: it runs 1000 ns ahead for every lane registered beyond the root's.
     */
    @Test
    void aTasksQueueWaitEndsBeforeItsLaneIsSetUp() throws Exception {
        for (int budget : new int[] {512, 0}) { // a recorded handoff, and one folded past the budget
            Trace[] trace = new Trace[1];
            AtomicLong base = new AtomicLong(1_000_000_000L);
            LongSupplier clock = () -> {
                Trace t = trace[0];
                return base.get() + (t == null ? 0 : 1000L * (t.ctx.lanesById().length - 1));
            };
            Trace t = trace[0] = Tempo.trace(SEARCH, new Options().clock(clock).cpu(false).budget(budget)).records(STEP);
            base.addAndGet(10);
            Task h = Tempo.task(MATCH); // submitted at 10
            base.addAndGet(30);
            assertNull(onThread("worker", () -> {
                Span m = h.tryBegin();              // wins the handoff at 40, then sets the worker's lane up: 1040
                base.addAndGet(5);
                m.close();                       // 1045
            }));
            t.close();
            Tempo.Total a = t.total(MATCH);
            assertEquals(30, a.waitSum(), "budget " + budget + ": the wait is the time in the queue, no lane setup in it");
            assertEquals(1005, a.sum(), "budget " + budget + ": the lane setup is the task's run time");
            if (budget > 0) {
                assertEquals(lines(
                        "search 0+1045",
                        "  match 10+1005 handoff~30 @worker"), tree(t));
            }
        }
    }
}
