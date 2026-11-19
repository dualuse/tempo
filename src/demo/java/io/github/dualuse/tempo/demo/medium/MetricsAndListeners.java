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
 * <p>It shows the ways to get durations out of Tempo: a global listener on a name ({@code Tempo.on}:
 * {@code persist} feeds a histogram from every trace), totals per name ({@code t.total(name)}: the
 * {@code dedupe} steps and the {@code shard} tasks are counted, with the tasks' queue wait, while the
 * trace records spans only and draws neither), a listener for one trace ({@code t.on}), a measurement
 * taken elsewhere ({@code s.add} from a client library's callback), rendered as an extra {@code
 * Server-Timing} entry, and {@code Tempo.run} without a trace for a name with a global listener.
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=MetricsAndListeners                 the story, the headers, the body
 * ./gradlew -q demo -Pdemo=MetricsAndListeners | tools/tempo   the last request's trace as lanes
 * </pre>
 *
 * Look for: the trace has rows only for {@code parse}, {@code index} (flag {@code ^w}) and the two
 * {@code persist} spans, all on {@code main}, nothing on a {@code shard-*} thread. The dedupe tries
 * and the shard tasks are not drawn; their totals are the {@code dedupe} and {@code shards} fields of
 * the body. The cache calls are not in the trace at all, only in {@code t.total(..)}: the service adds
 * them to {@code Server-Timing} itself. The histograms count every persist of all three requests and
 * the one compaction. Or open the printed {@code Tempo-Link} in a browser.
 */
public final class MetricsAndListeners {
    private MetricsAndListeners() {
    }

    // The service's metrics.
    private static final Histogram PERSIST_SECONDS = new Histogram(), COMPACT_SECONDS = new Histogram();

    static final Tempo.Name POST = Tempo.name("POST /records"), PARSE = Tempo.name("parse"),
            INDEX = Tempo.name("index"), SHARD = Tempo.name("shard"), CACHE = Tempo.name("cache"),
            BYTES = Tempo.name("bytes"), WINDOW = Tempo.name("window"),
            // Opened as a step: each try is timed into t.total(DEDUPE) whatever the trace draws.
            DEDUPE = Tempo.name("dedupe"),
            PERSIST = Tempo.name("persist"), COMPACT = Tempo.name("compact");

    static {
        // Global listeners, set once at start-up: each persist span of every trace started from now on is
        // one sample, and so is each compaction, which runs outside any trace.
        Tempo.on(PERSIST, nanos -> PERSIST_SECONDS.record(nanos / 1e9));
        Tempo.on(COMPACT, nanos -> COMPACT_SECONDS.record(nanos / 1e9));
    }

    static final int RECORDS = 3, SHARDS = 4;

