package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.Handoff;
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

import static io.github.dualuse.tempo.Tempo.COARSE;
import static io.github.dualuse.tempo.Tempo.DETAIL;
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
        assertSame(NOOP, Tempo.current(), "a test left a span open on the test thread");
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

    /** {@code start+duration} of every span, and the legacy steps of {@code forEachTop}, of the trace and its snapshot. */
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
    void theRootStartsAtZeroAndTheLegacyStepsCountFromIt() {
        for (int level : new int[] {COARSE, DETAIL}) {
            Stepping clock = new Stepping(10);
            Trace t = Tempo.start(SEARCH, new Options().clock(clock).level(level).cpu(false));
            Span s = t.root();
            s.span(LOAD).close();          // the zero point is the first read; this one runs from 10 to 20
            s.done(DECODE, "Decode");      // a lap from the root's start, 0, to 30
            clock.add(100);
            t.close();                     // at 140
            assertEquals(lines(
                    "search 0+140",
                    "decode 0+30",
                    "load 10+10",
                    "top decode 0+30"), starts(t), "level " + level);
            assertEquals(140, t.elapsedNanos());
            assertEquals(140, t.snapshot().elapsedNanos());
            assertEquals("0.000000140s = 0.000000030s (Decode)", t.toRuntime(9));
            assertTrue(t.toHeader().contains(";S=00.0.0/"), t.toHeader()); // the root's wire start is 0 too
        }
    }

    @Test
    void theRootStartsAtZeroWithTheSystemClock() {
        for (int level : new int[] {COARSE, DETAIL}) {
            Trace t = Tempo.start(SEARCH, new Options().level(level));
            t.root().done(DECODE, "Decode");
            t.close();
            List<Long> starts = new ArrayList<>();
            t.forEachSpan(v -> starts.add(v.startNanos()));
            t.forEachTop((name, label, start, dur) -> starts.add(start));
            t.snapshot().forEachSpan(v -> starts.add(v.startNanos()));
            t.snapshot().forEachTop((name, label, start, dur) -> starts.add(start));
            assertEquals(List.of(0L, 0L, 0L, 0L, 0L, 0L), starts, "level " + level);
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
        Trace t = Tempo.start(SEARCH, new Options().clock(clock).cpuClock(cpu).level(DETAIL));
        t.root().span(LOAD).close();
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
        Trace t = Tempo.start(SEARCH, new Options().clock(clock).cpuClock(cpu).level(DETAIL));
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

        Recording started = new Recording(); // DETAIL from the start: the start reads it
        Trace a = Tempo.start(SEARCH, new Options().cpuClock(started).level(DETAIL));
        assertEquals(List.of(me), started.threads);
        Handoff h = a.root().handoff(MATCH);
        assertNull(onThread("worker", () -> h.begin().close()));
        a.close();
        assertEquals(List.of(me, "worker", "worker", me), started.threads);

        Recording raised = new Recording(); // COARSE, then raised by the handler: the raise reads it
        Trace b = Tempo.start(SEARCH, new Options().cpuClock(raised));
        Handoff early = b.root().handoff(MATCH); // submitted before the raise, begun after it
        assertEquals(List.of(), raised.threads);
        assertTrue(b.raise(DETAIL));
        assertEquals(List.of(me), raised.threads);
        assertNull(onThread("worker", () -> early.begin().close()));
        b.close();
        assertEquals(me, raised.threads.get(0));
        assertEquals(List.of(me, "worker", "worker"), raised.threads); // the root was not measured: COARSE at its open

        Recording off = new Recording(); // CPU off, or already measured: a raise reads nothing
        Trace c = Tempo.start(SEARCH, new Options().cpuClock(off).cpu(false));
        assertTrue(c.raise(DETAIL));
        c.close();
        Trace d = Tempo.start(SEARCH, new Options().cpuClock(off).cpu(true));
        assertTrue(d.raise(DETAIL));
        d.close();
        assertEquals(List.of(me, me), off.threads); // d's root: at the start and at the close

        Recording coarse = new Recording(); // COARSE throughout: never
        Trace e = Tempo.start(SEARCH, new Options().cpuClock(coarse));
        Handoff g = e.root().handoff(MATCH);
        assertNull(onThread("worker", () -> g.begin().close()));
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
            Trace t = Tempo.start(SEARCH, new Options().clock(clock).cpuClock(costly).level(DETAIL).budget(budget));
            clock.add(10);
            Handoff h = t.root().handoff(MATCH); // submitted at 17: the root's CPU read came first
            clock.add(30);
            assertNull(onThread("worker", () -> {
                Span m = h.begin();              // begins at 47, then reads CPU: 54
                cpu[0] += 3;
                clock.add(5);
                m.close();                       // ends at 59, then reads CPU: 66
            }));
            t.close();                           // 66
            Tempo.Aggregate a = t.aggregate(MATCH);
            assertEquals(30, a.waitSumNanos(), "budget " + budget + ": the wait is the time in the queue, no read in it");
            assertEquals(12, a.sumNanos(), "budget " + budget + ": the start's CPU read is the task's run time");
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
}
