package io.github.dualuse.tempo.demo.complex;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code PUT /upload} of a video-like stream of 120 frames, processed by a staged pipeline: the request
 * thread reads the body chunk by chunk and feeds two decode threads, which feed one describe thread, which
 * feeds one store thread that writes in batches of 16. Bounded queues connect the stages; when one is
 * full, its producer blocks, and that blocked put is what the trace calls backpressure.
 *
 * <p>It shows long-running stage workers joining the request's trace (one handoff per worker, begun on
 * the worker, so each worker records one lane), per-frame detail spans that run past the trace's budget
 * and fold into one aggregate per stage lane, end of stream with poison pills, a summary for the response
 * body built from {@code t.total(name)}, a size-limited header next to the full trace as JSON in the
 * body, and backpressure as {@code waiting()} spans that show where the pipeline is throttled. The header's
 * aggregates carry count, sum, min and max. It builds on {@code WorkerPool} and {@code MetricsAndListeners}.
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=IngestPipeline                      the story, the response and its body
 * ./gradlew -q demo -Pdemo=IngestPipeline | tools/tempo        lanes: main, decode-1/2, describe-1, store-1
 * ./gradlew -q demo -Pdemo=IngestPipeline | tools/tempo --agg  count, sum and max per stage
 * </pre>
 *
 * Look for: {@code describe-1} busy from start to end (the slowest stage); {@code backpressure} with
 * flag {@code w} on {@code main} and on the decode threads, because the queues in front of
 * {@code describe} stay full; {@code decode}, {@code describe} and {@code store} as a few rows, then one
 * aggregate ({@code ×N}) per lane once the budget is spent; 8 {@code flush}es, the last one at end of
 * stream; {@code drain} on {@code main} waiting until every stage has finished, so no lane is still live
 * ({@code l=}) when the response is rendered. Or open the printed Tempo-Link.
 */
@SuppressWarnings("try") // many spans here only time their block, so the block never names the span
public final class IngestPipeline {
    private IngestPipeline() {
    }

    static final Tempo.Name UPLOAD = Tempo.name("PUT /upload"), RECEIVE = Tempo.name("receive"),
            READ = Tempo.name("read"), DRAIN = Tempo.name("drain"),
            // one task per stage worker: the span its lane hangs from
            DECODER = Tempo.name("decoder"), DESCRIBER = Tempo.name("describer"), WRITER = Tempo.name("writer"),
            FRAMES = Tempo.name("frames"), BYTES = Tempo.name("bytes");

    // Per-frame work and the blocked puts, as steps: t.total(name) counts them whatever the trace draws, so
    // the summary in the body stays complete when a request records spans only and these write no rows.
    static final Tempo.Name DECODE = Tempo.name("decode"), DESCRIBE = Tempo.name("describe"),
            STORE = Tempo.name("store"), FLUSH = Tempo.name("flush"),
            BACKPRESSURE = Tempo.name("backpressure");

    static final int FRAME_COUNT = 120, QUEUE = 8, DECODERS = 2, BATCH = 16;
    // describe is the slowest stage by far (4 ms a frame against 1 ms a chunk read), so queues fill up.
    static final int READ_MS = 1, DECODE_US = 1_500, DESCRIBE_US = 4_000, STORE_US = 100, FLUSH_MS = 3;

    /**
     * Detail spans recorded one by one; the rest fold. 120 frames make over 480 of them, so the budget
     * keeps the trace, and its JSON, bounded however long the stream is. It still records more than a
     * header of {@link #HEADER_BYTES} can hold, so the header folds further.
     */
    static final int BUDGET = 320, HEADER_BYTES = 3_000;

    /** One frame on its way through the pipeline. */
    static final class Frame {
        final int bytes;

        Frame(int bytes) {
            this.bytes = bytes;
        }
    }

    /** End of stream: a stage that takes it finishes its work, passes it on and returns. */
    static final Frame END = new Frame(0);

