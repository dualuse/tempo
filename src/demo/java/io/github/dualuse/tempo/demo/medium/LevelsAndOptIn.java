package io.github.dualuse.tempo.demo.medium;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.util.Arrays;
import java.util.Locale;

/**
 * A search service that traces every request in production, coarsely, and records more on request:
 * {@code tempo=1} asks for detail, {@code tempo=2} for deep detail. The filter in front of the handler
 * starts every trace at {@code SPAN} before anyone has read the request; the handler reads the opt-in
 * and widens what the trace records once, before its first step. The same handler serves three
 * requests, one per level.
 *
 * <p>It shows {@code span} (always drawn) next to {@code step} (always timed, drawn from {@code STEP})
 * and {@code tick} (recorded only at {@code TICK}, else {@code NOOP} at the cost of a branch), {@code
 * Trace.records} and when it is refused, {@code t.records()} guarding work done only for an attribute,
 * and a hot loop of 5,000 {@code score} steps that folds into one aggregate once the trace's budget is
 * spent.
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=LevelsAndOptIn                     the three requests, then the TICK response
 * ./gradlew -q demo -Pdemo=LevelsAndOptIn | tools/tempo --tree   or --agg, or open the printed Tempo-Link
 * </pre>
 *
 * Look for: the header size per level in the story, {@code fetch} rows (detail) with a {@code decode}
 * below each (deep), the first few {@code score} rows and then one aggregate {@code score ×N} under
 * {@code rank} ({@code --agg} shows its count, sum, min and max), {@code x=} in the header with the
 * number folded, and {@code p99} on {@code rank}, which a coarse request does not compute.
 */
public final class LevelsAndOptIn {
    private LevelsAndOptIn() {
    }

    static final Tempo.Name SEARCH = Tempo.name("GET /search"), PARSE = Tempo.name("parse"),
            LOAD = Tempo.name("load"), FETCH = Tempo.name("fetch"), DECODE = Tempo.name("decode"),
            RANK = Tempo.name("rank"), SCORE = Tempo.name("score"), ENCODE = Tempo.name("encode"),
            LEVEL = Tempo.name("level"), TERMS = Tempo.name("terms"), SHARD = Tempo.name("shard"),
            CANDIDATES = Tempo.name("candidates"), P99 = Tempo.name("p99");

    static final int SHARDS = 3, ITERATIONS = 5_000;

    // How many detail and deep spans a trace records one by one; past that they fold. The default is 512;
    // a small budget makes the folding visible after a dozen rows.
    static final int BUDGET = 12;

    public static void main(String[] args) throws Exception {
        Tempo.Trace coarse = serve("q=desk+lamp");
        Tempo.Trace detail = serve("q=desk+lamp&tempo=1");
        Tempo.Trace deep = serve("q=desk+lamp&tempo=2");

        // The same code, three shapes.
        Demo.check(coarse.records() == Tempo.SPAN && detail.records() == Tempo.STEP && deep.records() == Tempo.TICK,
                "no opt-in stays SPAN, tempo=1 raises to STEP, tempo=2 to TICK");
        Demo.check(Demo.count(coarse, v -> v.level() != Tempo.SPAN) == 0, "the coarse trace has no detail or deep rows");
        Demo.check(Demo.count(coarse, v -> v.depth() == 1) == 4 && Demo.count(coarse, SCORE) == 0,
                "the coarse trace is parse, load, rank and encode, without a score row");
        Demo.check(Demo.count(detail, v -> v.level() == Tempo.STEP) > 0
                && Demo.count(detail, v -> v.level() == Tempo.TICK) == 0, "the detail trace has detail rows but no deep rows");
        Demo.check(Demo.count(detail, FETCH) == SHARDS && Demo.count(detail, DECODE) == 0, "detail: a fetch per shard, no decode");
        Demo.check(Demo.count(deep, v -> v.level() == Tempo.STEP) > 0
                && Demo.count(deep, v -> v.level() == Tempo.TICK) > 0, "the deep trace has detail and deep rows");
        Demo.check(Demo.count(deep, DECODE) == SHARDS, "deep: a decode under every fetch");
        Demo.check(rankHas(coarse, CANDIDATES) && !rankHas(coarse, P99), "coarse: rank has candidates, but p99 was not computed");
        Demo.check(rankHas(detail, P99) && rankHas(deep, P99), "detail and deep: rank has p99");
        String thread = Thread.currentThread().getName();
        for (Tempo.Trace t : new Tempo.Trace[] {coarse, detail, deep})
            Demo.check(Demo.count(t, v -> !thread.equals(v.thread()) || v.misuse() || v.healed()) == 0,
                    "every span ran on " + thread + " and was used correctly");
        checkFolding(detail);
        checkFolding(deep);
        Demo.check(Demo.folded(coarse.toHeader()) == 0 && coarse.total(SCORE).count() == ITERATIONS,
                "a coarse trace folds nothing: its score steps are timed, not drawn");
        Demo.check(coarse.toHeader().length() < detail.toHeader().length()
                && coarse.toHeader().length() < deep.toHeader().length(), "detail costs header bytes; coarse is the smallest");

        Demo.say("Three requests through the same handler (Tempo-Timing bytes, records, score rows, folded):");
        story("q=desk+lamp          ", coarse);
        story("q=desk+lamp&tempo=1  ", detail);
        story("q=desk+lamp&tempo=2  ", deep);
        Demo.say("The budget caps the rows: deep spends some of it on decode, leaving fewer score rows; about the same size.");

        lateGate();

        Demo.say("The response to the tempo=2 request:");
        Demo.respond(deep);
    }

