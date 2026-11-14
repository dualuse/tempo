package io.github.dualuse.tempo.demo.medium;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongConsumer;

/**
 * A small record service whose spans also feed its metrics, the duty a hand-rolled timer class
 * often carries besides timing. Each {@code POST /records} request parses a record, dedupes it with
 * restarts, indexes it on a shard pool and persists it; a background job compacts the store without
 * any trace.
 *
 * <p>It shows six ways to get durations out of Tempo: a global listener on a name ({@code persist}
 * feeds a histogram from every trace), an always-on name ({@code dedupe} tries are counted at
 * {@code COARSE} without rows and reported in the response body), a listener for one trace, a
 * measurement taken elsewhere ({@code t.add} from a client library's callback), {@code Tempo.time}
 * without a trace, and queue wait of pool tasks that have no lane ({@code t.submitted()} and
 * {@code t.waited(..)}), rendered as an extra {@code Server-Timing} entry.
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=MetricsAndListeners                 the story, the headers, the body
 * ./gradlew -q demo -Pdemo=MetricsAndListeners | tools/tempo   the last request's trace as lanes
 * </pre>
 *
 * Look for: the trace has rows only for {@code parse}, {@code index} (flag {@code ^w}) and the two
 * {@code persist} spans, all on {@code main}, nothing on a {@code shard-*} thread. The dedupe tries
 * are one aggregate ({@code dedupe ×4}, {@code x=4.0} in the header) and the {@code dedupe} field of
 * the body. The shard queue waits and the cache calls are not in the trace at all, only in
 * {@code t.aggregate(..)}: the service adds them to {@code Server-Timing} itself. The histograms
 * count every persist of all three requests and the one compaction. Or open the printed
 * {@code Tempo-Link} in a browser.
 */
public final class MetricsAndListeners {
    private MetricsAndListeners() {
    }

    // The service's metrics. They come before the names: PERSIST attaches its sink when it is created.
    private static final Histogram PERSIST_SECONDS = new Histogram(), COMPACT_SECONDS = new Histogram();

    static final Tempo.Name POST = Tempo.name("POST /records"), PARSE = Tempo.name("parse"),
            INDEX = Tempo.name("index"), SHARD = Tempo.name("shard"), CACHE = Tempo.name("cache"),
            BYTES = Tempo.name("bytes"), WINDOW = Tempo.name("window"),
            // Always-on, in every trace: each try is timed into t.aggregate(DEDUPE) at every level.
            DEDUPE = Tempo.name("dedupe").aggregate(),
            // A global listener: each persist span of every trace started from now on is one sample.
            PERSIST = Tempo.name("persist").sink(Tempo.seconds(PERSIST_SECONDS::record));

    static final int RECORDS = 3, SHARDS = 4;

    public static void main(String[] args) throws Exception {
        ExecutorService shards = Demo.pool("shard", 2), background = Demo.pool("compact", 1);
        CacheClient cache = new CacheClient();
        // The histograms live as long as the process; count what this run adds.
        long persisted = PERSIST_SECONDS.count(), compacted = COMPACT_SECONDS.count();
        int persistSpans = 0;
        Handled last = null;
        for (int record = 1; record <= RECORDS; record++) {
            last = handle(record, shards, cache);
            persistSpans += Demo.count(last.trace, PERSIST);
        }

        // A background job has no request and no trace: Tempo.time feeds the metric with two clock reads.
        background.submit(() -> Tempo.time(Tempo.seconds(COMPACT_SECONDS::record), () -> Demo.io(2))).get();
        Demo.shutdown(shards, background);

        Demo.check(PERSIST_SECONDS.count() - persisted == persistSpans,
                "the global listener took one sample per persist span, over all " + RECORDS + " traces");
        Demo.check(COMPACT_SECONDS.count() - compacted == 1, "Tempo.time fed the compact histogram without a trace");

        Tempo.Trace t = last.trace;
        Demo.say(RECORDS + " requests handled; below is the last one, record " + last.record + ".");
        Demo.say("persist seconds (global listener, all requests): " + PERSIST_SECONDS);
        Demo.say("compact seconds (Tempo.time, no trace):          " + COMPACT_SECONDS);
        Demo.say("this trace's listener saw its persists: " + last.persists.size() + " samples");
        Demo.respond(t.toRuntime(3), serverTiming(t), t.toHeader(), t.toLink());
        Demo.body(body(last));
    }

    /** What one request leaves behind: its trace, and what its own listener collected. */
    private static final class Handled {
        final int record;
        final Tempo.Trace trace;
        final List<Long> persists;

        Handled(int record, Tempo.Trace trace, List<Long> persists) {
            this.record = record;
            this.trace = trace;
            this.persists = persists;
        }
    }