    /** A stage's loop, run on its worker inside the stage's span. */
    interface Stage {
        void run(Tempo.Span stage) throws InterruptedException;
    }

    public static void main(String[] args) throws Exception {
        // Dedicated stage workers, started with the service. Each upload runs one loop on every worker, so a
        // second upload's loops would wait in the pool queues (its handoffs' queue wait would show it).
        ThreadPoolExecutor decoders = Demo.pool("decode", DECODERS), describers = Demo.pool("describe", 1),
                writers = Demo.pool("store", 1);
        try {
            upload(decoders, describers, writers);
        } finally {
            Demo.shutdown(decoders, describers, writers);
        }
    }

    static void upload(ExecutorService decoders, ExecutorService describers, ExecutorService writers) throws Exception {
        Tempo.Trace t = Tempo.trace(UPLOAD, new Tempo.Options().budget(BUDGET)
                .headerBytes(HEADER_BYTES).strict(true)).records(Tempo.STEP);
        try (t) {
            Tempo.Span root = t.set(FRAMES, FRAME_COUNT);
            BlockingQueue<Frame> toDecode = new ArrayBlockingQueue<>(QUEUE), toDescribe = new ArrayBlockingQueue<>(QUEUE),
                    toStore = new ArrayBlockingQueue<>(QUEUE);

            // Each stage worker joins the trace for this upload: one task per worker, submitted here.
            List<Future<?>> stages = new ArrayList<>();
            for (int i = 0; i < DECODERS; i++)
                stages.add(start(decoders, DECODER, s -> decode(t, s, toDecode, toDescribe)));
            stages.add(start(describers, DESCRIBER, s -> describe(t, s, toDescribe, toStore)));
            stages.add(start(writers, WRITER, s -> store(t, s, toStore)));

            try (Tempo.Span r = root.span(RECEIVE)) {
                long bytes = 0;
                for (int i = 0; i < FRAME_COUNT; i++) {
                    Frame f = new Frame(40_000 + i % 7 * 1_000);
                    try (Tempo.Span c = r.step(READ)) {
                        Demo.io(READ_MS); // the next chunk of the request body
                        c.set(BYTES, f.bytes);
                    }
                    bytes += f.bytes;
                    put(t, r, toDecode, f);
                }
                for (int i = 0; i < DECODERS; i++) put(t, r, toDecode, END); // one pill per decoder
                r.set(BYTES, bytes);
            }

            // The response waits for the last stage: no worker still records into this trace when it renders.
            try (Tempo.Span d = root.span(DRAIN).waiting()) {
                for (Future<?> f : stages) f.get();
            }
        }

        // The numbers a client logs, from the aggregates: every frame counts, recorded as a row or folded.
        StringBuilder body = new StringBuilder("{\"frames\":").append(FRAME_COUNT).append(",\"summary\":{");
        for (Tempo.Name n : new Tempo.Name[] {DECODE, DESCRIBE, STORE}) {
            Tempo.Total a = t.total(n);
            body.append('"').append(n).append("\":").append(stats(a)).append(',');
            Demo.say(String.format(Locale.ROOT, "%-9s %d frames, %s ms in all, slowest %s ms", n, a.count(), ms(a.sum()),
                    ms(a.max())));
        }
        Tempo.Total flushes = t.total(FLUSH), pressure = t.total(BACKPRESSURE);
        body.append("\"flush\":").append(stats(flushes)).append(",\"backpressure\":").append(stats(pressure)).append("},");
        Demo.say(String.format(Locale.ROOT, "flush     %d batches, %s ms; backpressure: %d blocked puts, %s ms",
                flushes.count(), ms(flushes.sum()), pressure.count(), ms(pressure.sum())));

        // A response holds a snapshot, never the trace. The header keeps to its byte limit; the body has none.
        Tempo.Snapshot snap = t.snapshot();
        body.append("\"tempo\":");
        snap.writeJson(body);
        body.append('}');
        String header = snap.toHeader();

        int headerBytes = Demo.bytes(header);
        int headerSpans = Integer.parseInt(Demo.field(header, "e"), 36);
        int jsonSpans = Demo.countOf(body.toString(), "{\"id\":"); // one per span
        Demo.say("describe is the slowest stage: the queues in front of it stay full, and every full put waits");
        Demo.say("  (backpressure, flag w) on the decode threads and on main, the request thread.");
        Demo.say(String.format(Locale.ROOT, "Tempo-Timing: %d spans in %d bytes, folded per stage; the body: %d spans in %d bytes of JSON.",
                headerSpans, headerBytes, jsonSpans, body.length()));
        Demo.respond(snap.toRuntime(3), snap.toServerTiming(), header, snap.toLink());
        Demo.body(body);

        check(t, body.toString(), header, headerBytes, headerSpans, jsonSpans);
        t.release(); // the snapshot stays readable, for a body serialized later
    }

