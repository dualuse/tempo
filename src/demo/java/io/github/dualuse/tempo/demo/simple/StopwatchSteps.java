package io.github.dualuse.tempo.demo.simple;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A nightly import job that used to time itself with a stopwatch and log one line when it finished:
 * fetch the file, parse it, validate the rows, store them. There is no request here, just a job with
 * consecutive steps, and Tempo takes the stopwatch's place.
 *
 * <p>It shows three ways to mark a step on one span: {@code phase(name)} names it before it runs,
 * {@code done(name)} or {@code done(name, "Label")} names it after it ran (a lap), and
 * {@code lap(name, body)} wraps a body. {@code legacy()} or a label picks a step for the {@code Runtime}
 * and {@code Server-Timing} headers. Spans opened during a step nest under it. {@code printOnClose}
 * writes the old log line, {@code forEachTop} reads the picked steps back.
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=StopwatchSteps                    the log line, a table of the steps, the headers
 * ./gradlew -q demo -Pdemo=StopwatchSteps | tools/tempo --tree    the steps as a tree
 * </pre>
 *
 * Look for: the log line {@code import: <total>s = ...s (fetch) + ...s (Parse Rows) + ...s (store)};
 * {@code validate} in the tree but not in {@code Runtime} or {@code Server-Timing}, because it was not
 * picked; {@code download} and {@code unpack} under {@code fetch}, the {@code write} spans under
 * {@code store}, and {@code rows=1200} on {@code store}. Or open the printed {@code Tempo-Link} in a
 * browser.
 */
public final class StopwatchSteps {
    private StopwatchSteps() {
    }

    static final Tempo.Name IMPORT = Tempo.name("import"), FETCH = Tempo.name("fetch"),
            DOWNLOAD = Tempo.name("download"), UNPACK = Tempo.name("unpack"), PARSE = Tempo.name("parse"),
            VALIDATE = Tempo.name("validate"), STORE = Tempo.name("store"), WRITE = Tempo.name("write"),
            ROWS = Tempo.name("rows"), BYTES = Tempo.name("bytes");

    public static void main(String[] args) throws Exception {
        List<String> log = new ArrayList<>(); // stands in for the job's logger
        Tempo.Trace t = Tempo.trace(IMPORT, new Tempo.Options().strict(true));
        // The old stopwatch logged one line at the end of the job; this does the same when the trace closes.
        t.printOnClose(line -> {
            log.add(line);
            Demo.say(line);
        }, "import");

        try (t) {
            Tempo.Span job = t;

            // Label first: fetch runs from here to done(FETCH), and legacy() picks it for the headers now.
            job.phase(FETCH).legacy();
            try (Tempo.Span d = job.span(DOWNLOAD)) { // opened during the phase, so it nests under fetch
                Demo.io(15);
                d.set(BYTES, 48_213);
            }
            job.run(UNPACK, () -> Demo.cpu(2_000));
            job.done(FETCH);

            // Label after: the work runs first, then done() names what ran since the last boundary; the
            // label form also picks it for the headers.
            Demo.cpu(4_000);
            job.done(PARSE, "Parse Rows");

            // A step that is recorded but not picked: it is in the tree, not in the legacy headers.
            Demo.cpu(1_000);
            job.done(VALIDATE);

            // lap() wraps a body; legacy() and phaseSet() then apply to that last lap.
            long stored = job.lap(STORE, () -> {
                long rows = 0;
                for (int batch = 0; batch < 3; batch++) {
                    try (Tempo.Span w = job.span(WRITE)) { // shown under the store lap
                        Demo.io(4);
                        w.set(ROWS, 400);
                        rows += 400;
                    }
                }
                return rows;
            });
            job.legacy().phaseSet(ROWS, stored);
        } // the root closes here, and the log line is written

        // forEachTop walks the picked steps: the same numbers the Runtime header prints.
        List<String> steps = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        long[] lastStart = {-1};
        boolean[] ordered = {true};
        Demo.say(String.format(Locale.ROOT, "%-8s %-12s %9s %9s", "step", "label", "start ms", "ms"));
        t.forEachTop((name, label, startNanos, durationNanos) -> {
            Demo.say(String.format(Locale.ROOT, "%-8s %-12s %9.3f %9.3f",
                    name, label == null ? "-" : label, startNanos / 1e6, durationNanos / 1e6));
            steps.add(name.toString());
            labels.add(label);
            ordered[0] &= startNanos > lastStart[0];
            lastStart[0] = startNanos;
        });

        Demo.respond(t);

        String runtime = t.toRuntime(3), serverTiming = t.toServerTiming();
        Demo.check(steps.equals(List.of("fetch", "parse", "store")),
                "forEachTop visits the picked steps fetch, parse, store, and skips validate: " + steps);
        Demo.check(ordered[0], "forEachTop visits the steps in start order");
        Demo.check(labels.get(0) == null && "Parse Rows".equals(labels.get(1)) && labels.get(2) == null,
                "only done(PARSE, \"Parse Rows\") carries a label; legacy() steps have none: " + labels);
        Demo.check(runtime.contains("s (fetch)") && runtime.contains("s (Parse Rows)") && runtime.contains("s (store)")
                        && !runtime.contains("validate"),
                "Runtime prints each picked step under its label, or its name without one: " + runtime);
        Demo.check(serverTiming.startsWith("fetch;dur=") && serverTiming.contains(", parse;dur=")
                        && serverTiming.contains(", store;dur=") && serverTiming.contains(", total;dur=")
                        && !serverTiming.contains("validate"),
                "Server-Timing lists the same steps by name, then the total: " + serverTiming);
        Demo.check(log.size() == 1 && log.get(0).startsWith("import: ") && log.get(0).contains("s (Parse Rows)"),
                "printOnClose logged one line, prefixed import: " + log);

        int fetch = Demo.id(t, FETCH), store = Demo.id(t, STORE);
        Demo.check(Demo.count(t, v -> (v.name() == DOWNLOAD || v.name() == UNPACK) && v.parent() == fetch) == 2,
                "download and unpack, opened during the fetch phase, are its children");
        Demo.check(Demo.count(t, v -> v.name() == WRITE && v.parent() == store) == 3,
                "the three write spans, opened during the store lap, are its children");
        Demo.check(Demo.count(t, v -> v.name() == VALIDATE && v.phase() && !v.legacy()) == 1,
                "validate is recorded as a lap, just not picked for the headers");
        Demo.check(Demo.count(t, v -> v.name() == STORE && v.attributes() == 1
                        && v.attributeKey(0) == ROWS && v.longAttribute(0) == 1_200) == 1,
                "phaseSet put rows=1200 on the store lap");

        t.release();
    }
}
