package io.github.dualuse.tempo;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
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

import java.util.concurrent.TimeUnit;

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

    @Benchmark
    public void traceLifecycle() {
        Tempo.Trace t = Tempo.start(ROOT);
        t.close();
        t.release();
    }
}
