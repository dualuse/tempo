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
 * <p>Each step is a phase of the job's span, a section of its timeline: {@code phase(name)} names it
 * before it runs, {@code done(name)} names the time since the last boundary after it ran. A label
 * ({@code phase(name).label("Text")}) picks a step for the {@code Runtime} and {@code Server-Timing}
 * headers. Spans opened during a step stay children of the job. {@code printOnClose} writes the old log
 * line, {@code forEachTop} reads the picked steps back.
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=StopwatchSteps                    the log line, a table of the steps, the headers
 * ./gradlew -q demo -Pdemo=StopwatchSteps | tools/tempo --tree    the steps as a tree
 * </pre>
 *
 * Look for: the log line {@code import: <total>s = ...s (fetch) + ...s (Parse Rows) + ...s (store)};
 * {@code validate} in the tree but not in {@code Runtime} or {@code Server-Timing}, because it has no
 * label; {@code download}, {@code unpack} and the {@code write} spans as children of the job, and
 * {@code rows=1200} on {@code store}. Or open the printed {@code Tempo-Link} in a browser.
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

            // Fetch runs from here to done(FETCH); its label picks it for the headers.
            job.phase(FETCH).label("fetch");
            try (Tempo.Span d = job.span(DOWNLOAD)) { // a child of the job, during the fetch phase
                Demo.io(15);
                d.set(BYTES, 48_213);
            }
            job.run(UNPACK, () -> Demo.cpu(2_000));
            job.done(FETCH);

            job.phase(PARSE).label("Parse Rows");
            Demo.cpu(4_000);
            job.done();

            // Named after it ran, without a label: in the tree, not in the Runtime and Server-Timing headers.
            Demo.cpu(1_000);
            job.done(VALIDATE);

            Tempo.Phase store = job.phase(STORE).label("store");
            long stored = 0;
            for (int batch = 0; batch < 3; batch++) {
                try (Tempo.Span w = job.span(WRITE)) { // children of the job, during the store phase
                    Demo.io(4);
                    w.set(ROWS, 400);
                    stored += 400;
                }
            }
            store.set(ROWS, stored);
            job.done();
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
        Demo.check(labels.equals(List.of("fetch", "Parse Rows", "store")), "each picked step has its label: " + labels);
        Demo.check(runtime.contains("s (fetch)") && runtime.contains("s (Parse Rows)") && runtime.contains("s (store)")
                        && !runtime.contains("validate"),
                "Runtime prints each picked step under its label: " + runtime);
        Demo.check(serverTiming.startsWith("fetch;dur=") && serverTiming.contains(", parse;dur=")
                        && serverTiming.contains(", store;dur=") && serverTiming.contains(", total;dur=")
                        && !serverTiming.contains("validate"),
                "Server-Timing lists the same steps by name, then the total: " + serverTiming);
        Demo.check(log.size() == 1 && log.get(0).startsWith("import: ") && log.get(0).contains("s (Parse Rows)"),
                "printOnClose logged one line, prefixed import: " + log);

        Demo.check(Demo.count(t, v -> (v.name() == DOWNLOAD || v.name() == UNPACK) && v.parent() == 0) == 2,
                "download and unpack, opened during the fetch phase, are children of the job");
        Demo.check(Demo.count(t, v -> v.name() == WRITE && v.parent() == 0) == 3,
                "the three write spans, opened during the store phase, are children of the job");
        Demo.check(Demo.count(t, v -> v.name() == VALIDATE && v.phase() && v.label() == null) == 1,
                "validate is recorded as a phase, just not picked for the headers");
        Demo.check(Demo.count(t, v -> v.name() == STORE && v.phase() && v.attributes() == 1
                        && v.attributeKey(0) == ROWS && v.longAttribute(0) == 1_200) == 1,
                "set put rows=1200 on the store phase");

        t.release();
    }
}
