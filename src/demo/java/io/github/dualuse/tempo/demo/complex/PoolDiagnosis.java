package io.github.dualuse.tempo.demo.complex;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * One pool or two? A search service loads candidates (blocking I/O) and matches each one (CPU), and
 * the team argues whether both kinds of task should share one pool sized to the cores. Instead of
 * arguing, the demo serves the same six concurrent requests under both layouts, traces every request
 * at {@code STEP}, and answers the question by reading the traces in code.
 *
 * <ul>
 *   <li>Layout A: one pool {@code shared} of 2 threads (the cores) runs loads and matches. A blocking
 *       load holds a compute thread for its whole wait, and a match queues behind every load submitted
 *       before it.</li>
 *   <li>Layout B: a pool {@code io} of 8 threads for loads and a pool {@code cpu} of 2 for matches.
 *       More threads, but not more CPU: the io threads sleep, so only the 2 cpu threads compete for
 *       the cores.</li>
 * </ul>
 *
 * <p>It shows many concurrent traces read in code: the demo prints every request's response, then walks
 * each trace with {@link Tempo.Trace#forEachSpan} and sums what a {@link Tempo.SpanView} says about
 * every task: its queue wait, its run time, its CPU time and the thread it ran on. A table and a
 * verdict close the output. It builds on {@code WorkerPool} (tasks, queue wait, CPU per task).
 * Save the output once, so every view below reads the same run:
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=PoolDiagnosis &gt; out.txt                12 responses, the table, the verdict
 * tools/tempo out.txt                                             request 1 of layout A as lanes
 * grep '^Tempo-Timing' out.txt | sed -n 1p &gt; a.header             request 1 of layout A
 * grep '^Tempo-Timing' out.txt | sed -n 7p &gt; b.header             request 1 of layout B
 * tools/tempo b.header                                            request 1 of layout B as lanes
 * tools/tempo a.header --diff b.header                            the two compared per path
 * sed -n '/^# layout A/,/^# layout B/p' out.txt | tools/tempo --stats   the 6 requests of A (B: '/^# layout B/,$p')
 * tools/tempo a.header --pools                                    the shared pool's running and queued tasks over time
 * </pre>
 *
 * Look for: in layout A, lanes {@code shared-1} and {@code shared-2} run loads and matches
 * alike, and every {@code match} starts with a long queue wait ({@code ░}, {@code ~} in the header);
 * {@code --pools} shows the shared pool saturated with a deep queue. In layout B the loads run on
 * {@code io-*}, the matches on {@code cpu-*}, with short waits and a CPU/wall ratio near 100 % on
 * {@code cpu} (near 0 % on {@code io}). Tempo Timeline compares the two too: open the Tempo-Link of
 * request 1 of A and press Compare with B's. A load hands its match on before it ends, so the match
 * nests under the load and outlives it: {@code --findings} calls it a straggler, as expected.
 */
public final class PoolDiagnosis {
    private PoolDiagnosis() {
    }

    static final Tempo.Name SEARCH = Tempo.name("GET /search"), LOAD = Tempo.name("load"),
            MATCH = Tempo.name("match"), JOIN = Tempo.name("join"),
            QUEUED = Tempo.name("queued"), ACTIVE = Tempo.name("active"), POOLS = Tempo.name("pools");

    // Sizes chosen so the effect is large: 24 loads of 20 ms on 2 threads keep layout A's pool busy
    // for about 240 ms, while each match needs only 2 ms of CPU.
    static final int REQUESTS = 6, CANDIDATES = 4, CORES = 2, IO_THREADS = 8;
    static final long LOAD_MS = 20, MATCH_MICROS = 2_000;

    public static void main(String[] args) throws Exception {
        ThreadPoolExecutor requests = Demo.pool("req", REQUESTS);

        // CORES is fixed rather than read from the machine, so the demo shows the same thing everywhere.
        ThreadPoolExecutor shared = Demo.pool("shared", CORES);
        Demo.say("layout A: one pool, shared-1..2, runs loads and matches");
        List<Tempo.Trace> a = serve(requests, shared, shared, 1);
        Demo.shutdown(shared);
        for (Tempo.Trace t : a) Demo.respond(t);

        ThreadPoolExecutor io = Demo.pool("io", IO_THREADS), cpu = Demo.pool("cpu", CORES);
        Demo.say("layout B: loads on io-1..8, matches on cpu-1..2");
        List<Tempo.Trace> b = serve(requests, io, cpu, 2);
        Demo.shutdown(io, cpu, requests);
        for (Tempo.Trace t : b) Demo.respond(t);

        Reading ra = read(a), rb = read(b);
        Demo.say("");
        Demo.say("layout  match wait: sum       max   load wait: sum   request: p50       max   tasks, cpu/wall per pool");
        Demo.say(ra.row("A"));
        Demo.say(rb.row("B"));
        Demo.say(String.format(Locale.ROOT, "verdict: %s (matches queued %.0fx as long in A, where they wait behind blocking loads)",
                ra.matchWait > 2 * rb.matchWait ? "give blocking loads their own pool" : "one pool is enough here",
                ra.matchWait / (double) Math.max(1, rb.matchWait)));
        Demo.say(String.format(Locale.ROOT, "  B's %d io threads cost no cores (io %.0f%% CPU/wall): cpu stays at %d threads, and matches no longer queue behind sleeps",
                IO_THREADS, 100 * rb.cpuRatio("io"), CORES));
        Demo.say("save once: ./gradlew -q demo -Pdemo=PoolDiagnosis > out.txt");
        Demo.say("per layout: sed -n '/^# layout A/,/^# layout B/p' out.txt | tools/tempo --stats   (B: '/^# layout B/,$p')");
        Demo.say("request 1 of each: grep '^Tempo-Timing' out.txt | sed -n 1p > a.header; the same with 7p > b.header;");
        Demo.say("  tools/tempo a.header --diff b.header, tools/tempo a.header --pools, or Compare in Tempo Timeline");

        for (int r = 0; r < REQUESTS; r++) {
            Demo.check(ra.loads[r] == CANDIDATES && rb.loads[r] == CANDIDATES
                            && ra.matches[r] == CANDIDATES && rb.matches[r] == CANDIDATES,
                    "every request handed off one load and one match per candidate");
        }
        Demo.check(ra.ranCleanly && rb.ranCleanly,
                "every task began, none failed or was cancelled, each match nests under its load, CPU was measured on each");
        Demo.check(ra.loadThreads.stream().allMatch(t -> t.startsWith("shared-"))
                        && ra.matchThreads.stream().allMatch(t -> t.startsWith("shared-")),
                "in A, loads and matches run on the shared pool only");
        Demo.check(ra.loadThreads.stream().anyMatch(ra.matchThreads::contains),
                "in A, the same threads run loads and matches: a match takes a thread a load held before");
        Demo.check(rb.loadThreads.stream().allMatch(t -> t.startsWith("io-"))
                        && rb.matchThreads.stream().allMatch(t -> t.startsWith("cpu-")),
                "in B, loads run on the io pool only and matches on the cpu pool only");
        Demo.check(rb.cpuRatio("io") < 0.5, "in B, the io pool spends its time blocked, not on a CPU");
        Demo.check(ra.matchWait > 2 * rb.matchWait,
                "matches wait in the queue more than twice as long in total in A as in B");

        for (Tempo.Trace t : a) t.release();
        for (Tempo.Trace t : b) t.release();
    }

    /** Serves REQUESTS concurrent searches; loads go to {@code loads}, matches to {@code matches}. */
    private static List<Tempo.Trace> serve(ExecutorService requests, ThreadPoolExecutor loads,
                                           ThreadPoolExecutor matches, int pools) throws Exception {
        List<Future<Tempo.Trace>> served = new ArrayList<>();
        for (int r = 0; r < REQUESTS; r++) served.add(requests.submit(() -> search(loads, matches, pools)));
        List<Tempo.Trace> traces = new ArrayList<>();
        for (Future<Tempo.Trace> f : served) traces.add(f.get());
        return traces;
    }

    /** One request, on a request thread: the traced code is the same under both layouts. */
    private static Tempo.Trace search(ThreadPoolExecutor loads, ThreadPoolExecutor matches, int pools) throws Exception {
        // STEP measures the CPU time of every task, which the diagnosis needs.
        Tempo.Trace t = Tempo.trace(SEARCH, new Tempo.Options().strict(true)).records(Tempo.STEP);
        try (t) {
            Tempo.Span s = t.set(POOLS, pools);     // tells the layouts apart in a viewer
            List<Future<Future<?>>> pending = new ArrayList<>();
            for (int c = 0; c < CANDIDATES; c++) {
                // The pool's busy threads and queue depth at submit: tools/tempo --pools draws them as
                // pool-wide gauges, so one trace shows how full the pool was for everyone.
                Tempo.CallableTask<Future<?>> load = Tempo.callable(LOAD, () -> {
                    Demo.io(LOAD_MS);
                    // Still inside the load task: the match it submits nests under this load.
                    Tempo.RunnableTask m = Tempo.runnable(MATCH, () -> Demo.cpu(MATCH_MICROS));
                    m.set(ACTIVE, matches.getActiveCount()).set(QUEUED, matches.getQueue().size());
                    matches.execute(m);
                    return m; // the task is its own future
                });
                load.set(ACTIVE, loads.getActiveCount()).set(QUEUED, loads.getQueue().size());
                loads.submit(load);
                pending.add(load);
            }
            // A future completes only after its task's span closed, so the trace closes with no task open.
            Tempo.Span join = s.span(JOIN).waiting();
            try {
                for (Future<Future<?>> f : pending) f.get().get();
            } finally {
                join.close();
            }
        }
        return t;
    }

    /** What the traces of one layout say, summed over its requests. */
    private static final class Reading {
        long loadWait, matchWait, matchWaitMax;
        final long[] latency = new long[REQUESTS];
        final int[] loads = new int[REQUESTS], matches = new int[REQUESTS];
        final Set<String> loadThreads = new HashSet<>(), matchThreads = new HashSet<>();
        final Map<String, long[]> pools = new TreeMap<>(); // pool -> {tasks, cpu, wall}
        boolean ranCleanly = true;

        double cpuRatio(String pool) {
            long[] p = pools.get(pool);
            return p == null || p[2] == 0 ? 0 : p[1] / (double) p[2];
        }

        String row(String layout) {
            long[] sorted = latency.clone();
            Arrays.sort(sorted);
            StringBuilder perPool = new StringBuilder();
            for (Map.Entry<String, long[]> e : pools.entrySet()) {
                perPool.append(String.format(Locale.ROOT, "  %s %d, %.0f%%", e.getKey(), e.getValue()[0],
                        100 * cpuRatio(e.getKey())));
            }
            return String.format(Locale.ROOT, "%-6s %12s %9s %15s %14s %9s %s", layout, ms(matchWait), ms(matchWaitMax),
                    ms(loadWait), ms(sorted[(REQUESTS - 1) / 2]), ms(sorted[REQUESTS - 1]), perPool);
        }

        private static String ms(long nanos) {
            return String.format(Locale.ROOT, "%.1f ms", nanos / 1e6);
        }
    }

    private static Reading read(List<Tempo.Trace> traces) {
        Reading r = new Reading();
        for (int i = 0; i < traces.size(); i++) {
            int request = i;
            List<Tempo.Name> names = new ArrayList<>(); // by span id, which is the visiting order
            traces.get(i).forEachSpan(v -> {
                names.add(v.name());
                if (v.id() == 0) r.latency[request] = v.durationNanos();
                if (!v.handoff()) return;
                if (v.neverStarted() || v.failed() || v.cancelled() || v.aggregate() || v.cpuNanos() < 0
                        || v.name() == MATCH && names.get(v.parent()) != LOAD) r.ranCleanly = false;
                String thread = v.thread() == null ? "?" : v.thread();
                long[] pool = r.pools.computeIfAbsent(thread.replaceAll("-\\d+$", ""), p -> new long[3]);
                pool[0]++;
                pool[1] += Math.max(0, v.cpuNanos());
                pool[2] += v.durationNanos();
                if (v.name() == LOAD) {
                    r.loads[request]++;
                    r.loadWait += v.waitNanos();
                    r.loadThreads.add(thread);
                } else if (v.name() == MATCH) {
                    r.matches[request]++;
                    r.matchWait += v.waitNanos();
                    r.matchWaitMax = Math.max(r.matchWaitMax, v.waitNanos());
                    r.matchThreads.add(thread);
                }
            });
        }
        return r;
    }
}
