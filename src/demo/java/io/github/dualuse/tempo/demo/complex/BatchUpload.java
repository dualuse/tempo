package io.github.dualuse.tempo.demo.complex;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A batch upload: {@code POST /images} with 24 items, processed in parallel. The request thread hands
 * each item to an I/O pool, which fetches its bytes; the I/O thread then hands the item's tiles to a
 * compute pool to decode, and the decoded image to the same pool to describe. The compute pool is
 * small and bounded, one item is corrupt, and the request has a deadline.
 *
 * <p>It shows nested fan-out (two hops: {@code main -> io-* -> cpu-*}, the second submitted on the I/O
 * thread through {@code Tempo.executor}), a failure that reaches the item's result while the batch goes
 * on, a refused submission (recorded as rejected by the executor) that falls back to running inline on
 * the I/O thread, and items that had not started by the deadline ({@code Task.cancel()}): an I/O thread
 * that takes one too late drops it, and at the deadline the request thread cancels the rest. Both cancel
 * the same task, and only one of them wins, so the two never both handle an item. Each item is a {@code
 * Tempo.callable} task, which is also the item's {@code Future}. The body carries the per-item results
 * and the whole trace as JSON. It builds on {@code WorkerPool} (tasks, rejection, cancel) and {@code
 * AttributesAndFailures} (failures).
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=BatchUpload                  the story, the response and its body
 * ./gradlew -q demo -Pdemo=BatchUpload | tools/tempo    lanes: main, io-1..4, cpu-1..2 (read from the body)
 * ./gradlew -q demo -Pdemo=BatchUpload | tools/tempo --tree
 * </pre>
 *
 * Look for: on {@code main}, 24 {@code item} tasks, the late ones {@code ^c} (cancelled at or after
 * the deadline, never started); on {@code io-*}, {@code fetch}, {@code decode} and {@code describe},
 * with {@code tile} tasks running on {@code cpu-*} after their queue wait. A {@code tile} or {@code
 * describe} the pool refused shows as {@code ^c} followed by the same span run on the I/O thread itself.
 * Item 3's {@code tile}, {@code decode} and {@code item} are {@code ^f}. Or open the printed {@code
 * Tempo-Link} in a browser.
 */
@SuppressWarnings("try") // blocks that only time their work never name the span; see src/demo/README.md
public final class BatchUpload {
    private BatchUpload() {
    }

    static final Tempo.Name UPLOAD = Tempo.name("POST /images"), ITEM = Tempo.name("item"),
            FETCH = Tempo.name("fetch"), DECODE = Tempo.name("decode"), TILE = Tempo.name("tile"),
            DESCRIBE = Tempo.name("describe"), JOIN = Tempo.name("join"),
            AWAIT = Tempo.name("await"), DRAIN = Tempo.name("drain");
    static final Tempo.Name INDEX = Tempo.name("index"), BYTES = Tempo.name("bytes"), TILES = Tempo.name("tiles"),
            OK = Tempo.name("ok"), FAILED = Tempo.name("failed"), CANCELLED = Tempo.name("cancelled");

    static final int ITEMS = 24, CORRUPT = 3, TILE_BYTES = 64_000;
    static final long FETCH_MS = 10, TILE_MICROS = 3_000, DESCRIBE_MICROS = 3_000, DEADLINE_MS = 40;

    public static void main(String[] args) throws Exception {
        // Fetching blocks, so the I/O pool (4 threads) is wider than the compute pool (2 threads, a queue
        // of 2). With both compute threads busy and 2 tasks queued, AbortPolicy refuses the next submission.
        ThreadPoolExecutor io = Demo.pool("io", 4);
        ThreadPoolExecutor cpu = Demo.pool("cpu", 2, 2, new ThreadPoolExecutor.AbortPolicy());
        io.prestartAllCoreThreads(); // a service's pools are warm when a request arrives
        cpu.prestartAllCoreThreads();
        // The compute pool, wrapped once per kind of work: every submit is a task of that name.
        ExecutorService tiles = Tempo.executor(cpu, TILE), describer = Tempo.executor(cpu, DESCRIBE);

        // STEP: the items and the tiles are tasks, which a trace draws when it records steps.
        Tempo.Trace t = Tempo.trace(UPLOAD, new Tempo.Options().strict(true).cpu(true)).records(Tempo.STEP);
        List<Item> items = new ArrayList<>();
        try (t) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS); // from the request's start
            for (int i = 0; i < ITEMS; i++) {
                final int index = i;
                // The task is made here, under the request, and is the item's Future too.
                Tempo.CallableTask<String> task = Tempo.callable(ITEM, () -> process(index, tiles, describer));
                task.set(INDEX, index);
                items.add(new Item(index, task));
                io.execute(() -> take(task, deadline));
            }