    private static Handled handle(int record, ExecutorService shards, CacheClient cache) throws Exception {
        List<Long> persists = new ArrayList<>();
        int tries = 0;
        Tempo.Trace t = Tempo.start(POST, new Tempo.Options().strict(true));
        try (t) {
            // This trace's own listener, added before the first child span as it must be. Listeners run
            // on whichever thread ends the span, so a plain list goes behind Tempo.serialized.
            t.on(PERSIST, Tempo.serialized(persists::add));
            Tempo.Span s = t.root();
            s.run(PARSE, () -> Demo.cpu(200));

            // Dedupe restarts with a wider window until it can decide. Below DETAIL a detail span on an
            // always-on name writes no row: each try is timed into one aggregate record of the lane
            // (dedupe ×N in the header) and into t.aggregate(DEDUPE), and its attribute is ignored. At
            // DETAIL the same code records each try as a row with its window.
            for (int window = 8; ; window *= 2) {
                tries++;
                try (Tempo.Span d = s.detail(DEDUPE)) {
                    d.set(WINDOW, window);
                    if (decided(record, window)) break;
                }
            }

            // Shard tasks get no handoff and so no lane: no rows, no Handoff per task. The token taken at
            // submit and t.waited(..) where the task starts put its queue wait into t.aggregate(SHARD).
            try (Tempo.Span index = s.span(INDEX)) {
                List<Future<?>> tasks = new ArrayList<>();
                for (int shard = 0; shard < SHARDS; shard++) {
                    long token = t.submitted();
                    String key = record + "/" + shard;
                    tasks.add(shards.submit(() -> {
                        t.waited(SHARD, token);
                        // The client times its own calls; t.add takes such a measurement from any thread.
                        cache.get(key, nanos -> t.add(CACHE, nanos));
                        Demo.cpu(300);
                    }));
                }
                index.waiting();
                for (Future<?> task : tasks) task.get();
            }

            try (Tempo.Span p = s.span(PERSIST)) {      // the record
                p.set(BYTES, 400 + 100 * record);
                Demo.io(1);
            }
            try (Tempo.Span p = s.span(PERSIST)) {      // its index entry
                p.set(BYTES, 64);
                Demo.io(1);
            }
        }

        Tempo.Aggregate dedupe = t.aggregate(DEDUPE), shard = t.aggregate(SHARD);
        Demo.check(t.level() == Tempo.COARSE, "the trace records at COARSE");
        Demo.check(Demo.count(t, v -> v.name() == DEDUPE && !v.aggregate()) == 0, "dedupe tries write no rows at COARSE");
        Demo.check(dedupe.count() == tries && tries == record + 1, "t.aggregate(dedupe) counts every try: " + tries);
        Demo.check(Demo.count(t, v -> v.name() == DEDUPE && v.aggregate() && v.count() == dedupe.count()) == 1,
                "the tries show as one aggregate record in the trace");
        Demo.check(Demo.count(t, PERSIST) == 2 && t.aggregate(PERSIST).count() == 2, "two persist spans");
        Demo.check(persists.size() == 2, "the trace's own listener saw each persist span of this trace");
        Demo.check(shard.waitCount() == SHARDS && shard.count() == 0,
                "t.aggregate(shard) has one queue wait per task, and no span");
        Demo.check(t.aggregate(CACHE).count() == SHARDS, "t.add took each cache call the client measured");
        String caller = Thread.currentThread().getName();
        Demo.check(Demo.count(t, v -> !caller.equals(v.thread())) == 0,
                "every row is on the request's thread: the shard tasks have no lane");
        Demo.check(Demo.count(t, v -> v.name() == INDEX && v.waiting()) == 1, "index is a blocking wait");
        String header = t.toHeader();
        Demo.check(!header.contains("shard") && !header.contains("cache"),
                "the lane-less aggregates are not in Tempo-Timing; only t.aggregate(..) reads them");
        return new Handled(record, t, persists);
    }

    /** Stand-in for the near-duplicate search: a window of 8 << record entries is wide enough to decide. */
    private static boolean decided(int record, int window) {
        Demo.cpu(150);
        return window >= 8 << record;
    }

    /** The body reports the dedupe aggregate: how many tries, and their total and longest time. */
    private static String body(Handled h) {
        Tempo.Aggregate d = h.trace.aggregate(DEDUPE);
        return String.format(Locale.ROOT, "{\"record\":%d,\"dedupe\":{\"tries\":%d,\"ms\":%.3f,\"maxMs\":%.3f}}",
                h.record, d.count(), ms(d.sumNanos()), ms(d.maxNanos()));
    }

    /**
     * toServerTiming() prints the legacy steps and the total. The shards' queue wait and the cache calls
     * are this service's own entries, rendered from their aggregates. Their dur is a sum over tasks that
     * ran at the same time on two threads, so it can come close to the total or exceed it.
     */
    private static String serverTiming(Tempo.Trace t) {
        Tempo.Aggregate w = t.aggregate(SHARD), c = t.aggregate(CACHE);
        return t.toServerTiming()
                + String.format(Locale.ROOT, ", shard-wait;dur=%.3f;desc=\"%d tasks summed, max %.3f ms\"",
                        ms(w.waitSumNanos()), w.waitCount(), ms(w.waitMaxNanos()))
                + String.format(Locale.ROOT, ", cache;dur=%.3f;desc=\"%d calls summed\"", ms(c.sumNanos()), c.count());
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    /** Stands in for a client library that times its own calls and reports them through a callback. */
    private static final class CacheClient {
        byte[] get(String key, LongConsumer callNanos) {
            long start = System.nanoTime();
            Demo.io(1);
            callNanos.accept(System.nanoTime() - start);
            return new byte[key.length()];
        }
    }

    /** A tiny thread-safe latency histogram, what a metrics library would provide: counts per bucket. */
    private static final class Histogram {
        private static final double[] UPPER = {0.0005, 0.001, 0.002, 0.005}; // seconds; one more bucket above
        private final AtomicLongArray counts = new AtomicLongArray(UPPER.length + 1);

        void record(double seconds) {
            int i = 0;
            while (i < UPPER.length && seconds > UPPER[i]) i++;
            counts.incrementAndGet(i);
        }

        long count() {
            long n = 0;
            for (int i = 0; i < counts.length(); i++) n += counts.get(i);
            return n;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < UPPER.length; i++) {
                b.append(String.format(Locale.ROOT, "<=%sms:%d ", UPPER[i] * 1000, counts.get(i)));
            }
            return b.append(">5.0ms:").append(counts.get(UPPER.length)).toString();
        }
    }
}
