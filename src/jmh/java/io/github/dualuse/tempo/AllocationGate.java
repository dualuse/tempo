package io.github.dualuse.tempo;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs the allocation-gated benchmarks of {@link TempoBenchmark} with {@code -prof gc} and exits
 * non-zero when a {@code gc.alloc.rate.norm} exceeds its gate.
 *
 * <p>A cycling benchmark pays a fixed per-trace setup every {@code cycle} operations, so its B/op is
 * {@code perOp + setup / cycle}. The gate applies to {@code perOp}, solved from the two cycle lengths;
 * the setup is reported, and {@code traceLifecycle} is held to a ceiling of its own. A handoff allocates
 * exactly one small object, so the handoff benchmarks are gated at its size instead of zero.
 *
 * <p>Rendering the 300-span trace of {@code Rendered} is held to ceilings: the encoder alone
 * ({@code snapshotToHeader}) to its one output String (3,440 characters, 3.5 KB) plus about 2 KB of
 * per-render tables and walk state, measured 5.2 KB; {@code toHeader} adds the copy of the trace that
 * {@code snapshot()} takes, measured 22.6 KB. BENCHMARKS.md records the numbers.
 */
public final class AllocationGate {

    static final double PER_OP = 0.01;   // B/op
    static final double SETUP = 1024;    // B per trace start + close + release
    static final double HANDOFF = 32;    // B per handoff: exactly one small object
    static final double ENCODE = 6 * 1024, RENDER = 24 * 1024; // B per render of the 300-span trace
    static final String GATED = "span|spanNotInlined|phase|done|setLong|detailDisabled|traceLifecycle"
            + "|handoff|handoffInline|handoffFolded|currentDetailNoTrace|currentDetailCoarse|waited|alwaysOn|folded"
            + "|snapshotToHeader|toHeader";

    public static void main(String[] args) throws RunnerException {
        Iterable<RunResult> results = new Runner(new OptionsBuilder()
                .include(TempoBenchmark.class.getName() + "\\.(" + GATED + ")$")
                .addProfiler(GCProfiler.class)
                .forks(1)
                .warmupIterations(3).warmupTime(TimeValue.seconds(1))
                .measurementIterations(3).measurementTime(TimeValue.seconds(1))
                .build()).run();

        Map<String, Map<Integer, Double>> norm = new TreeMap<>(); // benchmark -> cycle (0 if none) -> B/op
        for (RunResult r : results) {
            BenchmarkParams p = r.getParams();
            String name = p.getBenchmark().substring(p.getBenchmark().lastIndexOf('.') + 1);
            String cycle = p.getParam("cycle");
            norm.computeIfAbsent(name, k -> new TreeMap<>()).put(cycle == null ? 0 : Integer.parseInt(cycle), allocNorm(r));
        }

        boolean ok = true;
        System.out.println();
        for (Map.Entry<String, Map<Integer, Double>> e : norm.entrySet()) {
            Map<Integer, Double> byCycle = e.getValue();
            if (byCycle.size() == 2) {
                int c1 = Collections.min(byCycle.keySet()), c2 = Collections.max(byCycle.keySet());
                double b1 = byCycle.get(c1), b2 = byCycle.get(c2);
                double perOp = (b2 * c2 - b1 * c1) / (c2 - c1), setup = (b1 - perOp) * c1;
                double gate = e.getKey().startsWith("handoff") ? HANDOFF + PER_OP : PER_OP;
                ok &= check(e.getKey(), perOp, gate, String.format(" B/op net of %.0f B per trace", setup));
            } else if (e.getKey().equals("traceLifecycle")) {
                ok &= check(e.getKey(), byCycle.get(0), SETUP, " B/trace");
            } else if (e.getKey().endsWith("oHeader")) {
                double gate = e.getKey().equals("toHeader") ? RENDER : ENCODE;
                ok &= check(e.getKey(), byCycle.get(0), gate, " B/render");
            } else {
                ok &= check(e.getKey(), byCycle.get(0), e.getKey().startsWith("handoff") ? HANDOFF + PER_OP : PER_OP, " B/op");
            }
        }
        int expected = GATED.split("\\|").length;
        if (norm.size() != expected) {
            System.out.println("FAIL  expected " + expected + " gated benchmarks, ran " + norm.keySet());
            ok = false;
        }
        System.exit(ok ? 0 : 1);
    }

    private static double allocNorm(RunResult r) {
        for (Map.Entry<String, Result> e : r.getSecondaryResults().entrySet())
            if (e.getKey().endsWith("gc.alloc.rate.norm")) return e.getValue().getScore();
        throw new IllegalStateException("no gc.alloc.rate.norm for " + r.getParams().getBenchmark());
    }

    private static boolean check(String name, double value, double limit, String unit) {
        boolean pass = value <= limit;
        System.out.printf("%s  %-16s %10.4f%s (gate %s)%n", pass ? "ok  " : "FAIL", name, value, unit, limit);
        return pass;
    }
}
