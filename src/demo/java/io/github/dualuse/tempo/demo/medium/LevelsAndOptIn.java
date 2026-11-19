package io.github.dualuse.tempo.demo.medium;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.util.Arrays;
import java.util.Locale;

/**
 * A search service that traces every request in production, coarsely, and records more on request:
 * {@code tempo=1} asks for steps, {@code tempo=2} for ticks too. The filter in front of the handler
 * starts every trace at {@code SPAN} before it has read anything; once it has read the opt-in, it
 * widens what the trace records, once, before the handler opens its first step. The same handler
 * serves three requests, one per level.
 *
 * <p>It shows {@code Tempo.span} (always timed and drawn) next to {@code Tempo.step} (always timed,
 * drawn from {@code STEP}) and {@code Tempo.tick} (timed and drawn only at {@code TICK}, else {@code
 * NOOP} at the cost of a branch), {@code Trace.records} and when it is refused, the level guarding work
 * done only for an attribute, a hot loop of 5,000 {@code score} steps that folds into one aggregate once
 * the trace's budget is spent, and {@code t.total(score)}, which counts every score whatever the trace
 * drew. The hot loop uses a {@code Tempo.name} constant, which needs no lookup per span.
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=LevelsAndOptIn                     the three requests, then the TICK response
 * ./gradlew -q demo -Pdemo=LevelsAndOptIn | tools/tempo --tree   or --agg, or open the printed Tempo-Link
 * </pre>
 *
 * Look for: the header size per level in the story, {@code fetch} rows (from {@code STEP}) with a
 * {@code decode} below each (from {@code TICK}), the first few {@code score} rows and then one aggregate {@code score ×N} under
 * {@code rank} ({@code --agg} shows its count, sum, min and max), {@code x=} in the header with the
 * number folded, and {@code p99} on {@code rank}, which a request without opt-in does not compute.
 */
@SuppressWarnings("try") // blocks that only time their work never name the span; see src/demo/README.md
public final class LevelsAndOptIn {
    private LevelsAndOptIn() {
    }

    static final Tempo.Name SEARCH = Tempo.name("GET /search"), PARSE = Tempo.name("parse"),
            LOAD = Tempo.name("load"), FETCH = Tempo.name("fetch"), DECODE = Tempo.name("decode"),
            RANK = Tempo.name("rank"), SCORE = Tempo.name("score"), ENCODE = Tempo.name("encode"),
            LEVEL = Tempo.name("level"), TERMS = Tempo.name("terms"), SHARD = Tempo.name("shard"),
            CANDIDATES = Tempo.name("candidates"), P99 = Tempo.name("p99");

    static final int SHARDS = 3, ITERATIONS = 5_000;

    // How many steps and ticks a trace draws one by one; past that they fold. The default is 512;
    // a small budget makes the folding visible after a dozen rows.
    static final int BUDGET = 12;

    public static void main(String[] args) throws Exception {
        Tempo.Trace spansOnly = serve("q=desk+lamp");
        Tempo.Trace withSteps = serve("q=desk+lamp&tempo=1");
        Tempo.Trace withTicks = serve("q=desk+lamp&tempo=2");

        // The same code, three shapes.
        Demo.check(spansOnly.records() == Tempo.SPAN && withSteps.records() == Tempo.STEP && withTicks.records() == Tempo.TICK,
                "no opt-in stays SPAN, tempo=1 widens to STEP, tempo=2 to TICK");
        Demo.check(Demo.count(spansOnly, v -> v.level() != Tempo.SPAN) == 0, "the spans-only trace has no step or tick rows");
        Demo.check(Demo.count(spansOnly, v -> v.depth() == 1) == 4 && Demo.count(spansOnly, SCORE) == 0,
                "the spans-only trace is parse, load, rank and encode, without a score row");
        Demo.check(Demo.count(withSteps, v -> v.level() == Tempo.STEP) > 0
                && Demo.count(withSteps, v -> v.level() == Tempo.TICK) == 0, "the STEP trace has step rows but no tick rows");
        Demo.check(Demo.count(withSteps, FETCH) == SHARDS && Demo.count(withSteps, DECODE) == 0, "STEP: a fetch per shard, no decode");
        Demo.check(Demo.count(withTicks, v -> v.level() == Tempo.STEP) > 0
                && Demo.count(withTicks, v -> v.level() == Tempo.TICK) > 0, "the TICK trace has step and tick rows");
        Demo.check(Demo.count(withTicks, DECODE) == SHARDS, "TICK: a decode under every fetch");
        Demo.check(rankHas(spansOnly, CANDIDATES) && !rankHas(spansOnly, P99), "SPAN: rank has candidates, but p99 was not computed");
        Demo.check(rankHas(withSteps, P99) && rankHas(withTicks, P99), "STEP and TICK: rank has p99");
        String thread = Thread.currentThread().getName();
        for (Tempo.Trace t : new Tempo.Trace[] {spansOnly, withSteps, withTicks})
            Demo.check(Demo.count(t, v -> !thread.equals(v.thread()) || v.misuse() || v.healed()) == 0,
                    "every span ran on " + thread + " and was used correctly");
        checkFolding(withSteps);
        checkFolding(withTicks);
        Demo.check(Demo.folded(spansOnly.toHeader()) == 0 && spansOnly.total(SCORE).count() == ITERATIONS,
                "a spans-only trace folds nothing: its score steps are timed, not drawn");
        for (Tempo.Trace t : new Tempo.Trace[] {spansOnly, withSteps, withTicks}) {
            Tempo.Total score = t.total(SCORE);
            Demo.check(score.mean() == score.sum() / ITERATIONS && score.min() <= score.mean() && score.mean() <= score.max(),
                    "total(score).mean() is the sum over all " + ITERATIONS + " scores, drawn or not, divided by their count");
        }
        Demo.check(spansOnly.total(DECODE).count() == 0 && withSteps.total(DECODE).count() == 0
                        && withTicks.total(DECODE).count() == SHARDS,
                "a tick below TICK is not even timed: only the TICK trace counts its decodes");
        Demo.check(spansOnly.toHeader().length() < withSteps.toHeader().length()
                && spansOnly.toHeader().length() < withTicks.toHeader().length(), "steps cost header bytes; the spans-only trace is the smallest");

        Demo.say("Three requests through the same handler (Tempo-Timing bytes, records, score rows, folded):");
        story("q=desk+lamp          ", spansOnly);
        story("q=desk+lamp&tempo=1  ", withSteps);
        story("q=desk+lamp&tempo=2  ", withTicks);
        Demo.say("The budget caps the rows: TICK spends some of it on decode, leaving fewer score rows; about the same size.");
        Demo.say(String.format(Locale.ROOT, "Every trace counts all %d scores, drawn or not: mean %.1f us (SPAN), %.1f us (STEP), %.1f us (TICK).",
                ITERATIONS, spansOnly.total(SCORE).mean() / 1e3, withSteps.total(SCORE).mean() / 1e3, withTicks.total(SCORE).mean() / 1e3));

        lateGate();

        Demo.say("The response to the tempo=2 request:");
        Demo.respond(withTicks);
    }

