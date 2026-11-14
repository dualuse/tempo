package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.Handoff;
import io.github.dualuse.tempo.Tempo.Name;
import io.github.dualuse.tempo.Tempo.Options;
import io.github.dualuse.tempo.Tempo.Snapshot;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.SpanView;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

import static io.github.dualuse.tempo.Tempo.Header.MAX_DEPTH;
import static io.github.dualuse.tempo.Tempo.NOOP;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code Tempo-Timing} encoder: names, numbers, the cases the vectors do not cover, the byte limit,
 * and round trips of random recordings through the test-only {@link WireDecoder}, whole and cut.
 */
class HeaderTest {

    static final Name SEARCH = TempoTest.SEARCH, LOAD = TempoTest.LOAD, MATCH = TempoTest.MATCH,
            DECODE = TempoTest.DECODE, BYTES = TempoTest.BYTES, SCORE = TempoTest.SCORE,
            RATIO = Tempo.name("ratio"), NAN = Tempo.name("nan");

    final TempoTest.Clock clock = new TempoTest.Clock();

    Options opts() {
        return new Options().clock(clock).wallClock(() -> 1794567890123L).headerBytes(1 << 16);
    }

    @AfterEach
    void noTraceLeftOnThread() {
        assertSame(NOOP, Tempo.current(), "a test left a span open on the test thread");
    }

    // ---- names and numbers ------------------------------------------------------------------------

    static String name(String s) {
        StringBuilder b = new StringBuilder();
        Tempo.Header.name(b, s);
        return b.toString();
    }

    static String number(double v) {
        StringBuilder b = new StringBuilder();
        Tempo.Header.number(b, v);
        return b.toString();
    }

    @Test
    void namesArePercentEncodedUtf8() {
        String[][] cases = {
                {"GET /search", "GET_%2Fsearch"}, {"snake_case", "snake%5Fcase"}, {"a.b-c", "a.b-c"},
                {"100%", "100%25"}, {"x:y;z/w=v,\"\\", "x%3Ay%3Bz%2Fw%3Dv%2C%22%5C"}, {"Größe", "Gr%C3%B6%C3%9Fe"},
                {"日本語", "%E6%97%A5%E6%9C%AC%E8%AA%9E"}, {"🚀 launch", "%F0%9F%9A%80_launch"},
                {"?", "%3F"}, {"", "%3F"}, {null, "%3F"}, {"a\uD800b", "a%3Fb"}, {"\uDC00", "%3F"}, {"\uD83D", "%3F"},
                {"\u0000\u007f\u0080߿ࠀ￿", "%00%7F%C2%80%DF%BF%E0%A0%80%EF%BF%BF"}};
        for (String[] c : cases) assertEquals(c[1], name(c[0]), c[0]);
    }

