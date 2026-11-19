package io.github.dualuse.tempo;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * The cost gate CI runs ({@code ./gradlew jmhGate}): the gated benchmarks of {@link TempoBenchmark} in one
 * JMH run with {@code -prof gc}, checked against the four cost promises and the render ceilings. Exits
 * non-zero when one fails.
 *
 * <p>Allocation is {@code gc.alloc.rate.norm}. A cycling benchmark pays a fixed per-trace setup every
 * {@code cycle} operations, so its B/op is {@code perOp + setup / cycle}. The gate applies to {@code
 * perOp}, solved from the two cycle lengths; the setup is reported, and {@code traceLifecycle} is held to
 * a ceiling of its own.
 *
 * <p>Time is gated only as a ratio to {@link TempoBenchmark#baselineNanoTime()}, one {@code
 * System.nanoTime()}, measured in the same run on the same machine: absolute times differ between
 * machines, the ratio much less. Each time is the median of the measurement iterations (of the longer
 * cycle for a cycling benchmark), so one slow iteration on a busy machine does not decide the gate.
 *
 * <ol>
 * <li>0 B per recorded span in steady state. A recorded span takes a handle from its thread's pool, which
 * a released trace refills; a handle allocated per span, in one trace or in every trace, grows with the
 * cycle and shows in {@code perOp}. Gated: {@code span}, {@code spanStatic}, {@code spanNotInlined},
 * {@code run}, {@code stepDrawn}, {@code phase}, {@code done}, {@code setLong}.
 * <li>One object per task: the task is the one allocation of its submit, drawn, folded or undrawn,
 * and through {@code Tempo.executor}, where it is also the future. Gated at the size of that object
 * (64-bit JVM, compressed oops): a {@code Task} without a body or a {@code SupplierTask}, 48 B; a
 * {@code RunnableTask} or {@code CallableTask}, which is also its future, 56 B.
 * <li>An unrecorded tick costs about a branch through {@code s.tick(name)}: under half of one clock read,
 * so it reads no clock. The static {@code Tempo.tick(name)} may add the ThreadLocal read: under one
 * clock read. Both allocate nothing. An undrawn step is always timed: its time is reported, its
 * allocation gated at 0 B.
 * <li>A recorded span through the static API, {@code Tempo.span(name).close()}, costs at most 6.4 times
 * one clock read.
 * </ol>
 *
 * <p>Rendering the 300-span trace of {@code Rendered} is held to ceilings: the encoder alone ({@code
 * snapshotToHeader}) to its one output String (3,440 characters, 3.5 KB) plus about 2 KB of per-render
 * tables and walk state; {@code toHeader} adds the copy of the trace that {@code snapshot()} takes. That
 * copy is an accepted deviation from "one output String": a render runs once per request, and avoiding
 * it would need a second walker over the live lanes. Its JSON form into a reused builder ({@code
 * snapshotWriteJson}) has no output String, only the tables and walk state. BENCHMARKS.md records the
 * numbers.
 */
public final class CostGate {

    static final double NOTHING = 0.01;  // B/op: allocates nothing
    static final double TASK = 48, RUNNABLE_TASK = 56; // B per task: exactly one object
    static final double SETUP = 1024;    // B per trace start + close + release
    static final double ENCODE = 6 * 1024, RENDER = 24 * 1024, JSON = 2560; // B per render of the 300-span trace
    static final double TICK = 0.5, TICK_STATIC = 1.0, SPAN_STATIC = 6.4; // times one clock read
    static final String BASELINE = "baselineNanoTime";

    enum Unit { PER_OP, PER_TRACE, PER_RENDER, TIMES_BASELINE }

    /** One check: a benchmark, what is measured, and its limit; {@code limit < 0} only reports. */
    static final class Gate {
        final String group, benchmark;
        final Unit unit;
        final double limit;

        Gate(String group, String benchmark, Unit unit, double limit) {
            this.group = group;
            this.benchmark = benchmark;
            this.unit = unit;
            this.limit = limit;
        }
    }