    /** The filter in front of every handler: it starts the trace before anything has read the request. */
    static Tempo.Trace serve(String query) {
        // SPAN (the default level): cheap enough for every request in production. A later widening records
        // steps, and the root's CPU time from then on; pass Options.cpu(true) to measure it from the start.
        Tempo.Trace t = Tempo.trace(SEARCH, new Tempo.Options().strict(true).budget(BUDGET));
        try (t) {
            // The gate comes first: records(level) works once, and only before the first step or tick.
            // Asking for SPAN on a SPAN trace changes nothing, so the gate needs no if.
            t.records(levelAsked(query));
            t.set(LEVEL, t.records());
            search(query, t.records());
        }
        return t;
    }

    /** The handler. Nothing in it depends on the level except the p99, which only a request that opted in pays for. */
    static void search(String query, int level) {
        try (Tempo.Span p = Tempo.span(PARSE)) {
            Demo.cpu(200);
            p.set(TERMS, param(query, "q").split("\\+").length);
        }

        Tempo.run(LOAD, () -> {
            for (int shard = 0; shard < SHARDS; shard++) {
                // Below STEP a fetch is timed, not drawn; below TICK a decode is a NOOP that costs a branch.
                try (Tempo.Span f = Tempo.step(FETCH)) {
                    f.set(SHARD, shard);
                    Demo.io(2);
                    decode();
                }
            }
        });

        int[] scores = new int[ITERATIONS];
        try (Tempo.Span r = Tempo.span(RANK)) {
            for (int i = 0; i < ITERATIONS; i++) {
                // A hot loop: within the budget each score is a row, past it they all go into one aggregate.
                // SCORE is a Tempo.name constant, so the loop looks nothing up.
                try (Tempo.Span sc = Tempo.step(SCORE)) {
                    scores[i] = (int) Long.remainderUnsigned(Demo.cpu(1), 1000);
                }
            }
            r.set(CANDIDATES, ITERATIONS);
            // Sorting 5,000 scores is work done only for an attribute: a request without opt-in skips it.
            if (level >= Tempo.STEP) r.set(P99, p99(scores));
        }

        Tempo.run(ENCODE, () -> Demo.cpu(150));
    }

    /** Below TICK, Tempo.tick returns NOOP: the block runs, and nothing is timed or drawn. */
    static void decode() {
        try (Tempo.Span d = Tempo.tick(DECODE)) {
            Demo.cpu(100);
        }
    }

    /** The opt-in. A real service would honour it only for trusted callers: steps cost time and bytes. */
    static int levelAsked(String query) {
        String tempo = param(query, "tempo");
        return "2".equals(tempo) ? Tempo.TICK : "1".equals(tempo) ? Tempo.STEP : Tempo.SPAN;
    }

    /**
     * The gate in the wrong place. Not strict, so that the refused widening is counted instead of thrown.
     * A step opened before it fixes what the trace draws: steps are always timed, and their totals must
     * not change shape halfway, so the widening is refused and the trace keeps recording spans only.
     */
    static void lateGate() {
        Tempo.Trace t = Tempo.trace(SEARCH);
        try (t) {
            try (Tempo.Span f = Tempo.step(FETCH)) {   // still SPAN: timed, not drawn
                Demo.io(1);
            }
            t.records(Tempo.STEP);                     // refused: a step has opened (a misuse)
            try (Tempo.Span f = Tempo.step(FETCH)) {   // timed, not drawn either
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
        String level = t.records() == Tempo.TICK ? "TICK: " : "STEP: ";
        Demo.check(budgeted[0] == BUDGET, level + "exactly the budget of " + BUDGET + " steps and ticks are rows");
        Demo.check(aggregates[0] == 1 && aggParent[0] == rankId[0], level + "the folded scores are one aggregate under rank");
        Demo.check(rows[0] + aggCount[0] == ITERATIONS, level + "score rows plus the aggregate's count make " + ITERATIONS);
        Demo.check(stats[0], level + "the aggregate keeps min <= max <= sum");
        Demo.check(Demo.folded(t.toHeader()) == aggCount[0], level + "x= in the header counts the folded scores");
        Demo.check(t.total(SCORE).count() == ITERATIONS, level + "Trace.total(score) counts rows and folded alike");
    }

    private static void story(String query, Tempo.Trace t) {
        String header = t.toHeader();
        String[] levels = {"SPAN", "STEP", "TICK"};
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