    /** Whatever the string, the decoder gets back what UTF-8 encoding gives (an unpaired surrogate as {@code ?}). */
    @Test
    void randomNamesRoundTrip() {
        SplittableRandom rnd = new SplittableRandom(7);
        for (int i = 0; i < 5000; i++) {
            StringBuilder b = new StringBuilder();
            for (int n = 1 + rnd.nextInt(12); n > 0; n--) {
                int kind = rnd.nextInt(5);
                if (kind == 0) b.append((char) rnd.nextInt(0x80));
                else if (kind == 1) b.append((char) rnd.nextInt(0x800));
                else if (kind == 2) b.append((char) rnd.nextInt(0x10000)); // may be a lone surrogate
                else if (kind == 3) b.appendCodePoint(0x10000 + rnd.nextInt(0x100000));
                else b.append(" _.-%:/".charAt(rnd.nextInt(7)));
            }
            String s = b.toString(), wire = name(s);
            assertTrue(wire.matches("(?:[A-Za-z0-9._-]|%[0-9A-F]{2})+"), wire);
            assertEquals(new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8), WireDecoder.name(wire));
        }
    }

    @Test
    void numbersAreFixedPointWithSixRoundedDigits() {
        Object[][] cases = {
                {0.5, "0.5"}, {-0.25, "-0.25"}, {1e-6, "0.000001"}, {123456.789, "123456.789"}, {-3.141593, "-3.141593"},
                {2.0, "2"}, {-2.0, "-2"}, {0.0, "0"}, {-0.0, "0"}, {1e-7, "0"}, {-1e-7, "0"}, {-4e-7, "0"},
                {0.0078125, "0.007812"}, {0.0234375, "0.023438"},   // exact halves: to even
                {5e-7, "0"}, {2.5e-6, "0.000003"}, {1.0000005, "1.000001"}, {0.1234565, "0.123456"}, // binary value decides
                {0.1, "0.1"}, {1.0 / 3, "0.333333"}, {-2.0 / 3, "-0.666667"}, {0.9999996, "1"}, {-0.9999996, "-1"},
                {12345678901.5, "12345678901.5"}, {1e20, "100000000000000000000"}, {4611686018427387904.0, "4611686018427387904"},
                {9007199254740991.0, "9007199254740991"}, {Double.MIN_VALUE, "0"}, {-Double.MAX_VALUE, new BigDecimal(-Double.MAX_VALUE).toBigInteger().toString()}};
        for (Object[] c : cases) assertEquals(c[1], number((Double) c[0]), String.valueOf(c[0]));
    }

    /** The six-digit rounding is exact: the same as {@link BigDecimal} on the binary value, half to even. */
    @Test
    void numbersMatchExactRounding() {
        SplittableRandom rnd = new SplittableRandom(11);
        for (int i = 0; i < 200_000; i++) {
            double v;
            int kind = rnd.nextInt(4);
            if (kind == 0) v = Double.longBitsToDouble(rnd.nextLong());                   // anything
            else if (kind == 1) v = (rnd.nextLong(2_000_000_000L) - 1_000_000_000L) / 2e6; // near halves
            else if (kind == 2) v = rnd.nextDouble() * Math.pow(10, rnd.nextInt(-8, 16)) * (rnd.nextBoolean() ? 1 : -1);
            else v = (rnd.nextLong(1L << 40) + 0.5) / (1L << rnd.nextInt(30));          // exact binary fractions
            if (!Double.isFinite(v)) continue;
            BigDecimal d = new BigDecimal(v).setScale(6, RoundingMode.HALF_EVEN);
            String want = d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
            assertEquals(want, number(v), () -> Double.toString(v));
        }
    }

    @Test
    void base36() {
        long[] values = {0, 1, 35, 36, 1295, 1296, 184200, 1794567890123L, Long.MAX_VALUE};
        for (long v : values) assertEquals(Long.toString(v, 36), Tempo.Header.b36(new StringBuilder(), v).toString());
        assertEquals("0", Tempo.Header.b36(new StringBuilder(), -5).toString());
    }

    // ---- records ----------------------------------------------------------------------------------

    @Test
    void attributesKeepTheFirstPositionAndTheLastValueAndDropNonFiniteOnes() {
        Trace t = Tempo.start(SEARCH, opts());
        t.root().set(BYTES, 1).set(RATIO, 2.5).set(BYTES, 3).set(NAN, Double.NaN).set(SCORE, 1.0).set(SCORE, Double.POSITIVE_INFINITY);
        clock.add(5000);
        t.close();
        assertEquals("v1;w=mwevdy2z;x=0.2;T=" + name(Thread.currentThread().getName()) + ";N=search:bytes:ratio;"
                + "S=00.0.5:1=3:2=2.5;e=1", t.toHeader());
    }

    /** Caller-runs: a handoff begun at once in the submitter's own lane is a plain child; later, it keeps its wait. */
    @Test
    void inlineHandoffWithoutWaitIsAPlainChild() {
        Trace t = Tempo.start(SEARCH, opts());
        Span root = t.root();
        clock.add(1000);
        Handoff now = root.handoff(LOAD);
        clock.add(999);
        Span a = now.begin();
        clock.add(2000);
        a.close();
        Handoff later = root.handoff(MATCH);
        clock.add(3000);
        Span b = later.begin();
        clock.add(1000);
        b.close();
        t.close();
        assertTrue(t.toHeader().endsWith(";S=00.0.7/11.1.2/12.2.1~3;e=3"), t.toHeader());
    }

    /** A task cancelled before it began nominally sits on its parent's thread, whichever thread cancelled it. */
    @Test
    void taskCancelledBeforeItBeganSitsOnItsParentsThread() throws InterruptedException {
        Trace t = Tempo.start(SEARCH, opts());
        clock.add(1000);
        Handoff h = t.root().handoff(LOAD);
        clock.add(4000);
        assertNull(TempoTest.onThread("canceller", h::cancel));
        clock.add(1000);
        t.close();
        assertEquals("v1;w=mwevdy2z;T=" + name(Thread.currentThread().getName()) + ";N=search:load;S=00.0.6/11.1.0~4^c;e=2",
                t.toHeader());
    }

    /**
     * Wire depth adds up along handoffs, so it can pass 35 although each lane stops at 34: the records
     * below depth 35 are left out with their subtrees and counted as dropped.
     */
    @Test
    void recordsDeeperThan35AreDropped() throws InterruptedException {
        Trace t = Tempo.start(SEARCH, opts());
        Span s = t.root();
        for (int d = 1; d < 34; d++) s = s.span(LOAD);
        Handoff h = s.handoff(MATCH); // depth 34
        Handoff[] next = new Handoff[1];
        assertNull(TempoTest.onThread("worker", () -> {
            try (Span m = h.begin(); Span x = m.span(DECODE)) { // depths 34 and 35
                next[0] = x.handoff(MATCH);                        // depth 36: left out
                x.span(SCORE).close();                             // depth 36: left out
            }
        }));
        assertNull(TempoTest.onThread("worker-2", () -> next[0].begin().span(LOAD).close())); // 36 and 37
        t.close();
        Map<String, Object> got = WireDecoder.decode(t.toHeader());
        assertEquals(3L, got.get("dropped"));
        assertEquals(36, ((List<?>) got.get("spans")).size());
        assertEquals(List.of(Thread.currentThread().getName(), "worker"), got.get("threads"));
    }

    /** Over the limit only the root and its children remain, with the threads and names they use and no attributes. */
    @Test
    void overTheLimitTheRootAndItsChildrenRemain() throws InterruptedException {
        Trace t = Tempo.start(SEARCH, opts().headerBytes(300));
        Span root = t.root().set(BYTES, 7);
        clock.add(1000);
        Span d = root.span(DECODE).set(SCORE, 0.5);
        for (int i = 0; i < 40; i++) {
            clock.add(1000);
            d.span(LOAD).close();
        }
        d.close();
        Handoff h = root.handoff(MATCH);
        assertNull(TempoTest.onThread("worker", () -> {
            clock.add(500);
            try (Span m = h.begin()) {
                clock.add(1000);
                m.span(SCORE).fail().close();
            }
        }));
        clock.add(10_000);
        t.close();
        String main = name(Thread.currentThread().getName());
        assertEquals("v1;w=mwevdy2z;x=0.15;T=" + main + ":worker;N=search:decode:match;S=00.0.1g/11.1.14/12.14.1@1~0;e=3",
                t.toHeader()); // 41 records left out
        assertEquals(44, ((List<?>) WireDecoder.decode(Tempo.Header.of(t.snapshot(), 1 << 16)).get("spans")).size());
    }

    /** An aggregate nested in another one is left out: its spans count as dropped and no longer as folded. */
    @Test
    void aggregateNestedInAnAggregateCountsAsDroppedOnly() {
        Name marked = Tempo.name("marked-only").aggregate();
        Trace t = Tempo.start(SEARCH, opts().level(Tempo.DETAIL).budget(0));
        Span root = t.root();
        for (int i = 0; i < 3; i++) {
            clock.add(1000);
            try (Span b = root.detail(LOAD)) {
                for (int j = 0; j < 2; j++) {
                    clock.add(1000);
                    b.detail(MATCH).close();
                }
                b.deep(marked).close(); // aggregate-only (below DEEP), not folded: left out, never counted as folded
            }
        }
        clock.add(1000);
        t.close();
        assertEquals(9, t.snapshot().folded());
        String h = t.toHeader();
        assertTrue(h.contains(";x=3.9;"), h); // 3 load on the wire; 6 match and 3 marked-only left out
        assertEquals(List.of(3), aggregates(h));
    }

    /** At ladder step 4 an aggregate below depth 1 is left out with the spans: folded drops to 0. */
    @Test
    void aggregateLeftOutAtTheLastLadderStepCountsAsDroppedOnly() {
        Trace t = Tempo.start(SEARCH, opts().level(Tempo.DETAIL).budget(0).cpu(false).headerBytes(256));
        Span root = t.root();
        clock.add(1000);
        try (Span d = root.span(DECODE)) {
            for (int i = 0; i < 40; i++) {
                clock.add(1000);
                d.span(LOAD).close();
            }
            for (int i = 0; i < 10; i++) {
                clock.add(1000);
                d.detail(SCORE).close();
            }
        }
        clock.add(1000);
        t.close();
        assertEquals(10, t.snapshot().folded());
        assertEquals("v1;w=mwevdy2z;x=0.1e;T=" + name(Thread.currentThread().getName()) + ";N=search:decode;S=00.0.1g/11.1.1e;e=2",
                t.toHeader()); // 40 spans and the aggregate of 10 left out
        Map<String, Object> fallback = WireDecoder.decode(Tempo.Header.of(t.snapshot(), 100));
        assertEquals(0L, fallback.get("folded"));
        assertEquals(51L, fallback.get("dropped"));
    }

    /** Counts of the aggregate records of a value, in order. */
    private static List<Integer> aggregates(String header) {
        List<Integer> counts = new ArrayList<>();
        for (Object o : (List<?>) WireDecoder.decode(header).get("spans")) {
            Map<?, ?> agg = (Map<?, ?>) ((Map<?, ?>) o).get("agg");
            if (agg != null) counts.add(((Long) agg.get("count")).intValue());
        }
        return counts;
    }

    @Test
    void releasedTraceRendersEmptyAndAnEarlierSnapshotStillRenders() {
        Trace t = Tempo.start(SEARCH, opts());
        clock.add(1500);
        t.close();
        Snapshot before = t.snapshot();
        String header = t.toHeader();
        t.release();
        assertEquals("", t.toHeader());
        assertEquals("", t.snapshot().toHeader());
        assertEquals(header, before.toHeader());
    }

    // ---- round trips of random recordings ---------------------------------------------------------

    static final Name[] NAMES = {LOAD, MATCH, DECODE, SCORE, Tempo.name("a b"), Tempo.name("x_y"), Tempo.name("Größe"),
            Tempo.name("🚀")};
    static final Name[] KEYS = {BYTES, RATIO, SCORE, Tempo.name("k k")};
    static final double[] DOUBLES = {0.5, -0.0, 1e-7, 0.0078125, 123.456789123, 2.0, 1e12 + 0.5, -3.25, Double.NaN,
            Double.POSITIVE_INFINITY, 1e20};
    static final long[] LONGS = {0, -1, 42, 9007199254740991L, Long.MAX_VALUE, Long.MIN_VALUE};

    /**
     * Random recordings on four threads (spans, detail and deep spans over a small budget, phases, laps,
     * attributes, flags, CPU, handoffs begun elsewhere, inline, cancelled or never) on a clock moving in
     * odd nanoseconds, rendered closed or while open: the decoder reads back exactly what the
     * {@link SpanView}s say, in microseconds; every limit of 256 bytes or more is kept; and every cut
     * of the value decodes to a prefix of its records, or is rejected as cut.
     */
    @Test
    void randomRecordingsRoundTrip() {
        SplittableRandom rnd = new SplittableRandom(20261113);
        Map<String, Integer> seen = new TreeMap<>();
        for (int round = 0; round < 300; round++) roundTrip(rnd, round, seen);
        for (String what : new String[] {"records", "agg", "wait", "@", "cpu", "attrs", "f", "c", "o", "n", "w", "m", "live", "dropped"}) {
            assertTrue(seen.getOrDefault(what, 0) > 10, () -> "too few " + seen);
        }
    }

    private void roundTrip(SplittableRandom rnd, int round, Map<String, Integer> seen) {
        try (GoldenTest.Script s = new GoldenTest.Script(1794567890123L + round, "http-1", "cpu 1", "cpu-2", "io_1")) {
            Options o = s.options().level(rnd.nextInt(3)).budget(rnd.nextInt(10)).cpu(rnd.nextBoolean());
            Trace t = s.at(0, () -> Tempo.start(SEARCH, o));
            List<List<Span>> stacks = new ArrayList<>();
            for (int i = 0; i < 4; i++) stacks.add(new ArrayList<>());
            stacks.get(0).add(t.root());
            List<Handoff> pending = new ArrayList<>();
            long[] cpu = {0};
            for (int step = 0, steps = rnd.nextInt(10, 400); step < steps; step++) {
                s.clock.add(rnd.nextInt(4) == 0 ? 0 : rnd.nextInt(1, 5000));
                s.cpu = rnd.nextInt(4) == 0 ? -1 : (cpu[0] += rnd.nextInt(3000));
                int k = rnd.nextInt(4);
                List<Span> stack = stacks.get(k);
                int op = rnd.nextInt(12);
                if (op >= 9) { // begin or cancel a pending handoff on thread k
                    if (pending.isEmpty()) continue;
                    Handoff h = pending.remove(rnd.nextInt(pending.size()));
                    if (op == 11) {
                        s.at(k, () -> {
                            h.cancel();
                            return null;
                        });
                    } else {
                        Span b = s.at(k, h::begin);
                        if (b != NOOP) stack.add(b);
                    }
                    continue;
                }
                if (stack.isEmpty()) continue;
                int i = rnd.nextInt(4) == 0 ? rnd.nextInt(stack.size()) : stack.size() - 1;
                Span h = stack.get(i);
                boolean frame = h.row == Tempo.Lane.FRAME;
                Name n = NAMES[rnd.nextInt(NAMES.length)], key = KEYS[rnd.nextInt(KEYS.length)];
                Span opened = s.at(k, () -> {
                    switch (op) {
                        case 0: return h.span(n);
                        case 1: return h.detail(n).cpu();
                        case 2: return h.deep(n);
                        case 3:
                            if (k == 0 && i == 0) return null;
                            h.close();
                            stack.subList(i, stack.size()).clear();
                            return null;
                        case 4:
                            if (rnd.nextBoolean()) h.set(key, LONGS[rnd.nextInt(LONGS.length)]);
                            else h.set(key, rnd.nextInt(3) == 0 ? rnd.nextDouble() * 1e4 : DOUBLES[rnd.nextInt(DOUBLES.length)]);
                            return null;
                        case 5:
                            int f = rnd.nextInt(4);
                            if (f == 0) h.fail();
                            else if (f == 1) h.cancel();
                            else if (f == 2) h.waiting();
                            else h.cpu();
                            return null;
                        case 6:
                        case 7:
                            if (frame) return null;
                            if (op == 6) h.phase(n);
                            else if (rnd.nextBoolean()) h.done(n);
                            else h.done(n, "label");
                            stack.subList(i + 1, stack.size()).clear();
                            return null;
                        default:
                            Handoff x = h.handoff(n);
                            if (rnd.nextBoolean()) x.set(key, rnd.nextInt(100));
                            pending.add(x);
                            return null;
                    }
                });
                if (opened != null && opened != NOOP) stack.add(opened);
            }
            boolean open = rnd.nextInt(4) == 0;
            if (!open) close(s, stacks, t);
            Snapshot snap = s.at(0, t::snapshot);
            String header = snap.toHeader();
            Map<String, Object> want = expected(snap), got = WireDecoder.decode(header);
            assertTrue(Json.same(want, got), () -> "round " + round + ": " + header + "\ngot:  " + Json.show(got)
                    + "\nwant: " + Json.show(want));

            for (int limit : new int[] {256, 300, 256 + rnd.nextInt(Math.max(1, header.length() - 254)), header.length()}) {
                String h = Tempo.Header.of(snap, limit);
                assertTrue(h.length() <= limit, () -> limit + ": " + h);
                assertFalse((Boolean) WireDecoder.decode(h).get("truncated"));
                if (limit >= header.length()) assertEquals(header, h);
            }
            for (int c = 0; c < 20; c++) assertCut(header, rnd.nextInt(header.length() + 1));
            if (open) close(s, stacks, t);
            s.at(0, () -> {
                t.release();
                return null;
            });
            count(got, seen);
        }
    }

    private static void count(Map<String, Object> doc, Map<String, Integer> seen) {
        if (!((List<?>) doc.get("live")).isEmpty()) seen.merge("live", 1, Integer::sum);
        if ((Long) doc.get("dropped") > 0) seen.merge("dropped", 1, Integer::sum);
        List<?> spans = (List<?>) doc.get("spans");
        for (Object o : spans) {
            Map<?, ?> span = (Map<?, ?>) o;
            seen.merge("records", 1, Integer::sum);
            for (String key : new String[] {"agg", "wait", "cpu", "attrs"}) if (span.containsKey(key)) seen.merge(key, 1, Integer::sum);
            Object flags = span.get("flags");
            if (flags != null) for (char f : ((String) flags).toCharArray()) seen.merge(String.valueOf(f), 1, Integer::sum);
            long parent = (Long) span.get("parent");
            if (parent >= 0 && !span.get("thread").equals(((Map<?, ?>) spans.get((int) parent)).get("thread"))) {
                seen.merge("@", 1, Integer::sum);
            }
        }
    }

    private static void close(GoldenTest.Script s, List<List<Span>> stacks, Trace t) {
        for (int k = 3; k >= 0; k--) {
            List<Span> stack = stacks.get(k);
            if (!stack.isEmpty()) s.at(k, () -> {
                stack.get(0).close();
                return null;
            });
            stack.clear();
        }
        s.at(0, () -> {
            t.close();
            return null;
        });
    }

    /** Cuts every golden value at every position. */
    @Test
    void cutGoldenValuesNeverYieldAFalseRecord() {
        for (String vector : new String[] {"search-example", "deep-nesting", "names", "handoff", "aggregates"}) {
            String v = GoldenTest.golden(vector);
            for (int cut = 0; cut <= v.length(); cut++) assertCut(v, cut);
        }
    }

    /** A value cut at {@code cut} is rejected as cut, or decodes to a prefix of its records, flagged truncated. */
    static void assertCut(String value, int cut) {
        Map<String, Object> full = WireDecoder.decode(value);
        Map<String, Object> got;
        try {
            got = WireDecoder.decode(value.substring(0, cut));
        } catch (WireDecoder.Rejected r) {
            assertTrue(r.code.equals("E_CUT") || r.code.equals("E_VERSION"), () -> cut + ": " + r.getMessage());
            assertTrue(cut < value.indexOf(";S=") + 3, () -> cut + " rejected after S=: " + r.getMessage());
            return;
        }
        List<?> all = (List<?>) full.get("spans"), some = (List<?>) got.get("spans");
        assertEquals(cut < value.length(), got.get("truncated"), () -> "cut at " + cut);
        assertTrue(some.size() <= all.size() && Json.same(all.subList(0, some.size()), some), () -> "cut at " + cut);
        assertTrue(cut == value.length() || some.size() < all.size(), () -> "cut at " + cut);
    }

    // ---- the expected JSON form, from the span views --------------------------------------------------

    /** One visited span, copied out of the reused view. */
    static final class Seen {
        int depth, lane, flags;
        String name;
        long start, dur, wait, cpu, count, min, max, extent, maxAt;
        boolean unrun, aggregate, waits, open, never;
        final Map<String, Object> attrs = new LinkedHashMap<>();
        int nonFinite;
    }

    /** The JSON form the value of {@code snap} should decode to, built from {@link Snapshot#forEachSpan}. */
    static Map<String, Object> expected(Snapshot snap) {
        List<Seen> seen = new ArrayList<>();
        int[] skip = {-1};
        long[] left = {0, 0}; // spans left out; folded occurrences of the aggregates among them
        boolean[] aggregate = new boolean[MAX_DEPTH + 1];
        snap.forEachSpan(v -> {
            int d = v.depth();
            if (skip[0] >= 0 && d > skip[0] || d > MAX_DEPTH || d == 0 && !seen.isEmpty() || d > 0 && aggregate[d - 1]) {
                if (skip[0] < 0 || d <= skip[0]) skip[0] = d;
                left[0] += v.count();
                left[1] += v.folded;
                return;
            }
            skip[0] = -1;
            aggregate[d] = v.aggregate();
            Seen x = new Seen();
            x.depth = d;
            x.name = v.name().toString();
            x.lane = v.shape.lane;
            x.flags = v.flags;
            x.unrun = (v.flags & Tempo.UNRUN) != 0;
            x.start = v.startNanos();
            x.dur = v.durationNanos();
            x.aggregate = v.aggregate();
            x.waits = v.handoff() && !v.aggregate() && !(v.inline && v.waitNanos() < 1000);
            x.wait = v.waitNanos();
            x.cpu = v.cpuNanos();
            x.open = v.open();
            x.never = v.neverStarted();
            x.count = v.count();
            x.min = v.minNanos();
            x.max = v.maxNanos();
            x.extent = v.extentNanos();
            x.maxAt = v.maxAtNanos();
            for (int i = 0; i < v.attributes(); i++) { // a repeated key: first position, last value
                Object value = v.attributeIsDouble(i) ? (Object) v.doubleAttribute(i) : (Object) v.longAttribute(i);
                x.attrs.put(v.attributeKey(i).toString(), value);
            }
            for (Map.Entry<String, Object> e : new ArrayList<>(x.attrs.entrySet())) {
                if (e.getValue() instanceof Double) {
                    double dv = (Double) e.getValue();
                    if (!Double.isFinite(dv)) {
                        x.attrs.remove(e.getKey());
                        x.nonFinite++;
                    } else {
                        e.setValue(new BigDecimal(dv).setScale(6, RoundingMode.HALF_EVEN).doubleValue());
                    }
                }
            }
            seen.add(x);
        });

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("v", 1L);
        doc.put("unit", "us");
        if (snap.wall > 0) doc.put("wall", snap.wall);
        long nonFinite = 0;
        for (Seen x : seen) nonFinite += x.nonFinite;
        doc.put("folded", snap.folded() - left[1]); // a left-out aggregate counts as dropped only
        doc.put("dropped", snap.dropped() + left[0] + nonFinite);
        TreeMap<Integer, Integer> lanes = new TreeMap<>();
        for (Seen x : seen) if (!x.unrun) lanes.put(x.lane, 0);
        List<String> threads = new ArrayList<>();
        List<Long> live = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : lanes.entrySet()) {
            e.setValue(threads.size());
            if (snap.shapes[e.getKey()].live) live.add((long) threads.size());
            threads.add(WireDecoder.name(name(snap.shapes[e.getKey()].thread)));
        }
        doc.put("threads", threads);
        doc.put("live", live);
        doc.put("truncated", false);

        List<Map<String, Object>> spans = new ArrayList<>();
        long[] ids = new long[MAX_DEPTH + 1], thread = new long[MAX_DEPTH + 1];
        long root = seen.isEmpty() ? 0 : seen.get(0).start;
        for (int i = 0; i < seen.size(); i++) {
            Seen x = seen.get(i);
            Map<String, Object> s = new LinkedHashMap<>();
            ids[x.depth] = i;
            thread[x.depth] = x.depth == 0 ? 0 : x.unrun ? thread[x.depth - 1] : lanes.get(x.lane);
            s.put("id", (long) i);
            s.put("parent", x.depth == 0 ? -1L : ids[x.depth - 1]);
            s.put("name", x.name);
            s.put("start", (x.start - root) / 1000);
            s.put("dur", x.dur / 1000);
            s.put("thread", thread[x.depth]);
            if (x.waits) s.put("wait", x.wait / 1000);
            if (x.cpu >= 0) s.put("cpu", x.cpu / 1000);
            StringBuilder f = new StringBuilder();
            if ((x.flags & Tempo.FAILED) != 0) f.append('f');
            if ((x.flags & Tempo.CANCELLED) != 0) f.append('c');
            if (x.open) f.append('o');
            if (x.never) f.append('n');
            if ((x.flags & Tempo.WAIT) != 0) f.append('w');
            if ((x.flags & (Tempo.HEALED | Tempo.MISUSE)) != 0) f.append('m');
            if (f.length() > 0) s.put("flags", f.toString());
            if (!x.attrs.isEmpty()) s.put("attrs", x.attrs);
            if (x.aggregate) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("count", x.count);
                g.put("min", x.min / 1000);
                g.put("max", x.max / 1000);
                g.put("extent", x.extent / 1000);
                g.put("maxAt", x.maxAt / 1000);
                s.put("agg", g);
            }
            spans.add(s);
        }
        doc.put("spans", spans);
        return doc;
    }
}