    /** The filter in front of every handler: it starts the trace before anything has read the request. */
    static Tempo.Trace serve(String query) {
        // SPAN (the default level): cheap enough for every request in production. A later widening records
        // steps, and the root's CPU time from then on; pass Options.cpu(true) to measure it from the start.
        Tempo.Trace t = Tempo.trace(SEARCH, new Tempo.Options().strict(true).budget(BUDGET));
        try (t) {
            search(t, query);
        }
        return t;
    }

    /** The handler. Nothing in it depends on the level except the gate at the top. */
    @SuppressWarnings("try") // some spans are opened only to time their block and are never named in it
    static void search(Tempo.Trace t, String query) {
        Tempo.Span s = t;
        // The gate comes first: records(level) works once, and only before the first step or tick. Asking
        // for SPAN on a SPAN trace changes nothing, so the gate needs no if.
        t.records(levelAsked(query));
        s.set(LEVEL, t.records());

        try (Tempo.Span p = s.span(PARSE)) {
            Demo.cpu(200);
            p.set(TERMS, param(query, "q").split("\\+").length);
        }

        try (Tempo.Span l = s.span(LOAD)) {
            for (int shard = 0; shard < SHARDS; shard++) {
                // Below STEP a fetch is timed, not drawn; below TICK a decode is a NOOP that costs a branch.
                try (Tempo.Span f = l.step(FETCH)) {
                    f.set(SHARD, shard);
                    Demo.io(2);
                    try (Tempo.Span d = f.tick(DECODE)) {
                        Demo.cpu(100);
                    }
                }
            }
        }

        int[] scores = new int[ITERATIONS];
        try (Tempo.Span r = s.span(RANK)) {
            for (int i = 0; i < ITERATIONS; i++) {
                // A hot loop: within the budget each score is a row, past it they all go into one aggregate.
                try (Tempo.Span sc = r.step(SCORE)) {
                    scores[i] = (int) Long.remainderUnsigned(Demo.cpu(1), 1000);
                }
            }
            r.set(CANDIDATES, ITERATIONS);
            // Sorting 5,000 scores is work done only for an attribute: a coarse request skips it.
            if (t.records() >= Tempo.STEP) r.set(P99, p99(scores));
        }

        s.run(ENCODE, () -> Demo.cpu(150));
    }

    /** The opt-in. A real service would honour it only for trusted callers: detail costs time and bytes. */
    static int levelAsked(String query) {
        String tempo = param(query, "tempo");
        return "2".equals(tempo) ? Tempo.TICK : "1".equals(tempo) ? Tempo.STEP : Tempo.SPAN;
    }

