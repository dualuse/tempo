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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Recording cost. Run with {@code -prof gc} and read {@code gc.alloc.rate.norm} (B/op).
 *
 * <p>The cycling benchmarks close, release and replace their trace every {@code cycle} operations,
 * so their B/op includes the per-trace setup divided by {@code cycle}. {@link AllocationGate} separates
 * the two and gates them in CI ({@code ./gradlew jmhGate}).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TempoBenchmark {

    static final Tempo.Name ROOT = Tempo.name("bench"), CHILD = Tempo.name("child"), KEY = Tempo.name("key"),
            RATIO = Tempo.name("ratio");
    /** Handoffs count against the budget; the cycling benchmarks record every one of theirs. */
    static final Tempo.Options UNBUDGETED = new Tempo.Options().budget(Integer.MAX_VALUE);

    @State(Scope.Thread)
    public static class Cycling {
        @Param({"1000", "16000"})
        int cycle;
        Tempo.Trace trace;
        Tempo.Span root;
        int used;

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.trace(ROOT, UNBUDGETED);
            root = trace;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            trace.close();
            trace.release();
        }

        Tempo.Span root() {
            if (++used == cycle) {
                trace.close();
                trace.release();
                trace = Tempo.trace(ROOT, UNBUDGETED);
                root = trace;
                used = 0;
            }
            return root;
        }
    }

    /** Like {@link Cycling} for an operation that takes two rows and two events, so 8000 fit a lane. */
    @State(Scope.Thread)
    public static class CyclingTwice {
        @Param({"1000", "8000"})
        int cycle;
        Tempo.Trace trace;
        Tempo.Span root;
        int used;

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.trace(ROOT, UNBUDGETED);
            root = trace;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            trace.close();
            trace.release();
        }

        Tempo.Span root() {
            if (++used == cycle) {
                trace.close();
                trace.release();
                trace = Tempo.trace(ROOT, UNBUDGETED);
                root = trace;
                used = 0;
            }
            return root;
        }
    }

    @State(Scope.Thread)
    public static class Coarse {
        Tempo.Trace trace;
        Tempo.Span root;

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.trace(ROOT, UNBUDGETED);
            root = trace;
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
        Tempo.Span root;

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.trace(ROOT, new Tempo.Options().budget(0)).records(Tempo.STEP);
            root = trace;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            trace.close();
            trace.release();
        }
    }

    @Benchmark
    public long baselineNanoTime() {
        return System.nanoTime();
    }

    @Benchmark
    public void span(Cycling s) {
        s.root().span(CHILD).close();
    }

    /** Cost promise 4: a span through the static API, which finds its parent through the ThreadLocal. */
    @Benchmark
    public void spanStatic(Cycling s) {
        s.root(); // cycles the trace like the others
        Tempo.span(CHILD).close();
    }

    @Benchmark
    public void spanNotInlined(Cycling s) {
        open(s.root()).close();
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static Tempo.Span open(Tempo.Span parent) {
        return parent.span(CHILD);
    }

    /** One boundary: ends the previous phase and opens the next at a single clock read. */
    @Benchmark
    public Tempo.Span phase(Cycling s) {
        return s.root().phase(CHILD);
    }

    /** One label-after lap from the last boundary to now. */
    @Benchmark
    public Tempo.Span done(Cycling s) {
        return s.root().done(CHILD);
    }

    @Benchmark
    public void setLong(Cycling s) {
        s.root().set(KEY, 42L);
    }

    /** Explicit handle, a trace of spans only: an unrecorded tick, the true no-op path. */
    @Benchmark
    public Tempo.Span tickDisabled(Coarse s) {
        Tempo.Span d = s.root.tick(CHILD);
        d.close();
        return d;
    }

    /** Submit side of a handoff: the handoff row and the one {@code Handoff} object. */
    @Benchmark
    public Tempo.Handoff handoff(Cycling s) {
        return s.root().handoff(CHILD);
    }

    /** A handoff begun and closed on the submitting thread (caller runs): a child span plus the handoff. */
    @Benchmark
    public void handoffInline(CyclingTwice s) {
        s.root().handoff(CHILD).begin().close();
    }

    /**
     * A handoff past the budget begun and closed on the submitting thread: no row, the one {@code Handoff}
     * object, the submit counted in its aggregate and the task folded into the lane's share of it.
     */
    @Benchmark
    public void handoffFolded(Folding s) {
        s.root.handoff(CHILD).begin().close();
    }

    /** Explicit handle, a trace of spans only: an undrawn step (two clock reads, no row, its total). */
    @Benchmark
    public Tempo.Span stepUndrawn(Coarse s) {
        Tempo.Span d = s.root.step(CHILD);
        d.close();
        return d;
    }

    /** Explicit handle, a trace of steps over its budget: a folded step (two clock reads, an aggregate update). */
    @Benchmark
    public Tempo.Span folded(Folding s) {
        Tempo.Span d = s.root.step(CHILD);
        d.close();
        return d;
    }

    /** One read of the calling thread's CPU time, as taken at lane roots and by {@code Span.cpu()}. */
    @Benchmark
    public long cpuTime() {
        return Tempo.Cpu.now();
    }

    /** A static tick without a trace: the ThreadLocal read and a branch. */
    @Benchmark
    public Tempo.Span tickNoTrace() {
        return Tempo.tick(CHILD);
    }

    /** A static tick in a trace of spans only: the ThreadLocal read and the level check. */
    @Benchmark
    public Tempo.Span tickStatic(Coarse s) {
        return Tempo.tick(CHILD);
    }

    /**
     * Handoffs across threads: {@code submit} creates a handoff per op and passes it through a ring to
     * {@code work}, which begins and closes it on its own thread. A group op is one of either side, so
     * the group's B/op is half the bytes per handoff; the submit side cycles its trace every 1000.
     */
    @State(Scope.Group)
    public static class Pipe {
        static final int SIZE = 16;
        final AtomicReferenceArray<Tempo.Handoff> ring = new AtomicReferenceArray<>(SIZE);
        long put, take; // each used by one side only
        Tempo.Trace trace;
        int used;

        @TearDown(Level.Trial)
        public void tearDown() {
            if (trace != null) trace.release();
        }
    }

    @Benchmark
    @Group("across")
    public void submit(Pipe p, Control c) {
        if (p.trace == null || ++p.used == 1000) {
            if (p.trace != null) {
                p.trace.close();
                p.trace.release();
            }
            p.trace = Tempo.trace(ROOT, UNBUDGETED);
            p.used = 0;
        }
        Tempo.Handoff h = p.trace.handoff(CHILD);
        int i = (int) (p.put & (Pipe.SIZE - 1));
        while (p.ring.get(i) != null) if (c.stopMeasurement) return;
        p.ring.lazySet(i, h);
        p.put++;
    }

    @Benchmark
    @Group("across")
    public void work(Pipe p, Control c) {
        int i = (int) (p.take & (Pipe.SIZE - 1));
        Tempo.Handoff h;
        while ((h = p.ring.get(i)) == null) if (c.stopMeasurement) return;
        p.ring.lazySet(i, null);
        p.take++;
        h.begin().close();
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
            Tempo.Span root = trace;
            for (int i = 0; i < 30; i++) {
                try (Tempo.Span parent = root.span(CHILD)) {
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
