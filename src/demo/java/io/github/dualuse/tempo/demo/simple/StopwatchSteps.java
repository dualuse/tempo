package io.github.dualuse.tempo.demo.simple;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A nightly import job that used to time itself with a stopwatch and log one line when it finished:
 * fetch the file, parse it, validate the rows, verify a checksum, store the rows, archive the file.
 * There is no request here, just a job with consecutive steps, and Tempo takes the stopwatch's place.
 *
 * <p>Each step is a phase of the job's span, a section of its timeline: {@code phase(name)} names it
 * before it runs, {@code done(name)} names the time since the last boundary after it ran. A label
 * ({@code phase(name).label("Text")}) picks a step for the {@code Runtime} and {@code Server-Timing}
 * headers. Spans opened during a step stay children of the job. A checksum library that times itself
 * hands over its {@code System.nanoTime()} readings, and a span is timed by hand from them. The archive
 * step is an unnamed span ({@code Tempo.span()}), labelled only by its phases. {@code printOnClose}
 * writes the old log line, {@code forEachTop} reads the picked steps back.
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=StopwatchSteps                    the log line, a table of the steps, the headers
 * ./gradlew -q demo -Pdemo=StopwatchSteps | tools/tempo      the job's bar with its phases drawn inside
 * ./gradlew -q demo -Pdemo=StopwatchSteps | tools/tempo --tree    the phases as :name under their span
 * </pre>
 *
 * Look for: the log line {@code import: <total>s = ...s (fetch) + ...s (Parse Rows) + ...s (store)};
 * {@code validate} on the job's bar but not in {@code Runtime} or {@code Server-Timing}, because it has
 * no label; {@code download}, {@code unpack} and the {@code write} spans as children of the job, under
 * its phases; {@code checksum} with its phases {@code read} and {@code hash}, timed from the library's
 * readings; an unnamed bar with the phases {@code compress} and {@code copy}; and {@code rows=1200} on
 * {@code store}. Or open the printed {@code Tempo-Link} in a browser.
 */
public final class StopwatchSteps {
    private StopwatchSteps() {
    }

    static final Tempo.Name IMPORT = Tempo.name("import"), FETCH = Tempo.name("fetch"),
            DOWNLOAD = Tempo.name("download"), UNPACK = Tempo.name("unpack"), PARSE = Tempo.name("parse"),
            VALIDATE = Tempo.name("validate"), CHECKSUM = Tempo.name("checksum"), READ = Tempo.name("read"),
            HASH = Tempo.name("hash"), STORE = Tempo.name("store"), WRITE = Tempo.name("write"),
            COMPRESS = Tempo.name("compress"), COPY = Tempo.name("copy"), UNNAMED = Tempo.name(""),
            ROWS = Tempo.name("rows"), BYTES = Tempo.name("bytes");

    public static void main(String[] args) throws Exception {
        List<String> log = new ArrayList<>(); // stands in for the job's logger
        Checksum checksum = new Checksum();
        Tempo.Trace t = Tempo.trace(IMPORT, new Tempo.Options().strict(true));
        // The old stopwatch logged one line at the end of the job; this does the same when the trace closes.
        t.printOnClose(line -> {
            log.add(line);
            Demo.say(line);
        }, "import");

        try (t) {
            // Fetch runs from here to done(FETCH); its label picks it for the headers. The trace is the
            // job's span, so its phases are the job's steps.
            t.phase(FETCH).label("fetch");
            download();                                   // a child of the job, during the fetch phase
            Tempo.run(UNPACK, () -> Demo.cpu(2_000));
            t.done(FETCH);

            t.phase(PARSE).label("Parse Rows");
            Demo.cpu(4_000);
            t.done();

            // Named after it ran, without a label: on the job's bar, not in the Runtime and Server-Timing headers.
            Demo.cpu(1_000);
            t.done(VALIDATE);

            // The checksum library times itself and reports System.nanoTime() readings. A span timed by hand
            // takes them as they are: it starts at the first, its phase read ends at the second, and its
            // phase hash ends with the span at the third.
            checksum.verify();
            Tempo.span(CHECKSUM).phase(READ).start(checksum.started).lap(checksum.read)
                    .phase(HASH).stop(checksum.hashed).close();

            Tempo.Phase store = t.phase(STORE).label("store");
            long stored = 0;
            for (int batch = 0; batch < 3; batch++) {
                try (Tempo.Span w = Tempo.span(WRITE)) {   // children of the job, during the store phase
                    Demo.io(4);
                    w.set(ROWS, 400);
                    stored += 400;
                }
            }
            store.set(ROWS, stored);
            t.done();

            // A span without a name: its phases are its label in the viewers ("compress · copy").
            try (Tempo.Span archive = Tempo.span()) {
                archive.phase(COMPRESS);
                Demo.cpu(1_500);
                archive.phase(COPY);
                Demo.io(3);
            }
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
        Demo.say(String.format(Locale.ROOT, "checksum, timed by hand from the library's readings: read %.3f ms, hash %.3f ms",
                (checksum.read - checksum.started) / 1e6, (checksum.hashed - checksum.read) / 1e6));

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

        int check = Demo.id(t, CHECKSUM);
        Demo.check(Demo.count(t, v -> v.id() == check && !v.misuse()
                        && v.durationNanos() == checksum.hashed - checksum.started) == 1,
                "checksum lasts exactly from the library's first reading to its last, with nothing clamped");
        Demo.check(Demo.count(t, v -> v.parent() == check && v.phase() && v.name() == READ
                        && v.durationNanos() == checksum.read - checksum.started) == 1
                        && Demo.count(t, v -> v.parent() == check && v.phase() && v.name() == HASH
                        && v.durationNanos() == checksum.hashed - checksum.read) == 1,
                "its phases read and hash split it at the library's middle reading");

        int archive = Demo.id(t, UNNAMED);
        Demo.check(archive > 0 && Demo.count(t, v -> v.parent() == archive && v.phase()) == 2
                        && Demo.count(t, v -> v.parent() == archive && (v.name() == COMPRESS || v.name() == COPY)) == 2,
                "the unnamed span has the empty name and two phases, compress and copy, to label it");
        Demo.check(t.total(COMPRESS).count() == 1 && t.total(HASH).sum() == checksum.hashed - checksum.read,
                "phases count towards the totals of their names, the hand-timed ones too");

        t.release();
    }

    /** Download the file; it opens its span under whatever span its caller has open. */
    static void download() {
        try (Tempo.Span d = Tempo.span(DOWNLOAD)) {
            Demo.io(15);
            d.set(BYTES, 48_213);
        }
    }

    /** Stands in for a library that times itself with {@code System.nanoTime()} and keeps the readings. */
    static final class Checksum {
        long started, read, hashed;

        void verify() {
            started = System.nanoTime();
            Demo.io(2);         // read the file
            read = System.nanoTime();
            Demo.cpu(1_000);    // hash it
            hashed = System.nanoTime();
        }
    }
}