    /**
     * Submits a stage worker's loop for this upload as a task under the calling thread's innermost span.
     * The task begins on the worker when the loop starts there (the queue wait ends, the worker's lane
     * joins the trace) and flags the stage failed if the loop throws; inside, {@code Tempo.current()} is
     * the stage's span.
     */
    static Future<?> start(ExecutorService pool, Tempo.Name name, Stage stage) {
        return pool.submit(Tempo.callable(name, () -> {
            stage.run(Tempo.current());
            return null;
        }));
    }

    /** Hands a frame to the next stage; a put that has to wait for room is backpressure. */
    static void put(Tempo.Trace t, Tempo.Span s, BlockingQueue<Frame> next, Frame f) throws InterruptedException {
        if (next.offer(f)) return;
        try (Tempo.Span b = s.step(BACKPRESSURE).waiting()) {
            next.put(f);
        }
    }

    static void decode(Tempo.Trace t, Tempo.Span stage, BlockingQueue<Frame> in, BlockingQueue<Frame> out)
            throws InterruptedException {
        int n = 0;
        for (Frame f; (f = in.take()) != END; n++) {
            try (Tempo.Span d = stage.step(DECODE)) {
                Demo.cpu(DECODE_US);
            }
            put(t, stage, out, f);
        }
        put(t, stage, out, END);
        stage.set(FRAMES, n);
    }

    static void describe(Tempo.Trace t, Tempo.Span stage, BlockingQueue<Frame> in, BlockingQueue<Frame> out)
            throws InterruptedException {
        int n = 0;
        for (int ends = 0; ends < DECODERS; ) { // done once every decoder has passed its pill on
            Frame f = in.take();
            if (f == END) {
                ends++;
                continue;
            }
            try (Tempo.Span d = stage.step(DESCRIBE)) {
                Demo.cpu(DESCRIBE_US);
            }
            put(t, stage, out, f);
            n++;
        }
        put(t, stage, out, END);
        stage.set(FRAMES, n);
    }

    static void store(Tempo.Trace t, Tempo.Span stage, BlockingQueue<Frame> in) throws InterruptedException {
        List<Frame> batch = new ArrayList<>(BATCH);
        int n = 0;
        for (Frame f; (f = in.take()) != END; n++) {
            try (Tempo.Span s = stage.step(STORE)) {
                Demo.cpu(STORE_US); // serialize the frame into the batch
            }
            batch.add(f);
            if (batch.size() == BATCH) flush(stage, batch);
        }
        if (!batch.isEmpty()) flush(stage, batch); // end of stream: the last, partial batch
        stage.set(FRAMES, n);
    }

    static void flush(Tempo.Span stage, List<Frame> batch) {
        try (Tempo.Span w = stage.step(FLUSH)) {
            Demo.io(FLUSH_MS); // one write for the whole batch
            w.set(FRAMES, batch.size());
        }
        batch.clear();
    }

    // ------------------------------------------------------------------ the claims

