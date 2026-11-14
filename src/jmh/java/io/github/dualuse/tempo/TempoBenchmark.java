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

    static final Tempo.Name ROOT = Tempo.name("bench"), CHILD = Tempo.name("child"), KEY = Tempo.name("key");

    @State(Scope.Thread)
    public static class Cycling {
        @Param({"1000", "16000"})
        int cycle;
        Tempo.Trace trace;
        Tempo.Span root;
        int used;

        @Setup(Level.Trial)
        public void setUp() {
            trace = Tempo.start(ROOT);
            root = trace.root();
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
                trace = Tempo.start(ROOT);
                root = trace.root();
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
            trace = Tempo.start(ROOT);
            root = trace.root();
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
                trace = Tempo.start(ROOT);
                root = trace.root();
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
            trace = Tempo.start(ROOT);
            root = trace.root();
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

    /** Explicit handle, unmarked name, COARSE trace: the true no-op path. */
    @Benchmark
    public Tempo.Span detailDisabled(Coarse s) {
        Tempo.Span d = s.root.detail(CHILD);
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

    @Benchmark
    public Tempo.Span currentDetailNoTrace() {
        return Tempo.current().detail(CHILD);
    }

    @Benchmark
    public Tempo.Span currentDetailCoarse(Coarse s) {
        return Tempo.current().detail(CHILD);
    }

    /** Always-on queue wait without a lane: one clock read at submit, one at start, a lane-less aggregate. */
    @Benchmark
    public long waited(Coarse s) {
        return s.trace.waited(CHILD, s.trace.submitted());
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
            p.trace = Tempo.start(ROOT);
            p.used = 0;
        }
        Tempo.Handoff h = p.trace.root().handoff(CHILD);
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

    @Benchmark
    public void traceLifecycle() {
        Tempo.Trace t = Tempo.start(ROOT);
        t.close();
        t.release();
    }
}