    public static void main(String[] args) throws Exception {
        // The shard pool, wrapped once: every submit is a shard task, counted with its queue wait.
        ExecutorService shardPool = Demo.pool("shard", 2), background = Demo.pool("compact", 1);
        ExecutorService shards = Tempo.executor(shardPool, SHARD);
        CacheClient cache = new CacheClient();
        // The histograms live as long as the process; count what this run adds.
        long persisted = PERSIST_SECONDS.count(), compacted = COMPACT_SECONDS.count();
        int persistSpans = 0;
        Handled last = null;
        for (int record = 1; record <= RECORDS; record++) {
            last = handle(record, shards, cache);
            persistSpans += Demo.count(last.trace, PERSIST);
        }

        // A background job has no request and no trace: its span is timed for the global listener only.
        background.submit(() -> Tempo.run(COMPACT, () -> Demo.io(2))).get();
        Demo.shutdown(shardPool, background);

        Demo.check(PERSIST_SECONDS.count() - persisted == persistSpans,
                "the global listener took one sample per persist span, over all " + RECORDS + " traces");
        Demo.check(COMPACT_SECONDS.count() - compacted == 1, "Tempo.run fed the compact histogram without a trace");

        Tempo.Trace t = last.trace;
        Demo.say(RECORDS + " requests handled; below is the last one, record " + last.record + ".");
        Demo.say("persist seconds (global listener, all requests): " + PERSIST_SECONDS);
        Demo.say("compact seconds (Tempo.run, no trace):           " + COMPACT_SECONDS);
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
        Tempo.Trace t = Tempo.trace(POST, new Tempo.Options().strict(true));
        try (t) {
            // This trace's own listener. Listeners run on whichever thread ends the span, so the plain
            // list is locked.
            t.on(PERSIST, nanos -> {
                synchronized (persists) {
                    persists.add(nanos);
                }
            });
            Tempo.run(PARSE, () -> Demo.cpu(200));

            // Dedupe restarts with a wider window until it can decide. While the trace records spans
            // only, a step writes no row: each try is timed into t.total(DEDUPE), and its attribute is
            // ignored. Recording steps, the same code records each try as a row with its window.
            for (int window = 8; ; window *= 2) {
                tries++;
                try (Tempo.Span d = Tempo.step(DEDUPE)) {
                    d.set(WINDOW, window);
                    if (decided(record, window)) break;
                }
            }

            // Shard tasks, like steps, are counted whatever the trace draws: t.total(SHARD) has their run
            // time and their queue wait. While the trace records spans only, they write no rows.
            try (Tempo.Span index = Tempo.span(INDEX).waiting()) {
                List<Future<?>> tasks = new ArrayList<>();
                for (int shard = 0; shard < SHARDS; shard++) {
                    String key = record + "/" + shard;
                    tasks.add(shards.submit(() -> {
                        // The client times its own calls and reports them through a callback, here on the
                        // shard thread; index.add takes such a measurement on any thread.
                        cache.get(key, nanos -> index.add(CACHE, nanos));
                        Demo.cpu(300);
                    }));
                }
                for (Future<?> task : tasks) task.get();
            }

            try (Tempo.Span p = Tempo.span(PERSIST)) {      // the record
                p.set(BYTES, 400 + 100 * record);
                Demo.io(1);
            }
            try (Tempo.Span p = Tempo.span(PERSIST)) {      // its index entry
                p.set(BYTES, 64);
                Demo.io(1);
            }
        }

        Tempo.Total dedupe = t.total(DEDUPE);
        Demo.check(t.records() == Tempo.SPAN, "the trace records spans only");
        Demo.check(Demo.count(t, v -> v.name() == DEDUPE) == 0, "dedupe tries are not drawn");
        Demo.check(dedupe.count() == tries && tries == record + 1, "t.total(dedupe) counts every try: " + tries);
        Demo.check(Demo.count(t, PERSIST) == 2 && t.total(PERSIST).count() == 2, "two persist spans");
        Demo.check(persists.size() == 2, "the trace's own listener saw each persist span of this trace");
        Tempo.Total shard = t.total(SHARD);
        Demo.check(shard.count() == SHARDS && shard.sum() > 0 && shard.waitMax() <= shard.waitSum(),
                "t.total(shard) counts every shard task with its run time and queue wait");
        Demo.check(Demo.count(t, SHARD) == 0, "the shard tasks are not drawn: the trace records spans only");
        Demo.check(t.total(CACHE).count() == SHARDS, "index.add took each cache call the client measured");
        String caller = Thread.currentThread().getName();
        Demo.check(Demo.count(t, v -> !caller.equals(v.thread())) == 0,
                "every row is on the request's thread: the shard tasks write none");
        Demo.check(Demo.count(t, v -> v.name() == INDEX && v.waiting()) == 1, "index is a blocking wait");
        String header = t.toHeader();
        Demo.check(!header.contains("shard") && !header.contains("cache"),
                "what is only counted is not in Tempo-Timing; t.total(..) reads it");
        return new Handled(record, t, persists);
    }

    /** Stand-in for the near-duplicate search: a window of 8 << record entries is wide enough to decide. */
    private static boolean decided(int record, int window) {
        Demo.cpu(150);
        return window >= 8 << record;
    }

    /**
     * The body reports two totals: the dedupe tries (how many, their total, mean and longest time) and
     * the shard tasks (how many, their mean run time, and their queue wait in all and at most).
     */
    private static String body(Handled h) {
        Tempo.Total d = h.trace.total(DEDUPE), s = h.trace.total(SHARD);
        return String.format(Locale.ROOT, "{\"record\":%d,\"dedupe\":{\"tries\":%d,\"ms\":%.3f,\"meanMs\":%.3f,\"maxMs\":%.3f},"
                        + "\"shards\":{\"tasks\":%d,\"meanMs\":%.3f,\"waitMs\":%.3f,\"maxWaitMs\":%.3f}}",
                h.record, d.count(), ms(d.sum()), ms(d.mean()), ms(d.max()),
                s.count(), ms(s.mean()), ms(s.waitSum()), ms(s.waitMax()));
    }

    /**
     * toServerTiming() prints the labelled phases and the total. The cache calls are this service's own
     * entry, rendered from their total. Its dur is a sum over calls that ran at the same time on two
     * threads, so it can come close to the total or exceed it.
     */
    private static String serverTiming(Tempo.Trace t) {
        Tempo.Total c = t.total(CACHE);
        return t.toServerTiming()
                + String.format(Locale.ROOT, ", cache;dur=%.3f;desc=\"%d calls summed\"", ms(c.sum()), c.count());
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