    static final String P1 = "Promise 1: 0 B per recorded span, steady state",
            P2 = "Promise 2: one object per task",
            P3 = "Promise 3: an unrecorded tick costs about a branch; an undrawn step allocates nothing",
            P4 = "Promise 4: a recorded static span costs at most 6.4 clock reads",
            REST = "Allocation of the other operations, and the render ceilings";

    static final List<Gate> GATES = List.of(
            new Gate(P1, "span", Unit.PER_OP, NOTHING),
            new Gate(P1, "spanStatic", Unit.PER_OP, NOTHING),
            new Gate(P1, "spanNotInlined", Unit.PER_OP, NOTHING),
            new Gate(P1, "run", Unit.PER_OP, NOTHING),
            new Gate(P1, "stepDrawn", Unit.PER_OP, NOTHING),
            new Gate(P1, "phase", Unit.PER_OP, NOTHING),
            new Gate(P1, "done", Unit.PER_OP, NOTHING),
            new Gate(P1, "setLong", Unit.PER_OP, NOTHING),

            new Gate(P2, "task", Unit.PER_OP, TASK + NOTHING),
            new Gate(P2, "supplierInline", Unit.PER_OP, TASK + NOTHING),
            new Gate(P2, "taskInline", Unit.PER_OP, RUNNABLE_TASK + NOTHING),
            new Gate(P2, "callableInline", Unit.PER_OP, RUNNABLE_TASK + NOTHING),
            new Gate(P2, "taskFolded", Unit.PER_OP, RUNNABLE_TASK + NOTHING),
            new Gate(P2, "taskUndrawn", Unit.PER_OP, RUNNABLE_TASK + NOTHING),
            new Gate(P2, "executorSubmit", Unit.PER_OP, RUNNABLE_TASK + NOTHING),
            new Gate(P2, "executorCall", Unit.PER_OP, RUNNABLE_TASK + NOTHING),

            new Gate(P3, "tickUnrecorded", Unit.PER_OP, NOTHING),
            new Gate(P3, "tickUnrecorded", Unit.TIMES_BASELINE, TICK),
            new Gate(P3, "tickStatic", Unit.PER_OP, NOTHING),
            new Gate(P3, "tickStatic", Unit.TIMES_BASELINE, TICK_STATIC),
            new Gate(P3, "tickNoTrace", Unit.PER_OP, NOTHING),
            new Gate(P3, "tickNoTrace", Unit.TIMES_BASELINE, TICK_STATIC),
            new Gate(P3, "stepUndrawn", Unit.PER_OP, NOTHING),
            new Gate(P3, "stepUndrawn", Unit.TIMES_BASELINE, -1),

            new Gate(P4, "spanStatic", Unit.TIMES_BASELINE, SPAN_STATIC),
            new Gate(P4, "span", Unit.TIMES_BASELINE, -1),

            new Gate(REST, "folded", Unit.PER_OP, NOTHING),
            new Gate(REST, "add", Unit.PER_OP, NOTHING),
            new Gate(REST, "traceLifecycle", Unit.PER_TRACE, SETUP),
            new Gate(REST, "snapshotToHeader", Unit.PER_RENDER, ENCODE),
            new Gate(REST, "toHeader", Unit.PER_RENDER, RENDER),
            new Gate(REST, "snapshotWriteJson", Unit.PER_RENDER, JSON));