    /**
     * The gate in the wrong place. Not strict, so that the refused widening is counted instead of thrown.
     * A step opened before it fixes what the trace draws: steps are always timed, and their totals must
     * not change shape halfway, so the widening is refused and the trace keeps recording spans only.
     */
    @SuppressWarnings("try")
    static void lateGate() {
        Tempo.Trace t = Tempo.trace(SEARCH);
        try (t) {
            Tempo.Span s = t;
            try (Tempo.Span f = s.step(FETCH)) {   // still SPAN: timed, not drawn
                Demo.io(1);
            }
            t.records(Tempo.STEP);                 // refused: a step has opened (a misuse)
            try (Tempo.Span f = s.step(FETCH)) {   // timed, not drawn either
                Demo.io(1);
            }
        }
        Demo.check(t.records() == Tempo.SPAN, "records(STEP) after the first step is refused, and the level stays");
        Demo.check(Demo.count(t, FETCH) == 0 && t.total(FETCH).count() == 2,
                "neither fetch is drawn, and both are in total(fetch)");

        Demo.say("A gate placed late: records(STEP) after a step -> the trace still records "
                + (t.records() == Tempo.SPAN ? "SPAN" : "more") + " (a misuse: strict(true) would throw an AssertionError);");
        Demo.say("  both fetches are timed in total(fetch), neither is drawn. Widen first.");
    }

    // ------------------------------------------------------------------ reading the traces back

    /** The score loop past the budget: one aggregate under rank, its count adding up to the loop. */
    private static void checkFolding(Tempo.Trace t) {
        long[] rankId = {-1}, rows = {0}, aggregates = {0}, aggCount = {0}, aggParent = {-2}, budgeted = {0};
        boolean[] stats = {false};
        t.forEachSpan(v -> {
            if (v.name() == RANK) rankId[0] = v.id();
            if (v.level() != Tempo.SPAN && !v.aggregate()) budgeted[0]++;
            if (v.name() != SCORE) return;
            if (!v.aggregate()) {
                rows[0]++;
                return;
            }
            aggregates[0]++;
            aggCount[0] = v.count();
            aggParent[0] = v.parent();
            stats[0] = v.minNanos() <= v.maxNanos() && v.maxNanos() <= v.durationNanos();
        });
        String level = t.records() == Tempo.TICK ? "deep: " : "detail: ";
        Demo.check(budgeted[0] == BUDGET, level + "exactly the budget of " + BUDGET + " detail and deep spans are rows");
        Demo.check(aggregates[0] == 1 && aggParent[0] == rankId[0], level + "the folded scores are one aggregate under rank");
        Demo.check(rows[0] + aggCount[0] == ITERATIONS, level + "score rows plus the aggregate's count make " + ITERATIONS);
        Demo.check(stats[0], level + "the aggregate keeps min <= max <= sum");
        Demo.check(Demo.folded(t.toHeader()) == aggCount[0], level + "x= in the header counts the folded scores");
        Demo.check(t.total(SCORE).count() == ITERATIONS, level + "Trace.total(score) counts rows and folded alike");
    }

    private static void story(String query, Tempo.Trace t) {
        String header = t.toHeader();
        String[] levels = {"SPAN", "STEP", "TICK  "};
        Demo.say(String.format(Locale.ROOT, "  %s %s %5d bytes %3d records %2d score rows %5d folded", query, levels[t.records()],
                header.length(), Demo.count(t, v -> true), Demo.count(t, v -> v.name() == SCORE && !v.aggregate()),
                Demo.folded(header)));
    }

    private static boolean rankHas(Tempo.Trace t, Tempo.Name key) {
        return Demo.count(t, v -> {
            if (v.name() != RANK) return false;
            for (int i = 0; i < v.attributes(); i++) if (v.attributeKey(i) == key) return true;
            return false;
        }) == 1;
    }

    private static int p99(int[] scores) {
        int[] sorted = scores.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length * 99 / 100];
    }

    private static String param(String query, String key) {
        for (String kv : query.split("&")) if (kv.startsWith(key + "=")) return kv.substring(key.length() + 1);
        return null;
    }
}
