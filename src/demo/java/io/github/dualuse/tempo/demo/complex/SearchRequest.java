package io.github.dualuse.tempo.demo.complex;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One search request through a service with two thread pools, {@code io} for blocking reads and
 * {@code cpu} for computation. The request decodes the query, fingerprints it on {@code cpu}, asks the
 * index on {@code io} for 48 candidates, then reranks them: each candidate is loaded on {@code io}, and
 * the load hands its data to a {@code match} on {@code cpu}. The request stops as soon as it has four
 * good hits and takes back the work that has not started; one load, from cold storage, is still
 * running when the response goes out.
 *
 * <p>It shows the whole lifecycle in a service: a filter that starts every trace {@code COARSE} and
 * times the routing before the handler, an opt-in ({@code tempo=1}) that raises the trace to
 * {@code DETAIL} once, handoffs from the request thread and from a pool thread (the chain
 * request → {@code io-N} → {@code cpu-N}, with a queue wait on each hop), blocking waits, a hot loop
 * that spends the detail budget and folds, an early exit that takes back every task that has not begun
 * (each cancel either wins, and the task never runs, or loses to a task already running), a straggler,
 * and the four response headers. It puts together what
 * {@code WorkerPool}, {@code LevelsAndOptIn} and {@code ResponseHeaders} show one at a time.
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=SearchRequest                   the story and the response
 * ./gradlew -q demo -Pdemo=SearchRequest | tools/tempo     the lanes: main, io-1..4, cpu-1..3
 * ./gradlew -q demo -Pdemo=SearchRequest | tools/tempo --tree    or --agg, --pools, --findings
 * </pre>
 *
 * Look for: {@code load} on {@code io-*} and {@code match} on {@code cpu-*}, each after its
 * queue wait ({@code ░} in the lanes, {@code ~} in the header), the matches waiting longer and longer
 * because three threads cannot keep up with the loads; {@code score ×N} aggregates inside the
 * matches, and {@code x=} in the header; handoffs past the budget fold too ({@code match ×1});
 * the tasks cancelled by the early exit ({@code ⊘}, flag {@code c}); the cold load still open
 * ({@code ▶}, flag {@code o}) on a live lane ({@code ●}). In {@code --tree}, CPU against wall time:
 * {@code match} is on CPU nearly all of its time, {@code load} hardly at all. {@code Runtime} and
 * {@code Server-Timing} list the legacy steps, {@code Routing} to {@code Rerank}. Or open the printed
 * {@code Tempo-Link} in a browser.
 */
@SuppressWarnings("try") // a span opened only to time its block is never named inside it
public final class SearchRequest {
    static final Tempo.Name SEARCH = Tempo.name("GET /search"), ROUTING = Tempo.name("routing"),
            DECODE = Tempo.name("decode"), DESCRIBE = Tempo.name("describe"), FINGERPRINT = Tempo.name("fingerprint"),
            LOOKUP = Tempo.name("lookup"), QUERY = Tempo.name("query"), RERANK = Tempo.name("rerank"),
            LOAD = Tempo.name("load"), MATCH = Tempo.name("match"), SCORE = Tempo.name("score"),
            AWAIT = Tempo.name("await");
    static final Tempo.Name CANDIDATES = Tempo.name("candidates"), QUEUED = Tempo.name("queued"),
            BYTES = Tempo.name("bytes"), COLD = Tempo.name("cold"), SIMILARITY = Tempo.name("similarity"),
            HITS = Tempo.name("hits"), CANCELLED = Tempo.name("cancelled");

    static final int CANDIDATES_FOUND = 48, WANTED = 4, FEATURES = 128;
    static final long LOAD_MS = 2, SCORE_MICROS = 50; // a match: 128 features of 50 us, over 6 ms of CPU
    static final double GOOD = 0.5;

    // One limit for the four timing headers together: proxies limit the whole header block, often to 4 or
    // 8 KB. Within it, each Tempo header keeps to a value of at most 3,500 bytes, the link compressed.
    static final int HEADER_LIMIT = 6_000, TEMPO_BYTES = 3_500;