    static void check(Tempo.Trace t, String body, String header, int headerBytes, int headerSpans, int jsonSpans) {
        for (Tempo.Name n : new Tempo.Name[] {DECODE, DESCRIBE, STORE}) {
            Tempo.Total a = t.total(n);
            Demo.check(a.count() == FRAME_COUNT, n + ": total(" + n + ") counts every frame");
            Demo.check(Demo.occurrences(t, v -> v.name() == n) == FRAME_COUNT, n + ": rows plus folded counts are all frames");
            Demo.check(Demo.count(t, v -> v.name() == n && v.aggregate()) >= 1, n + ": past the budget, frames fold into an aggregate");
        }
        Demo.check(t.total(FLUSH).count() == (FRAME_COUNT + BATCH - 1) / BATCH,
                "the store writes in batches of " + BATCH + ", the last one at end of stream");

        Demo.check(Demo.count(t, v -> v.name() == DECODER && v.handoff() && v.thread().startsWith("decode-")) == DECODERS
                && Demo.count(t, v -> v.name() == DECODER && "decode-1".equals(v.thread())) == 1, "one lane per decode worker");
        Demo.check(Demo.count(t, v -> v.name() == DESCRIBER && v.handoff() && "describe-1".equals(v.thread())) == 1,
                "describe runs in a lane of its own");
        Demo.check(Demo.count(t, v -> v.name() == WRITER && v.handoff() && "store-1".equals(v.thread())) == 1,
                "store runs in a lane of its own");
        Demo.check(sum(t, DECODER, FRAMES) == FRAME_COUNT && sum(t, DESCRIBER, FRAMES) == FRAME_COUNT
                && sum(t, WRITER, FRAMES) == FRAME_COUNT, "the stages' frames= add up to all frames");
        Demo.check(Demo.count(t, v -> v.name() == DECODE && (v.thread() == null || !v.thread().startsWith("decode-"))) == 0
                && Demo.count(t, v -> v.name() == DESCRIBE && !"describe-1".equals(v.thread())) == 0
                && Demo.count(t, v -> (v.name() == STORE || v.name() == FLUSH) && !"store-1".equals(v.thread())) == 0,
                "per-frame spans run on their stage's thread");

        String main = Thread.currentThread().getName();
        Demo.check(Demo.count(t, v -> v.name() == BACKPRESSURE && v.waiting() && main.equals(v.thread())) >= 1,
                "the request thread waited for room in the decode queue (backpressure)");
        Demo.check(Demo.count(t, v -> v.name() == DRAIN && v.waiting()) == 1, "the response waited for the stages (drain)");
        Demo.check(Demo.count(t, v -> v.open() || v.neverStarted() || v.failed()) == 0 && !header.contains(";l="),
                "every stage finished before the response was rendered: nothing open, no live lane");

        Demo.check(headerBytes <= HEADER_BYTES, "the header keeps to its byte limit");
        Demo.check(header.matches(".*;S=.*\\+.*"), "the header carries aggregates");
        Demo.check(jsonSpans > headerSpans && jsonSpans == Demo.count(t, v -> true),
                "the JSON body holds every recorded span, more than the header");
        Matcher folded = Pattern.compile("\"folded\":(\\d+)").matcher(body);
        Demo.check(folded.find() && Long.parseLong(folded.group(1)) > 0, "the JSON counts the spans folded for want of budget");
    }

    // ------------------------------------------------------------------ small helpers

    static String stats(Tempo.Total a) {
        return String.format(Locale.ROOT, "{\"count\":%d,\"ms\":%s,\"maxMs\":%s}", a.count(), ms(a.sum()), ms(a.max()));
    }

    static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e6);
    }

    /** The sum of attribute {@code key} over the spans named {@code name}. */
    static long sum(Tempo.Trace t, Tempo.Name name, Tempo.Name key) {
        long[] n = {0};
        t.forEachSpan(v -> {
            if (v.name() == name) n[0] += Math.max(0, Demo.attribute(v, key));
        });
        return n[0];
    }
}
