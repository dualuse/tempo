package io.github.dualuse.tempo.demo;

import io.github.dualuse.tempo.Tempo;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * What every demo shares: stand-ins for real work, named thread pools, the response a service would
 * send, and the checks that keep each demo honest.
 *
 * <p>Nothing here is part of Tempo. The demos do not start servers or open sockets; a "request" is a
 * method call, "I/O" is a sleep and "compute" is a busy loop, so that each demo shows only how Tempo is
 * used around that work.
 *
 * <p>The demo source set compiles with {@code -Xlint:all -Werror}. javac's {@code try} lint warns about
 * {@code try (Tempo.Span s = ...) { work(); }} when the block never names {@code s}, which is the usual
 * way to time a block; a demo either uses the handle in the block or puts {@code @SuppressWarnings("try")}
 * on the method or class, with a comment.
 */
public final class Demo {
    private Demo() {
    }

    // ------------------------------------------------------------------ stand-ins for real work

    /** Blocking I/O (a disk read, a database call, a download): the thread waits and burns no CPU. */
    public static void io(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Computation (decoding, inference, matching): the thread keeps a core busy for about this long. */
    public static long cpu(long micros) {
        long end = System.nanoTime() + micros * 1_000L, x = micros;
        do {
            for (int i = 0; i < 256; i++) x = x * 6364136223846793005L + 1442695040888963407L;
        } while (System.nanoTime() < end);
        return x;
    }

    // ------------------------------------------------------------------ thread pools

    /** A fixed pool whose threads are named {@code prefix-1}, {@code prefix-2}, ...; the trace shows these names. */
    public static ThreadPoolExecutor pool(String prefix, int threads) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), named(prefix));
    }

    /** A fixed pool with a bounded queue: when it is full, {@code whenFull} decides (run inline, refuse, ...). */
    public static ThreadPoolExecutor pool(String prefix, int threads, int queue, RejectedExecutionHandler whenFull) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue), named(prefix), whenFull);
    }

    /** Daemon threads named {@code prefix-1}, {@code prefix-2}, ... */
    public static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Shuts a pool down and waits for its tasks, so that a demo ends with no thread still running. */
    public static void shutdown(ExecutorService... pools) {
        for (ExecutorService p : pools) p.shutdown();
        for (ExecutorService p : pools) {
            try {
                if (!p.awaitTermination(10, TimeUnit.SECONDS)) p.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ narration and the response

    private static boolean quiet;

    /** {@code Demos --all} runs every demo quietly: only the checks matter there. */
    static void quiet(boolean on) {
        quiet = on;
    }

    /** One line of the demo's story, printed before the response. */
    public static void say(String line) {
        if (!quiet) System.out.println("# " + line);
    }

    /**
     * Prints the response a service would send for this trace, the way {@code curl -i} shows it, so
     * that {@code ./gradlew -q demo -Pdemo=Name | tools/tempo} draws it. The four timing headers:
     * {@code Runtime} and {@code Server-Timing} (the labelled phases), {@code Tempo-Timing} (the whole
     * tree, FORMAT.md) and {@code Tempo-Link} (the same tree as a link to Tempo Timeline).
     */
    public static void respond(Tempo.Trace t) {
        respond(t.toRuntime(3), t.toServerTiming(), t.toHeader(), t.toLink());
    }

    /** The same, with header values the demo rendered itself (for instance under a shared byte limit). */
    public static void respond(String runtime, String serverTiming, String tempoTiming, String tempoLink) {
        if (quiet) return;
        System.out.println();
        System.out.println("HTTP/1.1 200 OK");
        if (runtime != null) System.out.println("Runtime: " + runtime);
        if (serverTiming != null) System.out.println("Server-Timing: " + serverTiming);
        if (tempoTiming != null) System.out.println("Tempo-Timing: " + tempoTiming);
        if (tempoLink != null) System.out.println("Tempo-Link: " + tempoLink);
        System.out.println();
    }

    /**
     * Prints a response body after the headers, the way {@code curl -i} shows it. When the body holds
     * the trace as JSON ({@code Snapshot.writeJson}), {@code tools/tempo} reads it in preference to the
     * header: the body has no byte limit, so it holds every span recorded.
     */
    public static void body(CharSequence body) {
        if (!quiet) System.out.println(body);
    }

    // ------------------------------------------------------------------ checks

    /**
     * States what the demo shows, and fails the demo if it does not hold. {@code ./gradlew demos} runs
     * every demo with these checks, so the demos cannot drift away from what Tempo does. Checks are
     * about the shape of a trace (which spans, which threads, which flags), never about exact times.
     */
    public static void check(boolean holds, String claim) {
        if (!holds) throw new AssertionError("demo claim does not hold: " + claim);
    }

    /** How many recorded spans (rows and aggregates) of the trace match. */
    public static int count(Tempo.Trace t, Predicate<Tempo.SpanView> which) {
        int[] n = {0};
        t.forEachSpan(v -> {
            if (which.test(v)) n[0]++;
        });
        return n[0];
    }

    /** How many recorded spans of the trace carry this name. */
    public static int count(Tempo.Trace t, Tempo.Name name) {
        return count(t, v -> v.name() == name);
    }

    /** How many recorded spans (rows and aggregates) of the snapshot match. */
    public static int count(Tempo.Snapshot s, Predicate<Tempo.SpanView> which) {
        int[] n = {0};
        s.forEachSpan(v -> {
            if (which.test(v)) n[0]++;
        });
        return n[0];
    }

    /** How many times the matching spans ran: 1 for a row, its {@code count()} for an aggregate. */
    public static long occurrences(Tempo.Trace t, Predicate<Tempo.SpanView> which) {
        long[] n = {0};
        t.forEachSpan(v -> {
            if (which.test(v)) n[0] += v.count();
        });
        return n[0];
    }

    // ------------------------------------------------------------------ reading a trace back

    /**
     * The id of the first span called {@code name}, or -1. Ids are the order of {@code forEachSpan}
     * (the root is 0), and {@code SpanView.parent()} is such an id: compare it with this one to find
     * a span's children.
     */
    public static int id(Tempo.Trace t, Tempo.Name name) {
        int[] id = {-1};
        t.forEachSpan(v -> {
            if (id[0] < 0 && v.name() == name) id[0] = v.id();
        });
        return id[0];
    }

    /**
     * The long attribute {@code key} of this span, or -1 if it has none. A {@code SpanView} is reused for
     * the next span, so read what you need while visiting.
     */
    public static long attribute(Tempo.SpanView v, Tempo.Name key) {
        for (int i = 0; i < v.attributes(); i++)
            if (v.attributeKey(i) == key && !v.attributeIsDouble(i)) return v.longAttribute(i);
        return -1;
    }

    /** The long attribute {@code key} of the first span called {@code name}, or -1. */
    public static long attribute(Tempo.Trace t, Tempo.Name name, Tempo.Name key) {
        long[] value = {-1};
        boolean[] seen = {false};
        t.forEachSpan(v -> {
            if (seen[0] || v.name() != name) return;
            seen[0] = true;
            value[0] = attribute(v, key);
        });
        return value[0];
    }

    // ------------------------------------------------------------------ reading a header value

    /**
     * A field of a {@code Tempo-Timing} value, such as {@code e} (the number of records) or {@code x}
     * ({@code folded.dropped}), FORMAT.md; null if the value has none. Fields are separated by
     * {@code ;}, which never occurs inside one (names are percent-escaped).
     */
    public static String field(String value, String key) {
        for (String f : value.split(";")) if (f.startsWith(key + "=")) return f.substring(key.length() + 1);
        return null;
    }

    /** How many spans a value folded into aggregates: the first part of {@code x=}, base 36; 0 without {@code x=}. */
    public static long folded(String value) {
        String x = field(value, "x");
        return x == null ? 0 : Long.parseLong(x.substring(0, x.indexOf('.')), 36);
    }

    /** The size of a header value as a proxy counts it: in UTF-8 bytes, not chars. */
    public static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** How often {@code part} occurs in {@code text}, e.g. {@code {"id":} in a JSON body: one per span. */
    public static int countOf(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
        return n;
    }
}
