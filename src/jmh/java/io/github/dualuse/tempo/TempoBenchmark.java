package io.github.dualuse.tempo;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Control;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;

/**
 * Recording cost. Run with {@code -prof gc} and read {@code gc.alloc.rate.norm} (B/op).
 *
 * <p>The cycling benchmarks close, release and replace their trace every {@code cycle} operations,
 * so their B/op includes the per-trace setup divided by {@code cycle}. {@link CostGate} separates
 * the two and gates them in CI ({@code ./gradlew jmhGate}), together with the times of the cost promises
 * as ratios to {@link #baselineNanoTime()} in the same run.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TempoBenchmark {

    static final Tempo.Name ROOT = Tempo.name("bench"), CHILD = Tempo.name("child"), KEY = Tempo.name("key"),
            RATIO = Tempo.name("ratio"), BLOCK = Tempo.name("block");
    /** Drawn tasks count against the budget; the cycling benchmarks record every one of theirs. */
    static final Tempo.Options UNBUDGETED = new Tempo.Options().budget(Integer.MAX_VALUE);
    /** For the traces that draw their tasks (records steps), without CPU time, as at the level of spans. */
    static final Tempo.Options DRAWN = new Tempo.Options().budget(Integer.MAX_VALUE).cpu(false);
    static final Runnable NOTHING = () -> { };
    static final Tempo.ThrowingRunnable<RuntimeException> BODY = () -> { };
    static final Callable<Integer> ANSWER = () -> 42;   // a cached Integer: the body allocates nothing
    static final Supplier<Integer> SUPPLY = () -> 42;

    /** A pool that runs every task at once on the submitting thread. */
    static final class Inline extends AbstractExecutorService {
        @Override public void execute(Runnable r) { r.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    static final ExecutorService EXECUTOR = Tempo.executor(new Inline(), CHILD);

    @State(Scope.Thread)
    public static class Cycling {
        @Param({"1000", "16000"})
        int cycle;
        Tempo.Trace trace;
        int used;

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.trace(ROOT, UNBUDGETED);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            trace.close();
            trace.release();
        }

        Tempo.Trace cycled() {
            if (++used == cycle) {
                trace.close();
                trace.release();
                trace = Tempo.trace(ROOT, UNBUDGETED);
                used = 0;
            }
            return trace;
        }
    }

    /** Like {@link Cycling} for an operation that takes two rows and two events, so 8000 fit a lane. */
    @State(Scope.Thread)
    public static class CyclingTwice {
        @Param({"1000", "8000"})
        int cycle;
        Tempo.Trace trace;
        int used;

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.trace(ROOT, UNBUDGETED);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            trace.close();
            trace.release();
        }

        Tempo.Trace cycled() {
            if (++used == cycle) {
                trace.close();
                trace.release();
                trace = Tempo.trace(ROOT, UNBUDGETED);
                used = 0;
            }
            return trace;
        }
    }

    /**
     * Like {@link CyclingTwice}, in a trace that records steps, so its tasks are drawn: a task run inline
     * takes two rows (its record at the submit and its run) and two events (link and queue wait).
     */
    @State(Scope.Thread)
    public static class Drawing {
        @Param({"1000", "8000"})
        int cycle;
        Tempo.Trace trace;
        int used;

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.trace(ROOT, DRAWN).records(Tempo.STEP);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            trace.close();
            trace.release();
        }

        void cycle() {
            if (++used == cycle) {
                trace.close();
                trace.release();
                trace = Tempo.trace(ROOT, DRAWN).records(Tempo.STEP);
                used = 0;
            }
        }
    }

    /** A trace that records spans only, for the whole trial: steps are undrawn, ticks unrecorded. */
    @State(Scope.Thread)
    public static class SpansOnly {
        Tempo.Trace trace;

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.trace(ROOT, UNBUDGETED);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            trace.close();
            trace.release();
        }
    }

    /** A trace that records steps, without budget: every step folds into the lane's aggregate. */
    @State(Scope.Thread)
    public static class Folding {
        Tempo.Trace trace;

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.trace(ROOT, new Tempo.Options().budget(0)).records(Tempo.STEP);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            trace.close();
            trace.release();
        }
    }

    /**
     * Like {@link Cycling}, with a self-contained block (a nested trace) open in the trace the whole time, so
     * every operation records inside the block's scope.
     */
    @State(Scope.Thread)
    public static class InBlock {
        @Param({"1000", "16000"})
        int cycle;
        Tempo.Trace trace, block;
        int used;

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.trace(ROOT, UNBUDGETED);
            block = Tempo.trace(BLOCK);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            block.close();
            trace.close();
            trace.release();
        }

        void cycle() {
            if (++used == cycle) {
                block.close();
                trace.close();
                trace.release();
                trace = Tempo.trace(ROOT, UNBUDGETED);
                block = Tempo.trace(BLOCK);
                used = 0;
            }
        }
    }

    /** A trace of spans only with a self-contained block open in it for the whole trial: steps in it are undrawn. */
    @State(Scope.Thread)
    public static class SpansOnlyInBlock {
        Tempo.Trace trace, block;

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.trace(ROOT, UNBUDGETED);
            block = Tempo.trace(BLOCK);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            block.close();
            trace.close();
            trace.release();
        }
    }

    /**
     * A trace of spans that calls a self-contained block {@code cycle} times, each call with an undrawn task,
     * then the next trace. Each call keeps an aggregate entry of its own (the block's totals read it), so
     * a lane's table grows with the calls; up to {@code Kit.KEEP_AGGS} entries the thread keeps it for its
     * next trace. The cycles stay below that, so the per-trace setup is the same at both.
     */
    @State(Scope.Thread)
    public static class Blocks {
        @Param({"100", "500"})
        int cycle;
        Tempo.Trace trace;
        int used;

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.trace(ROOT, UNBUDGETED);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            trace.close();
            trace.release();
        }

        void cycle() {
            if (++used == cycle) {
                trace.close();
                trace.release();
                trace = Tempo.trace(ROOT, UNBUDGETED);
                used = 0;
            }
        }
    }

    @Benchmark
    public long baselineNanoTime() {
        return System.nanoTime();
    }

    @Benchmark
    public void span(Cycling s) {
        s.cycled().span(CHILD).close();
    }

    /**
     * A recorded span through the static API, which finds its parent through the ThreadLocal: cost promise
     * 1 (0 B) and 4 (at most 6.4 times {@link #baselineNanoTime()}).
     */
    @Benchmark
    public void spanStatic(Cycling s) {
        s.cycled(); // cycles the trace like the others
        Tempo.span(CHILD).close();
    }

    /** The compact form, {@code Tempo.run(name, body)}: a recorded span around a body that does nothing. */
    @Benchmark
    public void run(Cycling s) {
        s.cycled();
        Tempo.run(CHILD, BODY);
    }

    /** A step in a trace that records steps: drawn, one row (cost promise 1). */
    @Benchmark
    public void stepDrawn(Drawing s) {
        s.cycle();
        Tempo.step(CHILD).close();
    }

    @Benchmark
    public void spanNotInlined(Cycling s) {
        open(s.cycled()).close();
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static Tempo.Span open(Tempo.Span parent) {
        return parent.span(CHILD);
    }

    /** One boundary: ends the previous phase and opens the next at a single clock read, with its own handle. */
    @Benchmark
    public Tempo.Phase phase(Cycling s) {
        return s.cycled().phase(CHILD);
    }

    /** One phase named after the fact, from the last boundary to now. */
    @Benchmark
    public Tempo.Span done(Cycling s) {
        return s.cycled().done(CHILD);
    }

    @Benchmark
    public void setLong(Cycling s) {
        s.cycled().set(KEY, 42L);
    }

    /** Cost promise 3: an unrecorded tick through a span, {@code s.tick(name)}, in a trace of spans only: a branch. */
    @Benchmark
    public Tempo.Span tickUnrecorded(SpansOnly s) {
        Tempo.Span d = s.trace.tick(CHILD);
        d.close();
        return d;
    }

    /** Submit side of a drawn task: its record and the one {@code Task} object (cost promise 2). */
    @Benchmark
    public Tempo.Task task(Drawing s) {
        s.cycle();
        return Tempo.task(CHILD);
    }

    /**
     * A drawn task run on the submitting thread (caller runs): its record at the submit, its run as a
     * child span with its queue wait, and the one {@code RunnableTask} object.
     */
    @Benchmark
    public void taskInline(Drawing s) {
        s.cycle();
        Tempo.runnable(CHILD, NOTHING).run();
    }

    /**
     * A task past the budget run on the submitting thread: no row, the one {@code RunnableTask}, the submit
     * counted in its aggregate and the run folded into the lane's share of it.
     */
    @Benchmark
    public void taskFolded(Folding s) {
        Tempo.runnable(CHILD, NOTHING).run();
    }

    /** A drawn callable task called on the submitting thread: one {@code CallableTask}, which is also its future. */
    @Benchmark
    public Integer callableInline(Drawing s) throws Exception {
        s.cycle();
        return Tempo.callable(CHILD, ANSWER).call();
    }

    /** A drawn supplier task got on the submitting thread: one {@code SupplierTask}. */
    @Benchmark
    public Integer supplierInline(Drawing s) {
        s.cycle();
        return Tempo.supplier(CHILD, SUPPLY).get();
    }

    /** A task a trace of spans does not draw, run on the submitting thread: two clock reads, its totals, one object. */
    @Benchmark
    public void taskUndrawn(SpansOnly s) {
        Tempo.runnable(CHILD, NOTHING).run();
    }

    /**
     * A submit to {@code Tempo.executor} over a pool that runs it at once: a drawn task that is also the
     * future returned, so one object (cost promise 2).
     */
    @Benchmark
    public Future<?> executorSubmit(Drawing s) {
        s.cycle();
        return EXECUTOR.submit(NOTHING);
    }

    /** A callable submitted to {@code Tempo.executor}: one task, which is the future returned (cost promise 2). */
    @Benchmark
    public Future<Integer> executorCall(Drawing s) {
        s.cycle();
        return EXECUTOR.submit(ANSWER);
    }

    /**
     * A {@code CallableTask} made by {@code Tempo.callable} and submitted to {@code Tempo.executor}: the
     * executor hands the task itself to the pool, and returns it as the future, so one object (cost promise 2).
     */
    @Benchmark
    public Future<Integer> executorCallableTask(Drawing s) {
        s.cycle();
        return EXECUTOR.submit(Tempo.callable(CHILD, ANSWER));
    }

    /** A duration measured elsewhere, added to the totals of its name: {@code s.add(name, nanos)}. */
    @Benchmark
    public Tempo.Span add(SpansOnly s) {
        return s.trace.add(CHILD, 1000);
    }

    /**
     * Cost promise 3: an undrawn step through a span, in a trace of spans only: two clock reads and its
     * totals, no row, 0 B.
     */
    @Benchmark
    public Tempo.Span stepUndrawn(SpansOnly s) {
        Tempo.Span d = s.trace.step(CHILD);
        d.close();
        return d;
    }

    /**
     * A recorded span through the static API inside an open self-contained block: the block's scope is the
     * lane's, so it costs what a span outside does, 0 B in steady state.
     */
    @Benchmark
    public void spanInBlock(InBlock s) {
        s.cycle();
        Tempo.span(CHILD).close();
    }

    /** An undrawn step inside an open block, through the block: timed into its lane's entry of the block's scope, 0 B. */
    @Benchmark
    public Tempo.Span stepUndrawnInBlock(SpansOnlyInBlock s) {
        Tempo.Span d = s.block.step(CHILD);
        d.close();
        return d;
    }

    /**
     * A nested trace, opened and closed in a trace: a self-contained block, whose one allocation is its
     * {@code Trace}, the block's span handle and scope in one object.
     */
    @Benchmark
    public Tempo.Trace nestedTrace(Cycling s) {
        s.cycled();
        Tempo.Trace in = Tempo.trace(BLOCK);
        in.close();
        return in;
    }

    /**
     * A block called many times in one trace, each call with an undrawn task run inline: the block's {@code
     * Trace} and the {@code RunnableTask} are the call's allocations, and its cost does not grow with the
     * calls before it (its entry is found in constant time).
     */
    @Benchmark
    public Tempo.Trace blockCallWithTask(Blocks s) {
        s.cycle();
        Tempo.Trace in = Tempo.trace(BLOCK);
        Tempo.runnable(CHILD, NOTHING).run();
        in.close();
        return in;
    }

    /**
     * {@code add} in a block called many times: the duration goes to the lane's undrawn entry of the name in
     * the block, so the call allocates its {@code Trace} only.
     */
    @Benchmark
    public Tempo.Trace addInBlock(Blocks s) {
        s.cycle();
        Tempo.Trace in = Tempo.trace(BLOCK);
        in.add(CHILD, 1000);
        in.close();
        return in;
    }

    /** Explicit handle, a trace of steps over its budget: a folded step (two clock reads, an aggregate update). */
    @Benchmark
    public Tempo.Span folded(Folding s) {
        Tempo.Span d = s.trace.step(CHILD);
        d.close();
        return d;
    }

    /** One read of the calling thread's CPU time, as taken at lane roots and by {@code Span.cpu()}. */
    @Benchmark
    public long cpuTime() {
        return Tempo.Cpu.now();
    }

    /** A static tick without a trace, opened and closed: the ThreadLocal read and a branch. */
    @Benchmark
    public Tempo.Span tickNoTrace() {
        Tempo.Span d = Tempo.tick(CHILD);
        d.close();
        return d;
    }

    /**
     * Cost promise 3: a static tick in a trace of spans only, opened and closed, which may add the
     * ThreadLocal read to the branch. Closed, so a tick that opened a frame by mistake would show its
     * clock reads here instead of filling the stack and taking its cheap early exit.
     */
    @Benchmark
    public Tempo.Span tickStatic(SpansOnly s) {
        Tempo.Span d = Tempo.tick(CHILD);
        d.close();
        return d;
    }

    /**
     * Tasks across threads: {@code submit} creates a task per op and passes it through a ring to {@code
     * work}, which runs it on its own thread, as a pool thread would. A group op is one of either side, so
     * the group's B/op is half the bytes per task. The submit side starts a new trace every {@code cycle}
     * tasks, so the worker sets up its lane of each trace once; the gate solves that per-trace setup out
     * of the two cycle lengths, as for the cycling benchmarks, and holds it to a ceiling of its own.
     */
    @State(Scope.Group)
    public static class Pipe {
        static final int SIZE = 16;
        @Param({"1000", "8000"})
        int cycle;
        final AtomicReferenceArray<Tempo.Task> ring = new AtomicReferenceArray<>(SIZE);
        long put, take; // each used by one side only
        Tempo.Trace trace;
        int used;

        /** The submit side: the trace of the next task, a new one every {@code cycle} tasks. */
        Tempo.Trace cycled(boolean drawn) {
            if (trace == null || ++used == cycle) {
                if (trace != null) {
                    trace.close();
                    trace.release();
                }
                trace = drawn ? Tempo.trace(ROOT, DRAWN).records(Tempo.STEP) : Tempo.trace(ROOT, UNBUDGETED);
                used = 0;
            }
            return trace;
        }

        void put(Tempo.Task h, Control c) {
            int i = (int) (put & (SIZE - 1));
            while (ring.get(i) != null) if (c.stopMeasurement) return;
            ring.lazySet(i, h);
            put++;
        }

        Tempo.Task take(Control c) {
            int i = (int) (take & (SIZE - 1));
            Tempo.Task h;
            while ((h = ring.get(i)) == null) if (c.stopMeasurement) return null;
            ring.lazySet(i, null);
            take++;
            return h;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (trace != null) trace.release();
        }
    }

    /** A drawn bare task, begun by hand on the worker: 48 B, the one {@code Task} (cost promise 2). */
    @Benchmark
    @Group("across")
    public void submit(Pipe p, Control c) {
        p.cycled(true);
        p.put(Tempo.task(CHILD), c);
    }

    @Benchmark
    @Group("across")
    public void work(Pipe p, Control c) {
        Tempo.Task h = p.take(c);
        if (h != null) h.tryBegin().close();
    }

    /**
     * An undrawn {@code RunnableTask}, as a trace of spans makes by default, run on the worker: 56 B, the
     * one task, which is also its future (cost promise 2).
     */
    @Benchmark
    @Group("acrossUndrawn")
    public void submitUndrawn(Pipe p, Control c) {
        p.cycled(false);
        p.put(Tempo.runnable(CHILD, NOTHING), c);
    }

    @Benchmark
    @Group("acrossUndrawn")
    public void runUndrawn(Pipe p, Control c) {
        Tempo.Task h = p.take(c);
        if (h != null) ((Tempo.RunnableTask) h).run();
    }

    /**
     * A closed trace of 300 spans to render: 30 spans of 9 children each, a long attribute on every third
     * child and a double on every fifth. The header limit is raised so the whole value is written.
     */
    @State(Scope.Thread)
    public static class Rendered {
        Tempo.Trace trace;
        Tempo.Snapshot snapshot;
        final StringBuilder json = new StringBuilder(1 << 16);

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.trace(ROOT, new Tempo.Options().headerBytes(1 << 16));
            for (int i = 0; i < 30; i++) {
                try (Tempo.Span parent = Tempo.span(CHILD)) {
                    for (int j = 0; j < 9; j++) {
                        Tempo.Span c = parent.span(j % 2 == 0 ? CHILD : KEY);
                        if (j % 3 == 0) c.set(KEY, 1000L * i + j);
                        if (j % 5 == 0) c.set(RATIO, i / 7.0);
                        c.close();
                    }
                }
            }
            trace.close();
            snapshot = trace.snapshot();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            trace.release();
        }
    }

    /** {@code Trace.toHeader()}: a snapshot of the trace, then the value. */
    @Benchmark
    public String toHeader(Rendered s) {
        return s.trace.toHeader();
    }

    /** The value of a snapshot taken once: the encoder alone. */
    @Benchmark
    public String snapshotToHeader(Rendered s) {
        return s.snapshot.toHeader();
    }

    /** The JSON form of a snapshot taken once, into a reused builder: the encoder's walks alone. */
    @Benchmark
    public int snapshotWriteJson(Rendered s) throws java.io.IOException {
        s.json.setLength(0);
        s.snapshot.writeJson(s.json);
        return s.json.length();
    }

    /**
     * The same value under 1,000 bytes: the encoder, then the ladder of FORMAT.md §7.1, whose first fold
     * (the root's 30 children, the largest group) fits. Reported, not gated.
     */
    @Benchmark
    public String snapshotToHeaderLadder(Rendered s) {
        return s.snapshot.toHeader(1000);
    }

    /** A Tempo-Link of at most 1,000 bytes: the ladder, each step compressed until the link fits. Reported, not gated. */
    @Benchmark
    public String snapshotToLinkLadder(Rendered s) {
        return s.snapshot.toLink("https://dualuse.github.io/tempo/", 1000);
    }

    @Benchmark
    public void traceLifecycle() {
        Tempo.Trace t = Tempo.trace(ROOT, UNBUDGETED);
        t.close();
        t.release();
    }
}