    // The service: two pools, and the slow tier of the storage. A latch stands in for the slow read, so
    // the demo decides when it answers: after the response, every time.
    final ThreadPoolExecutor io = Demo.pool("io", 4), cpu = Demo.pool("cpu", 3);
    final CountDownLatch coldStorage = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        SearchRequest service = new SearchRequest();
        service.start();
        try {
            Response r = service.filter("q=red+lamp&tempo=1");
            check(r, Thread.currentThread().getName());
            Demo.respond(r.runtime, r.serverTiming, r.tempoTiming, r.tempoLink);

            // The cold read answers now. Its load sets bytes and closes its span in a trace the filter has
            // released: both are no-ops. (It hands off no match: the request stopped the fan-out.) A misuse
            // would have thrown in the task under strict, and join() would rethrow it.
            service.coldStorage.countDown();
            r.leftBehind.join();
            Demo.check(r.trace.toHeader().isEmpty(), "after release the trace reads empty: render from the snapshot");
            Demo.check(r.snapshot.toHeader(r.timingBytes).equals(r.tempoTiming),
                    "the snapshot taken for the response still renders the same header after release");
        } finally {
            service.coldStorage.countDown();
            Demo.shutdown(service.io, service.cpu);
        }
    }

    /** What a service does before it takes traffic: start its threads. */
    void start() {
        io.prestartAllCoreThreads();
        cpu.prestartAllCoreThreads();
    }

    // ------------------------------------------------------------------ the filter and the handler

    /** In front of every handler: starts the trace, times the routing, renders the headers at the end. */
    Response filter(String query) throws Exception {
        // COARSE, before anyone has read the request: cheap enough for every request in production.
        Tempo.Trace t = Tempo.start(SEARCH, new Tempo.Options().strict(true).headerBytes(TEMPO_BYTES));
        Response r;
        try (t) {
            Demo.cpu(1_000); // authenticate, match the route
            t.root().done(ROUTING, "Routing"); // a lap from the trace start: the time before the handler
            r = handle(t, query);
        }
        // One snapshot for all four headers, so they agree. The tasks still running go on recording into
        // the trace, but not into the snapshot.
        Tempo.Snapshot s = t.snapshot();
        r.trace = t;
        r.snapshot = s;
        r.runtime = s.toRuntime(3);
        r.serverTiming = s.toServerTiming();
        // The legacy headers first, then the Tempo headers from what is left. The two should carry the same
        // value (FORMAT.md 9.5): toHeader() and toLink() both keep to headerBytes, so they do. If they do not
        // fit together, the response sends Tempo-Timing alone, within what is left.
        int left = HEADER_LIMIT - Demo.bytes(r.runtime) - Demo.bytes(r.serverTiming);
        String timing = s.toHeader(), link = s.toLink();
        boolean both = Demo.bytes(timing) + Demo.bytes(link) <= left;
        r.timingBytes = both ? TEMPO_BYTES : left;
        r.tempoTiming = both ? timing : s.toHeader(left);
        r.tempoLink = both ? link : null;
        t.release(); // the cold load still runs: it keeps its lane until it ends, and what it records is lost
        return r;
    }

    /** The handler. Each step is a lap of the root, labelled for the legacy {@code Runtime} header. */
    Response handle(Tempo.Trace t, String query) throws Exception {
        Tempo.Span root = t.root();

        // The opt-in gate: once, and before the first detail span, or raise refuses (strict: throws). At
        // DETAIL every task measures its CPU time; the first CPU read in a JVM sets that clock up (it takes
        // milliseconds), and raise() does it here, on the request thread: in the first such request of a
        // JVM it shows in this lap, never as a task's queue wait.
        if (query.contains("tempo=1")) t.raise(Tempo.DETAIL);

        Demo.cpu(1_000);
        root.done(DECODE, "Decode");

        // The handoff is taken where the task is submitted; callable() begins it where it runs. The request
        // thread blocks meanwhile, and says so: waiting() sets the join apart from run time.
        Tempo.Handoff fp = root.handoff(FINGERPRINT);
        Future<Long> signature = cpu.submit(fp.callable(() -> Demo.cpu(1_500)));
        try (Tempo.Span w = root.span(AWAIT).waiting()) {
            signature.get();
        }
        root.done(DESCRIBE, "Describe");

        Tempo.Handoff q = root.handoff(QUERY);
        Future<int[]> found = io.submit(q.callable(() -> {
            Demo.io(3);
            return candidates();
        }));
        int[] ids;
        try (Tempo.Span w = root.span(AWAIT).waiting()) {
            ids = found.get();
        }
        root.done(LOOKUP, "Index Lookup").phaseSet(CANDIDATES, ids.length); // a number on the lap

        Rerank rerank = new Rerank();
        for (int id : ids) rerank.submit(root, LOAD, io, h -> () -> load(rerank, id, h));

        List<Integer> hits = new ArrayList<>();
        try (Tempo.Span w = root.span(AWAIT).waiting()) {
            while (hits.size() < WANTED) {
                Match m = rerank.results.poll(10, TimeUnit.SECONDS);
                if (m == null) break;
                if (m.similarity >= GOOD) hits.add(m.id);
            }
            w.set(HITS, hits.size());
        }
        int cancelled = rerank.stop(); // enough hits: take back whatever has not begun
        root.done(RERANK, "Rerank").phaseSet(HITS, hits.size()).phaseSet(CANCELLED, cancelled);
        Demo.say("early exit at " + hits.size() + " good hits: " + cancelled + " loads and matches cancelled before they began");

        Response r = new Response();
        r.hits = hits;
        r.cancelled = cancelled;
        r.leftBehind = rerank;
        return r;
    }

    // ------------------------------------------------------------------ the tasks

    /** On {@code io-N}: reads one candidate, then hands its data to a match on {@code cpu}. */
    void load(Rerank rerank, int id, Tempo.Handoff h) {
        // tryBegin() records the queue wait; the span is the root of io-N's lane, and its handle stays on
        // io-N. It is null when the early exit took the task back first: then there is nothing to do.
        Tempo.Span l = h.tryBegin();
        if (l == null) return;
        try (l) {
            if (id == COLD_ID) {
                l.set(COLD, 1);
                awaitColdStorage();
            } else {
                Demo.io(LOAD_MS);
            }
            l.set(BYTES, 4_096 + id % 512);
            // Handed off inside the load span: the match's parent is this load, on another thread.
            rerank.submit(l, MATCH, cpu, m -> () -> match(rerank, id, m));
        }
    }

    /** On {@code cpu-N}: a hot loop of one detail span per feature. Past the budget, they fold. */
    void match(Rerank rerank, int id, Tempo.Handoff h) {
        Tempo.Span m = h.tryBegin();
        if (m == null) return; // taken back by the early exit before it began
        try (m) {
            for (int f = 0; f < FEATURES; f++) {
                try (Tempo.Span s = m.detail(SCORE)) {
                    Demo.cpu(SCORE_MICROS);
                }
            }
            double similarity = (id % 10) / 10.0;
            m.set(SIMILARITY, similarity);
            rerank.results.add(new Match(id, similarity));
        }
    }

    // ------------------------------------------------------------------ the fan-out and the early exit

    /**
     * One request's tasks on both pools, and the results it collects. Submitting and stopping share a
     * lock, so no load hands off a match once the request has stopped.
     */
    static final class Rerank {
        final BlockingQueue<Match> results = new LinkedBlockingQueue<>();
        private final List<Queued> tasks = new ArrayList<>(); // guarded by this
        private boolean stopped;                              // guarded by this

        /** Hands a task to {@code pool} from {@code parent}'s thread, unless the request has stopped. */
        synchronized void submit(Tempo.Span parent, Tempo.Name name, ThreadPoolExecutor pool,
                                 Function<Tempo.Handoff, Runnable> task) {
            if (stopped) return;
            // The pool's queue length at submit: tools/tempo --pools draws it as the pool-wide signal.
            Tempo.Handoff h = parent.handoff(name).set(QUEUED, pool.getQueue().size());
            tasks.add(new Queued(pool.submit(task.apply(h)), h, pool));
        }

        /**
         * Stops the fan-out and cancels every task that has not begun; returns how many. The handoff decides
         * the race with a worker: cancel() wins only if the task has not begun, and then the wrapper skips
         * its body even if a worker takes it right now, so the trace shows it cancelled before it began. A
         * lost cancel means the task is running: it is left to finish. The cancelled tasks stay in the
         * queues, where they cost a worker nothing; ThreadPoolExecutor.remove would only free the slots of a
         * bounded queue sooner, and these queues are unbounded. Handoff.NOOP (an untraced request) takes no
         * part in the race, so there remove() decides it: it takes the task out of its queue only if no
         * worker took it (Future.cancel alone would also succeed on a running task, whose body runs on).
         */
        synchronized int stop() {
            stopped = true;
            int n = 0;
            for (Queued q : tasks) {
                boolean won = q.handoff == Tempo.Handoff.NOOP ? q.pool.remove((Runnable) q.task) : q.handoff.cancel();
                if (won) {
                    q.task.cancel(false); // completes the future for anyone who waits on it
                    n++;
                }
            }
            return n;
        }

        /** Waits for every task that was not cancelled; a misuse thrown in one (strict) surfaces here. */
        void join() throws Exception {
            List<Queued> all;
            synchronized (this) {
                all = new ArrayList<>(tasks);
            }
            for (Queued q : all) if (!q.task.isCancelled()) q.task.get(10, TimeUnit.SECONDS);
        }
    }

    static final class Queued {
        final Future<?> task;
        final Tempo.Handoff handoff;
        final ThreadPoolExecutor pool;

        Queued(Future<?> task, Tempo.Handoff handoff, ThreadPoolExecutor pool) {
            this.task = task;
            this.handoff = handoff;
            this.pool = pool;
        }
    }

    static final class Match {
        final int id;
        final double similarity;

        Match(int id, double similarity) {
            this.id = id;
            this.similarity = similarity;
        }
    }

    /** The response headers, and what the demo keeps to check them. */
    static final class Response {
        List<Integer> hits;
        int cancelled;
        Rerank leftBehind;
        Tempo.Trace trace;
        Tempo.Snapshot snapshot;
        String runtime, serverTiming, tempoTiming, tempoLink;
        int timingBytes; // what the shared limit left for Tempo-Timing
    }

    // ------------------------------------------------------------------ the stand-ins

    // The index answers with ids 100, 103, 106, ...; a match scores id % 10 / 10, so ids ending in 5 to 9
    // are good: the 3rd, 4th, 6th and 7th candidate are the first four. The first one lives in cold storage.
    static final int FIRST_ID = 100, COLD_ID = FIRST_ID;

    static int[] candidates() {
        int[] ids = new int[CANDIDATES_FOUND];
        for (int i = 0; i < ids.length; i++) ids[i] = FIRST_ID + 3 * i;
        return ids;
    }

    void awaitColdStorage() {
        try {
            coldStorage.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ what the demo claims

    static void check(Response r, String requestThread) {
        List<Row> rows = Row.all(r.snapshot);
        Demo.check(r.trace.level() == Tempo.DETAIL, "the opt-in raised the trace to DETAIL");
        Demo.check(r.hits.size() == WANTED, "the request found " + WANTED + " good hits and stopped");

        Demo.check(count(rows, v -> v.depth == 0 && requestThread.equals(v.thread)) == 1, "the root runs on " + requestThread);
        Demo.check(count(rows, v -> v.name == FINGERPRINT && v.handoff && v.thread.startsWith("cpu-")) == 1
                && count(rows, v -> v.name == QUERY && v.handoff && v.thread.startsWith("io-")) == 1,
                "fingerprint ran on cpu-*, query on io-*, each one handoff");
        Demo.check(count(rows, v -> v.name == AWAIT && v.waiting && requestThread.equals(v.thread)) == 3,
                "the request thread blocked three times, each wait flagged w");

        // The chain. The first match handoff comes before any score span, so the budget still has room
        // for it to be a row of its own.
        Demo.check(count(rows, v -> v.name == MATCH && v.handoff && !v.aggregate && v.ran()
                        && v.thread.startsWith("cpu-") && v.parent.name == LOAD && v.parent.thread.startsWith("io-")
                        && v.parent.parent.name == RERANK && requestThread.equals(v.parent.parent.thread)) > 0,
                "a match on cpu-* was handed off inside a load on io-*, which the request handed off");
        Demo.check(count(rows, v -> v.name == MATCH && v.handoff && v.waitMax >= 1_000_000) > 0,
                "some match waited 1 ms or more in the cpu queue: three io threads outpace three cpu threads");

        // The early exit. Cancelled before it began: a handoff with flag c and no run time.
        long cancelled = rows.stream().filter(v -> (v.name == LOAD || v.name == MATCH) && v.handoff && v.cancelled)
                .mapToLong(v -> v.count).sum();
        Demo.check(r.cancelled > 0, "the early exit took back at least one queued task");
        // If the request thread was descheduled while it submitted, the cpu lanes spent the budget first and
        // later loads folded under rerank. Such a group mixes loads that ran with loads taken back, and an
        // aggregate keeps only the flags all its occurrences share: it carries no c.
        boolean loadsFolded = count(rows, v -> v.name == LOAD && v.handoff && v.aggregate) > 0;
        Demo.check(loadsFolded ? cancelled > 0 && cancelled <= r.cancelled : cancelled == r.cancelled,
                "each task taken back shows cancelled (^c)" + (loadsFolded
                        ? ", except in a folded group that also holds loads that ran: an aggregate keeps only the flags all its tasks share"
                        : "") + ": " + cancelled + " of " + r.cancelled);
        Demo.check(count(rows, v -> v.misuse || v.healed) == 0, "no misuse, nothing healed");

        // The budget, 512 by default, is spent before the exit, whatever the timing: four matches have
        // ended, and 2 + 48 handoffs + 4 x (1 handoff + 128 score spans) is more than 512.
        Demo.check(count(rows, v -> v.name == SCORE && v.aggregate) > 0, "past the budget, score spans folded into aggregates");
        Demo.check(Demo.folded(r.tempoTiming) > 0, "the header counts the folded spans in x=");
        int total = Demo.bytes(r.runtime) + Demo.bytes(r.serverTiming) + Demo.bytes(r.tempoTiming)
                + (r.tempoLink == null ? 0 : Demo.bytes(r.tempoLink));
        Demo.check(total <= HEADER_LIMIT, "the timing headers keep to " + HEADER_LIMIT + " bytes together: " + total);
        Demo.check(r.tempoLink != null, "Tempo-Timing and Tempo-Link fit together, carrying the same value");
        // How far down the ladder the value goes depends on how many spans the timing let in, so the loads
        // and matches may or may not survive the limit; what the ladder leaves out is always counted.
        List<String> kept = Arrays.asList(names(r.tempoTiming));
        String x = Demo.field(r.tempoTiming, "x");
        long dropped = x == null ? 0 : Long.parseLong(x.substring(x.indexOf('.') + 1), 36);
        Demo.check(kept.containsAll(List.of("describe", "lookup", "rerank")),
                "within " + TEMPO_BYTES + " bytes, the value keeps the request's own steps");
        Demo.check(kept.containsAll(List.of("load", "match")) || dropped > 0,
                "spans the ladder left out are counted as dropped in x=");

        Demo.check(count(rows, v -> v.name == LOAD && v.open && v.attr(COLD) == 1 && v.thread.startsWith("io-")) == 1,
                "the cold load was still open on io-* when the headers were rendered (^o)");
        Demo.check(count(rows, v -> v.open && requestThread.equals(v.thread)) == 0,
                "only pool threads had spans open at render: the request itself had ended");

        // The legacy steps: the laps marked by done(name, label), in order, routing first.
        List<Tempo.Name> tops = new ArrayList<>();
        r.snapshot.forEachTop((name, label, start, dur) -> tops.add(name));
        Demo.check(tops.equals(List.of(ROUTING, DECODE, DESCRIBE, LOOKUP, RERANK)), "the legacy steps are " + tops);
        Demo.check(inOrder(r.runtime, "(Routing)", "(Decode)", "(Describe)", "(Index Lookup)", "(Rerank)"),
                "Runtime lists the labels in order: " + r.runtime);
        Demo.check(inOrder(r.serverTiming, "routing;", "decode;", "describe;", "lookup;", "rerank;", "total;"),
                "Server-Timing lists the steps in order, then the total");

        // CPU time is measured for each lane root at DETAIL, so each task shows what kind of work it is.
        long[] load = cpuAndWall(rows, LOAD), match = cpuAndWall(rows, MATCH);
        Demo.check(load[0] >= 0 && load[0] * 2 < load[1], "loads are mostly off CPU: they wait for I/O");
        Demo.check(match[0] > 0, "matches have their CPU time measured");
        Demo.say(String.format(Locale.ROOT, "CPU of wall time: match %d%%, load %d%%",
                100 * match[0] / Math.max(1, match[1]), 100 * load[0] / Math.max(1, load[1])));
        Demo.say("the cold load is still running (^o) as the headers are rendered; pipe this into tools/tempo");
    }

    /** CPU and wall time summed over the closed tasks of {@code name} that measured CPU, aggregates included. */
    static long[] cpuAndWall(List<Row> rows, Tempo.Name name) {
        long[] sum = new long[2];
        for (Row v : rows) {
            if (v.name != name || !v.handoff || v.open || v.cpu < 0) continue;
            sum[0] += v.cpu;
            sum[1] += v.dur;
        }
        return sum;
    }

    static boolean inOrder(String text, String... parts) {
        int at = 0;
        for (String p : parts) {
            at = text.indexOf(p, at);
            if (at < 0) return false;
        }
        return true;
    }

    /** The name table {@code N=} of a header value; these names need no escaping. */
    static String[] names(String header) {
        String n = Demo.field(header, "N");
        return n == null ? new String[0] : n.split(":");
    }

    static long count(List<Row> rows, Predicate<Row> which) {
        return rows.stream().filter(which).count();
    }

    /** A copy of one {@link Tempo.SpanView}, which the walk reuses, linked to its parent's copy. */
    static final class Row {
        Row parent;
        Tempo.Name name;
        String thread;
        int depth;
        boolean handoff, aggregate, open, cancelled, neverStarted, waiting, misuse, healed;
        long count, dur, waitMax, cpu;
        final Map<Tempo.Name, Long> attrs = new HashMap<>();

        /** Began on a worker: neither still queued nor cancelled (a task whose cancel won never began). */
        boolean ran() {
            return !neverStarted && !cancelled;
        }

        long attr(Tempo.Name key) {
            return attrs.getOrDefault(key, -1L);
        }

        static List<Row> all(Tempo.Snapshot s) {
            List<Row> rows = new ArrayList<>();
            s.forEachSpan(v -> {
                Row r = new Row();
                r.parent = v.parent() < 0 ? null : rows.get(v.parent());
                r.name = v.name();
                r.thread = v.thread();
                r.depth = v.depth();
                r.handoff = v.handoff();
                r.aggregate = v.aggregate();
                r.open = v.open();
                r.cancelled = v.cancelled();
                r.neverStarted = v.neverStarted();
                r.waiting = v.waiting();
                r.misuse = v.misuse();
                r.healed = v.healed();
                r.count = v.count();
                r.dur = v.durationNanos();
                r.waitMax = v.waitMaxNanos();
                r.cpu = v.cpuNanos();
                for (int i = 0; i < v.attributes(); i++) r.attrs.put(v.attributeKey(i), v.longAttribute(i));
                rows.add(r);
            });
            return rows;
        }
    }
}