    public static void main(String[] args) throws RunnerException {
        StringJoiner include = new StringJoiner("|", TempoBenchmark.class.getName() + "\\.(", ")$");
        include.add(BASELINE);
        GATES.stream().map(g -> g.benchmark).distinct().forEach(include::add);

        Iterable<RunResult> results = new Runner(new OptionsBuilder()
                .include(include.toString())
                .addProfiler(GCProfiler.class)
                .forks(1)
                .warmupIterations(3).warmupTime(TimeValue.seconds(1))
                .measurementIterations(3).measurementTime(TimeValue.seconds(1))
                .build()).run();

        Map<String, Map<Integer, Double>> bytes = new TreeMap<>(); // benchmark -> cycle (0 if none) -> B/op
        Map<String, Map<Integer, Double>> nanos = new TreeMap<>(); // benchmark -> cycle -> median ns/op
        for (RunResult r : results) {
            BenchmarkParams p = r.getParams();
            String name = p.getBenchmark().substring(p.getBenchmark().lastIndexOf('.') + 1);
            String c = p.getParam("cycle");
            int cycle = c == null ? 0 : Integer.parseInt(c);
            bytes.computeIfAbsent(name, k -> new TreeMap<>()).put(cycle, allocNorm(r));
            nanos.computeIfAbsent(name, k -> new TreeMap<>()).put(cycle, r.getPrimaryResult().getStatistics().getPercentile(50));
        }

        boolean ok = true;
        List<String> missing = new ArrayList<>();
        Double baseline = nanos.containsKey(BASELINE) ? nanos.get(BASELINE).get(0) : null;
        if (baseline == null) missing.add(BASELINE);
        String group = null;
        for (Gate g : GATES) {
            if (!g.group.equals(group)) {
                System.out.println();
                System.out.println(group = g.group);
            }
            Map<Integer, Double> byCycle = (g.unit == Unit.TIMES_BASELINE ? nanos : bytes).get(g.benchmark);
            if (byCycle == null || g.unit == Unit.TIMES_BASELINE && baseline == null) {
                missing.add(g.benchmark);
                ok = false;
                continue;
            }
            int longest = Collections.max(byCycle.keySet());
            switch (g.unit) {
                case PER_OP:
                    if (byCycle.size() == 2) {
                        int c1 = Collections.min(byCycle.keySet());
                        double b1 = byCycle.get(c1), b2 = byCycle.get(longest);
                        double perOp = (b2 * longest - b1 * c1) / (longest - c1), setup = (b1 - perOp) * c1;
                        ok &= check(g, perOp, String.format("%.4f B/op net of %.0f B per trace", perOp, setup));
                    } else {
                        ok &= check(g, byCycle.get(0), String.format("%.4f B/op", byCycle.get(0)));
                    }
                    break;
                case PER_TRACE:
                    ok &= check(g, byCycle.get(0), String.format("%.0f B/trace", byCycle.get(0)));
                    break;
                case PER_RENDER:
                    ok &= check(g, byCycle.get(0), String.format("%.0f B/render", byCycle.get(0)));
                    break;
                case TIMES_BASELINE:
                    double ns = byCycle.get(longest), ratio = ns / baseline;
                    ok &= check(g, ratio, String.format("%.1f ns = %.2f x %s %.1f ns", ns, ratio, BASELINE, baseline));
                    break;
            }
        }
        if (!missing.isEmpty()) {
            System.out.println("FAIL  no result for " + missing);
            ok = false;
        }
        System.exit(ok ? 0 : 1);
    }

    private static double allocNorm(RunResult r) {
        for (Map.Entry<String, Result> e : r.getSecondaryResults().entrySet())
            if (e.getKey().endsWith("gc.alloc.rate.norm")) return e.getValue().getScore();
        throw new IllegalStateException("no gc.alloc.rate.norm for " + r.getParams().getBenchmark());
    }

    private static boolean check(Gate g, double value, String shown) {
        if (g.limit < 0) {
            System.out.printf("      %-17s %s (reported)%n", g.benchmark, shown);
            return true;
        }
        boolean pass = value <= g.limit;
        String limit = g.limit == Math.rint(g.limit) ? String.format("%.0f", g.limit) : String.valueOf(g.limit);
        if (g.unit == Unit.TIMES_BASELINE) limit += " x";
        System.out.printf("%s  %-17s %s (gate %s)%n", pass ? "ok  " : "FAIL", g.benchmark, shown, limit);
        return pass;
    }
}