            try (Tempo.Span w = Tempo.span(AWAIT).waiting()) {
                for (Item it : items) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) break;
                    try {
                        it.task.get(left, TimeUnit.NANOSECONDS);
                    } catch (TimeoutException late) {
                        break;
                    } catch (ExecutionException | CancellationException outcome) {
                        // read again below, with every other outcome
                    }
                }
            }

            // Past the deadline, the request thread cancels every item that has not begun. The task decides
            // the race with a worker taking the item right now, and with that worker's own deadline check:
            // exactly one cancel or begin wins, so the two paths never both handle one item. A won cancel
            // means the item never runs, and its future is cancelled; a lost one, that it began, or that
            // its worker cancelled it already. The cancelled items stay in the io queue, where they cost a
            // worker nothing: ThreadPoolExecutor.remove would only free the slots of a bounded queue sooner.
            for (Item it : items) it.task.cancel();

            int ok = 0, failed = 0, cancelled = 0;
            try (Tempo.Span d = Tempo.span(DRAIN).waiting()) {
                for (Item it : items) {
                    try {
                        it.descriptor = it.task.get();
                        ok++;
                    } catch (CancellationException e) {
                        it.error = "not started by the deadline"; // by this thread or by the worker that took it
                        cancelled++;
                    } catch (ExecutionException e) {
                        it.error = e.getCause().getMessage();
                        failed++;
                    }
                }
            }
            t.set(OK, ok).set(FAILED, failed).set(CANCELLED, cancelled);
        }
        Demo.shutdown(io, cpu);

        int rejected = Demo.count(t, v -> v.handoff() && v.cancelled() && (v.name() == TILE || v.name() == DESCRIBE));
        int late = Demo.count(t, v -> v.name() == ITEM && v.cancelled());
        Demo.say("24 items; 4 io threads fetch, 2 cpu threads decode tiles and describe, behind a queue of 2.");
        Demo.say("the pool refused " + rejected + " submissions in all (^c; item 0's 6-tile burst into 2 workers"
                + " + 2 slots guarantees at least one); each ran inline on its io thread.");
        Demo.say("item " + CORRUPT + " is corrupt: its tile, decode and item are failed (^f); the batch went on.");
        Demo.say(late + " items had not started after " + DEADLINE_MS
                + " ms: cancelled (^c), no thread ever ran them.");

        Tempo.Snapshot snap = t.snapshot();
        String body = body(items, snap);
        Demo.say("the body, " + body.length() + " bytes, follows the headers: the item results and every span of the trace.");
        Demo.respond(snap.toRuntime(3), snap.toServerTiming(), snap.toHeader(), snap.toLink());
        Demo.body(body);

        check(t, items, body, cpu, io);
        t.release(); // the snapshot stays readable, for a body serialized later
    }

    /** One item of the batch, as the request thread tracks it. */
    static final class Item {
        final int index;
        final Tempo.CallableTask<String> task; // the item's task and its future
        String descriptor, error;

        Item(int index, Tempo.CallableTask<String> task) {
            this.index = index;
            this.task = task;
        }

        boolean cancelled() {
            return task.isCancelled();
        }
    }

    // ------------------------------------------------------------------ one item, on an io thread

    /**
     * An io thread takes an item. The deadline is enforced here, before the task begins: an item taken too
     * late is cancelled, however late the request thread itself wakes up. Each item keeps an io thread for at
     * least 16 ms (11 ms for the corrupt one), so no thread starts a fourth item before 43 ms: at most 12
     * items start before the 40 ms deadline. If the request thread cancelled the item first, this cancel
     * loses, and call() then skips the body too. The race is the same in an untraced request.
     */
    static void take(Tempo.CallableTask<String> item, long deadline) {
        if (System.nanoTime() - deadline > 0 && item.cancel()) return; // too late: it never runs
        try {
            item.call();
        } catch (Exception outcome) {
            // a failure or a cancel: the item's future holds it, and the request reads it from there
        }
    }

    /** Fetches, decodes and describes one item; runs inside the item's task on an {@code io} thread. */
    static String process(int index, ExecutorService tiles, ExecutorService describer) throws Exception {
        int bytes = sizeOf(index), count = (bytes + TILE_BYTES - 1) / TILE_BYTES;
        try (Tempo.Span f = Tempo.span(FETCH)) {
            Demo.io(FETCH_MS);
            f.set(BYTES, bytes);
        }

        // Every tile is submitted before the first is awaited, and every tile is awaited even after one
        // failed: the item must not end while its tiles still run on the compute pool. The tiles are
        // tasks under decode, the innermost open span when they are submitted.
        Throwable failure = null;
        try (Tempo.Span decode = Tempo.span(DECODE).set(TILES, count)) {
            List<Future<Long>> parts = new ArrayList<>();
            for (int k = 0; k < count; k++) {
                final int tile = k;
                parts.add(compute(tiles, TILE, () -> decodeTile(index, tile)));
            }
            try (Tempo.Span j = Tempo.span(JOIN).waiting()) {
                for (Future<Long> p : parts) {
                    try {
                        p.get();
                    } catch (ExecutionException e) {
                        if (failure == null) failure = e.getCause();
                    }
                }
            }
            if (failure != null) decode.fail(failure);
        }
        if (failure != null) throw new IllegalStateException("decode failed: " + failure.getMessage(), failure);

        Future<Long> descriptor = compute(describer, DESCRIBE, () -> Demo.cpu(DESCRIBE_MICROS));
        try (Tempo.Span j = Tempo.span(JOIN).waiting()) {
            return Long.toHexString(descriptor.get());
        }
    }

    /**
     * Submits {@code work} to the compute pool from this I/O thread, as a task of {@code name} under the
     * innermost open span. When the pool is full it refuses: the executor records the task rejected
     * ({@code ^c}, it never started) and throws, and the work runs here instead, as a plain child span.
     * CallerRunsPolicy would run it here too, but as a task begun inline; with AbortPolicy the trace keeps
     * the refusal, and the item still completes.
     */
    static <T> Future<T> compute(ExecutorService pool, Tempo.Name name, Callable<T> work) {
        try {
            return pool.submit(work);
        } catch (RejectedExecutionException full) {
            FutureTask<T> inline = new FutureTask<>(() -> Tempo.call(name, work::call));
            inline.run(); // a failure stays in the future, as it would on the pool
            return inline;
        }
    }

    static long decodeTile(int index, int tile) {
        if (index == CORRUPT && tile == 0) {
            Demo.cpu(TILE_MICROS / 3);
            throw new IllegalArgumentException("tile " + tile + " of item " + index + " is corrupt");
        }
        return Demo.cpu(TILE_MICROS);
    }

    /** Item 0 is a panorama of six tiles; the others take one or two. */
    static int sizeOf(int index) {
        return index == 0 ? 6 * TILE_BYTES - 24_000 : 40_000 + index * 7_919 % 80_000;
    }

    // ------------------------------------------------------------------ the response body

    /** {"items":[...],"tempo":{...}}: per-item results, then the whole trace, not size-limited like the header. */
    static String body(List<Item> items, Tempo.Snapshot snap) throws IOException {
        StringBuilder b = new StringBuilder("{\"items\":[");
        for (Item it : items) {
            b.append(it.index == 0 ? "" : ",").append("{\"index\":").append(it.index).append(",\"status\":\"")
                    .append(it.descriptor != null ? "ok" : it.cancelled() ? "cancelled" : "failed").append('"');
            if (it.descriptor != null) b.append(",\"descriptor\":\"").append(it.descriptor).append('"');
            else b.append(",\"error\":\"").append(it.error).append('"');
            b.append('}');
        }
        b.append("],\"tempo\":");
        snap.writeJson(b);
        return b.append('}').toString();
    }

    // ------------------------------------------------------------------ what the demo claims

    static void check(Tempo.Trace t, List<Item> items, String body, ThreadPoolExecutor cpu, ThreadPoolExecutor io) {
        // Each span's name, thread and parent by id, to follow a chain of tasks back to the request.
        Map<Integer, Tempo.Name> name = new HashMap<>();
        Map<Integer, String> thread = new HashMap<>();
        Map<Integer, Integer> parent = new HashMap<>();
        Set<Integer> cancelledItems = new HashSet<>();
        Set<Long> indices = new HashSet<>();
        t.forEachSpan(v -> {
            name.put(v.id(), v.name());
            thread.put(v.id(), v.thread());
            parent.put(v.id(), v.parent());
            if (v.name() != ITEM) return;
            if (v.cancelled()) cancelledItems.add(v.id());
            indices.add(Demo.attribute(v, INDEX));
        });
        Demo.check(Demo.count(t, v -> v.misuse() || v.healed()) == 0, "no span was misused or healed (strict mode)");
        Demo.check(Demo.count(t, ITEM) == ITEMS && indices.size() == ITEMS,
                "one item task per item, each with its index, the cancelled ones too");

        // the corrupt item
        Demo.check(Demo.count(t, v -> v.name() == DECODE && v.failed()) == 1, "exactly one decode failed");
        Demo.check(Demo.count(t, v -> v.name() == TILE && v.failed()) == 1, "exactly one tile failed");
        Demo.check(Demo.count(t, v -> v.name() == ITEM && v.failed()) == 1, "the failure reached its item's span");
        Item corrupt = items.get(CORRUPT);
        Demo.check(corrupt.error != null && corrupt.error.contains("corrupt"),
                "the corrupt item's result reports the failure");
        Demo.check(items.stream().filter(it -> it.error != null && !it.cancelled()).count() == 1,
                "no other item failed: the batch went on");

        // refused by the compute pool, then run inline
        int rejected = Demo.count(t, v -> v.handoff() && v.cancelled() && (v.name() == TILE || v.name() == DESCRIBE));
        Demo.check(rejected >= 1, "item 0's burst made the compute pool refuse at least one submission");
        Demo.check(Demo.count(t, v -> !v.handoff() && (v.name() == TILE || v.name() == DESCRIBE)
                && startsWith(v.thread(), "io-")) == rejected,
                "every refused submission never started on the pool and ran inline on its io thread instead");

        // the deadline: at most 3 items per io thread can start within it (see main), so 12 or more are late
        Demo.check(cancelledItems.size() >= ITEMS - 12, "items not started by the deadline were cancelled");
        Demo.check(cancelledItems.size() == items.stream().filter(Item::cancelled).count(),
                "the trace and the results agree on how many items were cancelled");
        Demo.check(Demo.count(t, v -> cancelledItems.contains(v.id()) && v.durationNanos() != 0) == 0
                && Demo.count(t, v -> cancelledItems.contains(v.parent())) == 0,
                "a cancelled item never ran: no time, no spans");
        Demo.check(items.get(0).descriptor != null, "item 0, first in the queue, finished before the deadline");

        // two hops: request thread -> io-* -> cpu-*
        String main = Thread.currentThread().getName();
        int chains = 0;
        for (Map.Entry<Integer, Tempo.Name> e : name.entrySet()) {
            int id = e.getKey(), up = parent.get(id);
            if (e.getValue() != TILE && e.getValue() != DESCRIBE || !startsWith(thread.get(id), "cpu-")) continue;
            while (up >= 0 && name.get(up) != ITEM) up = parent.get(up);
            boolean underRequest = up >= 0 && parent.get(up) == 0 && main.equals(thread.get(0));
            if (underRequest && startsWith(thread.get(up), "io-")) chains++;
        }
        Demo.check(chains >= 1, "tasks ran on cpu-* inside items on io-* inside the request on " + main);

        // the body: unlike the header, it has no byte limit
        Demo.check(Demo.countOf(body, "\"status\":") == ITEMS, "the body reports every item's status");
        Demo.check(Demo.countOf(body, "{\"id\":") == Demo.count(t, v -> true), "the body holds every span of the trace");

        Demo.check(io.isTerminated() && cpu.isTerminated(), "both pools shut down cleanly");
    }

    private static boolean startsWith(String thread, String pool) {
        return thread != null && thread.startsWith(pool);
    }
}
