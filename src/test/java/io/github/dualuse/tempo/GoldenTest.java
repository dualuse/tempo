package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.Handoff;
import io.github.dualuse.tempo.Tempo.Name;
import io.github.dualuse.tempo.Tempo.Options;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.github.dualuse.tempo.Tempo.DETAIL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The vectors of {@code testdata/}: the test-only {@link WireDecoder} decodes every golden vector to its
 * JSON and rejects every invalid one with its code; and each canonical vector is recorded through the
 * public API on a fake clock and rendered by {@link Trace#toHeader()}, byte for byte.
 *
 * <p>Six canonical vectors are wire-only (testdata/README.md): they describe traces the recorder cannot
 * produce by design. For those the test asserts the closest trace it can record instead, given below as
 * a header value that {@code tools/tempo_ref.py} encodes from the vector's JSON with the stated changes,
 * so the expectation does not come from the encoder under test. The reasons, all from the recording
 * model: a thread records one stack, so it runs one task at a time and cannot start a span while an
 * earlier sibling is open on it; a lane holds 35 open spans; work reaches another thread only through a
 * handoff, which always carries its queue wait; a render has one instant and measures CPU time of ended
 * spans only; and an aggregate is one per parent, lap and name, without attributes.
 */
class GoldenTest {

    static final Path DATA = Paths.get(System.getProperty("tempo.testdata", "testdata"));

    static String read(String path) {
        try {
            return new String(Files.readAllBytes(DATA.resolve(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String golden(String name) {
        return read("golden/" + name + ".header").trim();
    }

    // ---- the decoder against every vector ---------------------------------------------------------

    @TestFactory
    Stream<DynamicTest> decoderMatchesEveryGoldenVector() throws IOException {
        List<String> names;
        try (Stream<Path> files = Files.list(DATA.resolve("golden"))) {
            names = files.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".header"))
                    .map(f -> f.substring(0, f.length() - ".header".length())).sorted().collect(Collectors.toList());
        }
        assertEquals(23, names.size(), "golden vectors");
        return names.stream().map(n -> DynamicTest.dynamicTest(n, () -> {
            Object want = Json.parse(read("golden/" + n + ".json"));
            Map<String, Object> got = WireDecoder.decode(read("golden/" + n + ".header"));
            assertTrue(Json.same(want, got), n + ":\n" + Json.show(got) + "\nwant:\n" + Json.show(want));
        }));
    }

    @TestFactory
    Stream<DynamicTest> decoderRejectsEveryInvalidVectorWithItsCode() {
        Matcher m = Pattern.compile("^\\| `([^`]+\\.header)` \\| `(E_[A-Z]+)` \\|", Pattern.MULTILINE)
                .matcher(read("invalid/README.md"));
        List<String[]> cases = new ArrayList<>();
        while (m.find()) cases.add(new String[] {m.group(1), m.group(2)});
        assertEquals(35, cases.size(), "invalid header vectors listed");
        return cases.stream().map(c -> DynamicTest.dynamicTest(c[0], () -> {
            WireDecoder.Rejected r = assertThrows(WireDecoder.Rejected.class, () -> WireDecoder.decode(read("invalid/" + c[0])));
            assertEquals(c[1], r.code, r.getMessage());
        }));
    }

    // ---- the encoder against the canonical vectors ------------------------------------------------

    static final Name SEARCH = Tempo.name("search"), GET_SEARCH = Tempo.name("GET /search"), DECODE = Tempo.name("decode"),
            DESCRIBE = Tempo.name("describe"), FINGERPRINT = Tempo.name("fingerprint"), INDEX = Tempo.name("index"),
            RERANK = Tempo.name("rerank"), LOAD = Tempo.name("load"), MATCH = Tempo.name("match"),
            ENCODE = Tempo.name("encode"), INFER = Tempo.name("infer"), AWAIT = Tempo.name("await"),
            JOIN = Tempo.name("join"), SCORE = Tempo.name("score"), DEFLATE = Tempo.name("deflate"),
            K = Tempo.name("k"), BYTES = Tempo.name("bytes");

    /**
     * A recording on named threads, one per entry of the vector's thread table, against a fake clock set
     * in microseconds before each step. Steps run one at a time, each on its thread; the CPU clock is one
     * value the script sets ({@code -1}: not measured), the wall clock the vector's {@code wall}.
     */
    static final class Script implements AutoCloseable {
        final TempoTest.Clock clock = new TempoTest.Clock();
        final long base = clock.t, wall;
        final String vector; // null for a recording of no vector
        final ExecutorService[] threads;
        volatile long cpu = -1;

        /** The vector's threads and wall clock. */
        Script(String vector) {
            this(vector, Json.object(read("golden/" + vector + ".json")));
        }

        private Script(String vector, Map<String, Object> doc) {
            this(vector, doc.get("wall") == null ? 0 : (Long) doc.get("wall"), ((List<?>) doc.get("threads")).toArray(new String[0]));
        }

        Script(long wall, String... names) {
            this(null, wall, names);
        }

        private Script(String vector, long wall, String... names) {
            this.vector = vector;
            this.wall = wall;
            threads = new ExecutorService[names.length];
            for (int i = 0; i < threads.length; i++) {
                String n = names[i];
                threads[i] = Executors.newSingleThreadExecutor(r -> new Thread(r, n));
            }
        }

        Options options() {
            return new Options().clock(clock).cpuClock(() -> cpu).wallClock(() -> wall).headerBytes(1 << 16);
        }

        /** Sets the CPU clock, in microseconds; -1 reads as not measured. */
        Script cpu(long us) {
            cpu = us < 0 ? -1 : us * 1000;
            return this;
        }

        <T> T on(int thread, long us, Callable<T> step) {
            clock.t = base + us * 1000;
            return at(thread, step);
        }

        /** Runs {@code step} on its thread without setting the clock. */
        <T> T at(int thread, Callable<T> step) {
            try {
                return threads[thread].submit(step).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new AssertionError("step on thread " + thread + " at " + (clock.t - base) + " ns", e);
            }
        }

        /**
         * {@link Trace#toHeader()} on thread 0 at {@code us}; the trace's {@link Trace#toLink(String)} decodes
         * to the same value, and its JSON form (see {@link JsonTest#check}) is what the whole value decodes
         * to: the vector's JSON when the value is the vector's and no ladder step or fallback.
         */
        String render(long us, Trace t) {
            Object[] r = on(0, us, () -> new Object[] {t.toHeader(), t.toLink(Tempo.Link.DEFAULT_LINK_BASE), t.snapshot()});
            String header = (String) r[0];
            try {
                assertEquals(header, LinkTest.unpack((String) r[1], Tempo.Link.DEFAULT_LINK_BASE));
                Tempo.Snapshot snap = (Tempo.Snapshot) r[2];
                assertEquals(header, snap.toHeader());
                String json = JsonTest.check(snap);
                if (vector != null && header.equals(golden(vector)) && header.equals(snap.toHeader(Integer.MAX_VALUE))) {
                    assertTrue(Json.same(Json.parse(read("golden/" + vector + ".json")), Json.parse(json)), vector + ": " + json);
                }
            } catch (java.util.zip.DataFormatException | IOException e) {
                throw new AssertionError(String.valueOf(r[1]), e);
            }
            return header;
        }

        void run(int thread, long us, Runnable step) {
            on(thread, us, () -> {
                step.run();
                return null;
            });
        }

        @Override
        public void close() {
            for (ExecutorService e : threads) e.shutdown();
        }
    }

    /** Asserts the value byte for byte, and that the decoder reads it back as the vector's JSON when it is the vector. */
    static void assertHeader(String vector, String expected, String actual) {
        assertEquals(expected, actual, vector);
        if (expected.equals(golden(vector))) {
            assertTrue(Json.same(Json.parse(read("golden/" + vector + ".json")), WireDecoder.decode(actual)), vector);
        }
    }

    @Test
    void singleRoot() {
        try (Script s = new Script("single-root")) {
            Trace t = s.on(0, 0, () -> Tempo.start(SEARCH, s.options()));
            s.run(0, 1500, t::close);
            assertHeader("single-root", golden("single-root"), s.render(1500, t));
        }
    }

    @Test
    void attributes() {
        Name offset = Tempo.name("offset"), zero = Tempo.name("zero"), hits = Tempo.name("hits"),
                ratio = Tempo.name("ratio"), delta = Tempo.name("delta"), threshold = Tempo.name("threshold"),
                mean = Tempo.name("mean"), skew = Tempo.name("skew");
        try (Script s = new Script("attributes")) {
            Trace t = s.on(0, 0, () -> Tempo.start(SEARCH, s.options()));
            Span root = t.root();
            s.run(0, 0, () -> root.set(K, 10).set(offset, -42).set(zero, 0));
            Span index = s.on(0, 100, () -> root.span(INDEX).set(hits, 9007199254740991L).set(ratio, 0.5).set(delta, -0.25));
            s.run(0, 4100, index::close);
            Span rerank = s.on(0, 4200, () -> root.span(RERANK)
                    .set(threshold, 0.000001).set(mean, 123456.789).set(skew, -3.141593).set(INDEX, 3));
            s.run(0, 9200, rerank::close);
            s.run(0, 10000, t::close);
            assertHeader("attributes", golden("attributes"), s.render(10000, t));
        }
    }

    @Test
    void handoff() {
        try (Script s = new Script("handoff")) {
            Trace t = s.cpu(-1).on(0, 0, () -> Tempo.start(SEARCH, s.options().cpu(true)));
            Span root = t.root();
            Span describe = s.cpu(0).on(0, 50, () -> root.span(DESCRIBE).cpu());
            Handoff infer1 = s.on(0, 100, () -> describe.handoff(INFER)), infer2 = s.on(0, 140, () -> describe.handoff(INFER));

            Span i1 = s.cpu(0).on(1, 220, infer1::begin);
            Span encode = s.cpu(0).on(1, 230, () -> i1.span(ENCODE).cpu());
            s.cpu(990).run(1, 1230, encode::close);
            Handoff load = s.on(1, 1300, () -> i1.handoff(LOAD));
            Span l = s.cpu(0).on(2, 1330, load::begin);
            s.cpu(50).run(2, 2130, l::close);
            Handoff match = s.on(1, 2200, () -> i1.handoff(MATCH));
            s.cpu(4800).run(1, 5220, i1::close);
            Span m = s.cpu(0).on(1, 5300, match::begin); // its own thread again: a child that ends after its parent
            s.cpu(880).run(1, 6200, m::close);
            Span i2 = s.cpu(0).on(1, 6240, infer2::begin);
            s.cpu(5900).run(1, 12240, i2::close);

            Span fingerprint = s.on(0, 11500, () -> describe.span(FINGERPRINT));
            s.run(0, 14000, fingerprint::close);
            s.cpu(1200).run(0, 30050, describe::close);
            Span rerank = s.on(0, 30100, () -> root.span(RERANK));
            Handoff load2 = s.on(0, 30110, () -> rerank.handoff(LOAD));
            Span l2 = s.cpu(0).on(2, 30110, load2::begin);
            s.cpu(300).run(2, 34110, l2::close);
            Handoff match2 = s.on(0, 34200, () -> rerank.handoff(MATCH));
            Span m2 = s.cpu(0).on(1, 34207, match2::begin);
            s.cpu(2950).run(1, 37207, m2::close);
            s.run(0, 39100, rerank::close);
            s.cpu(-1).run(0, 40000, t::close);
            assertHeader("handoff", golden("handoff"), s.render(40000, t));
        }
    }

    @Test
    void handoffNotRun() {
        try (Script s = new Script("handoff-not-run")) {
            Trace t = s.on(0, 0, () -> Tempo.start(SEARCH, s.options().cpu(true)));
            Span rerank = s.on(0, 1000, () -> t.root().span(RERANK));
            Handoff a = s.on(0, 1010, () -> rerank.handoff(LOAD)), b = s.on(0, 1020, () -> rerank.handoff(LOAD)),
                    c = s.on(0, 1030, () -> rerank.handoff(LOAD)), d = s.on(0, 1040, () -> rerank.handoff(LOAD));
            Span la = s.cpu(0).on(1, 1025, a::begin);
            Span lb = s.cpu(0).on(2, 1040, b::begin);
            s.cpu(150).run(2, 4040, lb::close);
            s.cpu(200).run(1, 5025, la::close);
            Span ld = s.cpu(0).on(1, 5040, () -> d.begin().cancel()); // cancelled while running
            s.cpu(100).run(1, 7540, ld::close);
            Handoff e = s.on(0, 5100, () -> rerank.handoff(MATCH));
            Span me = s.cpu(0).on(3, 5140, e::begin);
            s.cpu(5900).run(3, 11140, me::close);
            s.on(0, 8100, () -> rerank.handoff(MATCH)); // never started
            s.cpu(-1).run(0, 9030, c::cancel);           // cancelled before it started
            s.run(0, 28000, rerank::close);
            Span encode = s.on(0, 28100, () -> t.root().span(ENCODE));
            s.run(0, 29600, encode::close);
            s.run(0, 30000, t::close);
            assertHeader("handoff-not-run", golden("handoff-not-run"), s.render(30000, t));
        }
    }

    /** Closest: the two cross-thread children without a handoff become handoffs with no queue wait ({@code ~0}). */
    @Test
    void names() {
        String closest = "v1;w=mwevdy38;T=pool_1-worker:%C3%96lm%C3%BChle-2:io%5F3;N=GET_%2Fsearch:Gr%C3%B6%C3%9Fe:"
                + "%E6%97%A5%E6%9C%AC%E8%AA%9E:%F0%9F%9A%80_launch:%3F:a.b-c:100%25:x%3Ay%3Bz%2Fw%3Dv:snake%5Fcase:bytes_read;"
                + "S=00.0.6y0:9=512:8=1/11.a.rs/12.ua.rs@1~0/13.uk.rs@2~0/14.uk.2s/15.2s.2s/16.2s.2s/17.2s.2s/18.2s.2s;e=9";
        Name snake = Tempo.name("snake_case");
        try (Script s = new Script("names")) {
            Trace t = s.on(0, 0, () -> Tempo.start(GET_SEARCH, s.options()));
            Span root = t.root();
            s.run(0, 0, () -> root.set(Tempo.name("bytes read"), 512).set(snake, 1));
            Span grosse = s.on(0, 10, () -> root.span(Tempo.name("Größe")));
            s.run(0, 1010, grosse::close);
            Handoff h1 = s.on(0, 1100, () -> root.handoff(Tempo.name("日本語")));
            Span w1 = s.on(1, 1100, h1::begin);
            s.run(1, 2100, w1::close);
            Handoff h2 = s.on(0, 2200, () -> root.handoff(Tempo.name("🚀 launch")));
            Span w2 = s.on(2, 2200, h2::begin);
            s.run(2, 3200, w2::close);
            String[] small = {"?", "a.b-c", "100%", "x:y;z/w=v", "snake_case"};
            for (int i = 0; i < small.length; i++) {
                Name n = Tempo.name(small[i]);
                Span x = s.on(0, 3300 + 100 * i, () -> root.span(n));
                s.run(0, 3400 + 100 * i, x::close);
            }
            s.run(0, 9000, t::close);
            assertHeader("names", closest, s.render(9000, t));
        }
    }

    /**
     * Closest: cross-thread children are handoffs ({@code ~0}); the second {@code infer} is submitted at
     * 4150 with a queue wait of 100, before the first opens, because a thread cannot open a sibling of a
     * span it still has open: submitted at 4250 it would be a child of the first {@code infer}.
     */
    @Test
    void threads() {
        String closest = "v1;w=mwevdy32;T=http-1:worker:worker:io-2;N=search:decode:describe:infer:load:encode:index:match;"
                + "S=00.0.12kw/11.a.334/12.35m.ffk@1~0/23.1e.ccg@2~2s/34.6y.p0@3~0/23.1e.bko/35.2s.1jk@0~0/16.fic.jag"
                + "/24.2s.7ps@3~0/27.7sk.asw;e=a";
        try (Script s = new Script("threads")) {
            Trace t = s.on(0, 0, () -> Tempo.start(SEARCH, s.options()));
            Span root = t.root();
            Span decode = s.on(0, 10, () -> root.span(DECODE));
            s.run(0, 4010, decode::close);
            Handoff hd = s.on(0, 4100, () -> root.handoff(DESCRIBE));
            Span d = s.on(1, 4100, hd::begin);
            Handoff hi = s.on(1, 4150, () -> d.handoff(INFER));
            Span i1 = s.on(1, 4200, () -> d.span(INFER));
            Span i2 = s.on(2, 4250, hi::begin);
            Handoff he = s.on(1, 4300, () -> i1.handoff(ENCODE));
            Span e = s.on(0, 4300, he::begin); // back on the request thread, which is inside the trace
            Handoff hl = s.on(2, 4400, () -> i2.handoff(LOAD));
            Span l = s.on(3, 4400, hl::begin);
            s.run(3, 5300, l::close);
            s.run(0, 6300, e::close);
            s.run(1, 19200, i1::close);
            s.run(2, 20250, i2::close);
            s.run(1, 24100, d::close);
            Span index = s.on(0, 24200, () -> root.span(INDEX));
            Handoff hl2 = s.on(0, 24300, () -> index.handoff(LOAD));
            Span l2 = s.on(3, 24300, hl2::begin);
            s.run(3, 34300, l2::close);
            Span match = s.on(0, 34400, () -> index.span(MATCH));
            s.run(0, 48400, match::close);
            s.run(0, 49200, index::close);
            s.run(0, 50000, t::close);
            assertHeader("threads", closest, s.render(50000, t));
        }
    }

    /**
     * Closest: a lane holds 35 open spans (depths 0 to 34), so {@code level-35} is a label-after lap of
     * {@code level-34}, which starts where its parent does (331, not 338). And {@code level-3} with its
     * subtree ends 1180 earlier, before its sibling {@code rerank} starts: one thread, one stack.
     */
    @Test
    void deepNesting() {
        String closest = "v1;w=mwevdy31;T=http-1;N=search:describe:level-2:level-3:level-4:level-5:level-6:level-7:level-8:"
                + "level-9:level-10:level-11:level-12:level-13:level-14:level-15:level-16:level-17:level-18:level-19:level-20:"
                + "level-21:level-22:level-23:level-24:level-25:level-26:level-27:level-28:level-29:level-30:level-31:level-32:"
                + "level-33:level-34:level-35:rerank:match:encode:deflate;S=00.0.1xg0/11.2s.1pq8/22.7.1ppo/33.7.1osc/44.7.1ors"
                + "/55.7.1or8/66.7.1oqo/77.7.1oq4/88.7.1opk/99.7.1op0/aa.7.1oog/bb.7.1onw/cc.7.1onc/dd.7.1oms/ee.7.1om8"
                + "/ff.7.1olo/gg.7.1ol4/hh.7.1okk/ii.7.1ok0/jj.7.1ojg/kk.7.1oiw/ll.7.1oic/mm.7.1ohs/nn.7.1oh8/oo.7.1ogo"
                + "/pp.7.1og4/qq.7.1ofk/rr.7.1of0/ss.7.1oeg/tt.7.1odw/uu.7.1odc/vv.7.1ocs/ww.7.1oc8/xx.7.1obo/yy.7.1ob4"
                + "/zz.0.1oak/310.1osw.1e/411.5.k/411.p.a/112.1tic.2bc/213.a.28k;e=15";
        try (Script s = new Script("deep-nesting")) {
            Trace t = s.on(0, 0, () -> Tempo.start(SEARCH, s.options()));
            Span[] level = new Span[35];
            level[0] = t.root();
            level[1] = s.on(0, 100, () -> level[0].span(DESCRIBE));
            for (int k = 2; k <= 34; k++) {
                int at = k;
                level[k] = s.on(0, 100 + 7 * (k - 1), () -> level[at - 1].span(Tempo.name("level-" + at)));
            }
            s.run(0, 331 + 79320 - 1180, () -> level[34].done(Tempo.name("level-35")));
            for (int k = 34; k >= 3; k--) s.run(0, 100 + 7 * (k - 1) + 80000 - 20 * (k - 1) - 1180, level[k]::close);
            Span rerank = s.on(0, 78914, () -> level[2].span(RERANK));
            Span m1 = s.on(0, 78919, () -> rerank.span(MATCH));
            s.run(0, 78939, m1::close);
            Span m2 = s.on(0, 78944, () -> rerank.span(MATCH));
            s.run(0, 78954, m2::close);
            s.run(0, 78964, rerank::close);
            s.run(0, 80087, level[2]::close);
            s.run(0, 80100, level[1]::close);
            Span encode = s.on(0, 85000, () -> level[0].span(ENCODE));
            Span deflate = s.on(0, 85010, () -> encode.span(DEFLATE));
            s.run(0, 87910, deflate::close);
            s.run(0, 88000, encode::close);
            s.run(0, 90000, t::close);
            assertHeader("deep-nesting", closest, s.render(90000, t));
        }
    }

    /**
     * Closest: {@code rerank} stays open until the render, and while it is open on the request thread
     * nothing new can be a child of the root there. So the two zero-length misuse entries come before
     * it: {@code describe} at 14100 is a {@code done} naming another phase than the open {@code index},
     * and the failed {@code decode} at 14200 is healed at once when {@code rerank} starts as a phase.
     */
    @Test
    void failedOpen() {
        String closest = "v1;w=mwevdy35;l=0.1;T=http-9:io-4;N=search:decode:index:await:describe:rerank:load:join;"
                + "S=00.0.1b2g^o/11.u.1jk^f/12.1li.99c/23.1e.93s^w/14.99c.0^m/11.2s.0^fm/15.0.1040^o/26.2s.zzk@1~1o^o"
                + "/27.2s.zyg^ow;e=9";
        try (Script s = new Script("failed-open")) {
            Trace t = s.on(0, 0, () -> Tempo.start(SEARCH, s.options()));
            Span root = t.root();
            Span decode = s.on(0, 30, () -> root.span(DECODE).fail());
            s.run(0, 2030, decode::close);
            s.run(0, 2100, () -> root.phase(INDEX));
            Span await = s.on(0, 2150, () -> root.span(AWAIT).waiting());
            s.run(0, 13950, await::close);
            s.run(0, 14100, () -> root.done(DESCRIBE));
            s.run(0, 14200, () -> root.span(DECODE).fail());
            s.run(0, 14200, () -> root.phase(RERANK));
            Handoff h = s.on(0, 14300, () -> root.handoff(LOAD));
            Span load = s.on(1, 14360, h::begin);
            s.run(0, 14400, () -> root.span(JOIN).waiting());
            assertHeader("failed-open", closest, s.render(61000, t));
            s.run(1, 61000, load::close);
            s.run(0, 61000, t::close);
        }
    }

    /**
     * Closest: a render has one instant, 75050, at which every open span ends; and CPU time is measured
     * when a span ends, so the open spans have none. The 48 folded and 3 dropped spans are detail spans
     * past the budget (spent on the four handoffs) still open in two of the tasks: an aggregate is written
     * once an occurrence has ended.
     */
    @Test
    void liveAndFolded() {
        String closest = "v1;w=mwevdy39;x=1c.3;l=1.2.3;T=http-4:cpu-1:cpu-2:io-1;N=search:rerank:match:load;"
                + "S=00.0.1lvc/11.2s.1l3k/22.2s.1lps@1~1e^o/22.2s.1i1u@2~3mk^o/23.2s.2bc@3~5/23.2e4.1j7i@3~0^o;e=6";
        try (Script s = new Script("live-and-folded")) {
            Trace t = s.on(0, 0, () -> Tempo.start(SEARCH, s.options().level(DETAIL).budget(4))); // the four handoffs
            Span rerank = s.on(0, 100, () -> t.root().span(RERANK));
            Handoff m1 = s.on(0, 200, () -> rerank.handoff(MATCH)), m2 = s.on(0, 300, () -> rerank.handoff(MATCH)),
                    l1 = s.on(0, 400, () -> rerank.handoff(LOAD)), l2 = s.on(0, 3500, () -> rerank.handoff(LOAD));
            Span w1 = s.on(1, 250, m1::begin);
            s.run(1, 300, () -> {
                Span x = w1;
                for (int i = 0; i < 34; i++) x = x.detail(SCORE);    // folded, all still open
                for (int i = 0; i < 3; i++) assertSame(Tempo.NOOP, x.detail(SCORE)); // dropped at depth 35
            });
            Span w2 = s.on(2, 5000, m2::begin);
            s.run(2, 5100, () -> {
                Span x = w2;
                for (int i = 0; i < 14; i++) x = x.detail(SCORE);
            });
            Span w3 = s.on(3, 405, l1::begin);
            s.run(3, 3405, w3::close);
            Span w4 = s.on(3, 3500, l2::begin);
            s.run(0, 74100, rerank::close);
            s.run(0, 75000, t::close);
            assertHeader("live-and-folded", closest, s.render(75050, t));
            s.run(1, 75050, w1::close);
            s.run(2, 75050, w2::close);
            s.run(3, 75050, w4::close);
        }
    }

    /**
     * Closest: a thread runs one task at a time, so the second {@code load} waits 1960 instead of 1950 and
     * starts when the first ends on {@code io-1}; the {@code score} aggregate has no attribute, which a
     * folded span ignores; and the {@code match} of count 1 is left out, as a second aggregate of the
     * same name under {@code rerank} cannot be recorded (one per parent, lap and name), so {@code cpu-1}
     * and {@code cpu-2} are in no record and leave the thread table.
     */
    @Test
    void aggregates() {
        String closest = "v1;w=mwevdy36;x=dc.0;T=http-2:io-1;N=search:rerank:load:match:score:encode;S=00.0.4abk/11.dw.42ls"
                + "/22.2s.1jk@1~a/22.1e.2bc@1~1ig/22.3cu.1i0g@1~6y0.jg*334+14.p0.3h0.1xg0.vmw"
                + "/23.2s.37qo@_~n5c.1xg*3674+14.1jk.6y0.2klc.5eg^f/24.2s.m8+b4.1.9.3uw0.3u48/15.42zo.668;e=8";
        try (Script s = new Script("aggregates")) {
            Trace t = s.on(0, 0, () -> Tempo.start(SEARCH, s.options().level(DETAIL).budget(2).cpu(true)));
            Span rerank = s.on(0, 500, () -> t.root().span(RERANK));
            Handoff a = s.on(0, 600, () -> rerank.handoff(LOAD)), b = s.on(0, 650, () -> rerank.handoff(LOAD));
            Span la = s.on(3, 610, a::begin);
            s.run(3, 2610, la::close);
            Span lb = s.on(3, 2610, b::begin);
            s.run(3, 5610, lb::close);
            // 40 loads past the budget, one after another on io-1: submitted every 2050 from 5000, the slowest
            // (4500) at 46000, then every 2300 from 50600, the last at 93050; mostly 1700 long after 200 waiting
            for (int i = 0; i < 40; i++) {
                long submit = i <= 20 ? 5000 + 2050 * i : i < 39 ? 50600 + 2300 * (i - 21) : 93050;
                long wait = i == 0 ? 650 : i == 1 ? 700 : i == 39 ? 250 : 200;
                long dur = i == 1 ? 900 : i == 20 ? 4500 : 1700, cpu = i == 0 ? 1700 : i == 1 ? 900 : i == 2 ? 1400 : -1;
                Handoff h = s.on(0, submit, () -> rerank.handoff(LOAD));
                Span l = s.cpu(cpu < 0 ? -1 : 0).on(3, submit + wait, h::begin);
                s.cpu(cpu).run(3, submit + wait + dur, l::close);
            }
            // 40 failed matches, 20 on cpu-1 submitted every 5500 from 5100, 20 on cpu-2: the slowest (9000)
            // at 12100, then every 5300 from 21800, the last at 118950 waiting 2500; mostly 3650 after 700
            for (int i = 0; i < 40; i++) {
                int thread = i < 20 ? 1 : 2, k = i % 20;
                long submit = thread == 1 ? 5100 + 5500 * k : k == 0 ? 12100 : k < 19 ? 21800 + 5300 * (k - 1) : 118950;
                long wait = thread == 1 && k == 1 ? 900 : thread == 2 && k == 19 ? 2500 : 700;
                long dur = thread == 1 ? (k == 0 ? 2000 : k == 1 ? 3950 : 3650) : k == 0 ? 9000 : 3650;
                Handoff h = s.on(0, submit, () -> rerank.handoff(MATCH));
                Span m = s.cpu(0).on(thread, submit + wait, () -> h.begin().fail());
                s.cpu(thread == 1 && k == 1 ? 1950 : dur).run(thread, submit + wait + dur, m::close);
            }
            // 400 scores: sum 800, min 1 (first), max 9 at +179000, the last ending at +180000
            List<long[]> score = new ArrayList<>();
            score.add(new long[] {5200, 1});
            for (int k = 0; k < 397; k++) score.add(new long[] {5600 + 400 * k, k < 6 ? 1 : 2});
            score.add(new long[] {184200, 9});
            score.add(new long[] {185198, 2});
            s.cpu(-1);
            for (long[] o : score) {
                Span x = s.on(0, o[0], () -> rerank.detail(SCORE));
                s.run(0, o[0] + o[1], x::close);
            }
            s.run(0, 190500, rerank::close);
            Span encode = s.on(0, 191000, () -> t.root().span(ENCODE));
            s.run(0, 199000, encode::close);
            s.run(0, 200000, t::close);
            assertHeader("aggregates", closest, s.render(200000, t));
        }
    }

    /**
     * The worked example of FORMAT.md, byte for byte. {@code fingerprint} is a child of the root submitted
     * while {@code describe} runs: a thread submits under its innermost open span, so the script ends
     * {@code describe} first and then submits at the earlier time on the fake clock. With a budget of five
     * the handoffs past {@code match2} fold: 36 {@code load} tasks one after another on {@code io-1}, and
     * 36 {@code match} tasks spread over {@code cpu-1} and {@code cpu-2}.
     */
    @Test
    void searchExample() {
        try (Script s = new Script("search-example")) {
            Trace t = s.cpu(-1).on(0, 0, () -> Tempo.start(GET_SEARCH, s.options().cpu(true).budget(5)));
            Span root = t.root();
            Span decode = s.on(0, 120, () -> root.span(DECODE));
            s.run(0, 8420, decode::close);
            Span describe = s.cpu(0).on(0, 8450, () -> root.span(DESCRIBE).cpu());
            s.cpu(40100).run(0, 49650, describe::close);
            Handoff fingerprint = s.on(0, 8460, () -> root.handoff(FINGERPRINT));
            Span f = s.cpu(0).on(1, 8770, fingerprint::begin);
            s.cpu(22000).run(1, 31670, f::close);
            Span index = s.cpu(-1).on(0, 49700, () -> root.span(INDEX).set(K, 40));
            s.run(0, 80200, index::close);
            Span rerank = s.on(0, 80250, () -> root.span(RERANK));
            Handoff load1 = s.on(0, 80300, () -> rerank.handoff(LOAD)), load2 = s.on(0, 80310, () -> rerank.handoff(LOAD)),
                    match1 = s.on(0, 86500, () -> rerank.handoff(MATCH)), match2 = s.on(0, 94000, () -> rerank.handoff(MATCH));
            // cpu-2 begins before io-1, so the thread table reads http-7, cpu-1, cpu-2, io-1
            Span m1 = s.cpu(0).on(1, 86520, () -> match1.begin().set(SCORE, 0.83));
            s.cpu(9350).run(1, 95920, m1::close);
            Span m2 = s.cpu(0).on(2, 94015, () -> match2.begin().set(SCORE, 0.41));
            s.cpu(8700).run(2, 102815, m2::close);
            Span l1 = s.cpu(-1).on(3, 80345, () -> load1.begin().set(BYTES, 48213));
            s.run(3, 86445, l1::close);
            Span l2 = s.on(3, 86510, () -> load2.begin().fail());
            s.run(3, 93910, l2::close);

            // load, folded: begins every 1500 from 94440 to the slowest (4100) submitted at 124400, then
            // every 1850 from 128900 to the fastest (600) at 154800; waits of 40, the last 400; CPU on the first
            for (int i = 0; i < 36; i++) {
                long begin = i <= 20 ? 94440 + 1500 * i : 128900 + 1850 * (i - 21), wait = i == 35 ? 400 : 40;
                long dur = i == 20 ? 4100 : i == 35 ? 600 : 1450;
                Handoff h = s.on(0, begin - wait, () -> rerank.handoff(LOAD));
                Span l = s.cpu(i == 0 ? 0 : -1).on(3, begin, h::begin);
                s.cpu(i == 0 ? 2100 : -1).run(3, begin + dur, l::close);
            }
            // match, folded: 18 on cpu-1 from 100400 (the slowest, 9400, submitted at 152400; the fastest,
            // 2100, last), 18 on cpu-2 from 102800 (wait 60, CPU 700 under its run time); other waits 24
            long[] cpu1 = {100424, 104424, 108424, 112424, 116424, 120424, 124424, 128424, 132424, 136424, 140424,
                    144424, 148424, 152424, 162400, 167000, 171600, 176300};
            for (int i = 0; i < 36; i++) {
                int thread = i < 18 ? 1 : 2, k = i % 18;
                long begin = thread == 1 ? cpu1[k] : k == 0 ? 102860 : 107000 + 4100 * (k - 1);
                long wait = thread == 2 && k == 0 ? 60 : 24;
                long dur = thread == 1 ? (k == 13 ? 9400 : k == 17 ? 2100 : 4000) : k == 0 ? 4100 : 4000;
                Handoff h = s.on(0, begin - wait, () -> rerank.handoff(MATCH));
                Span m = s.cpu(0).on(thread, begin, h::begin);
                s.cpu(thread == 2 && k == 0 ? dur - 700 : dur).run(thread, begin + dur, m::close);
            }
            s.cpu(-1).run(0, 182050, rerank::close);
            Span encode = s.on(0, 182100, () -> root.span(ENCODE));
            s.run(0, 184100, encode::close);
            s.run(0, 184200, t::close);
            assertHeader("search-example", golden("search-example"), s.render(184200, t));
            assertEquals(415, golden("search-example").length());
        }
    }

    /** The fallback: below 256 bytes there is no ladder. */
    @Test
    void fallback() {
        try (Script s = new Script("fallback")) {
            Trace t = s.on(0, 0, () -> Tempo.start(SEARCH, s.options().headerBytes(255)));
            for (int i = 0; i < 100; i++) {
                Span x = s.on(0, 100 + 1000 * i, () -> t.root().span(LOAD));
                s.run(0, 600 + 1000 * i, x::close);
            }
            s.run(0, 207126, t::close);
            assertHeader("fallback", golden("fallback"), s.render(207126, t));
            Tempo.Snapshot snap = t.snapshot();
            String full = Tempo.Header.of(snap, 1 << 16);
            assertEquals(939, full.length());
            assertEquals(full, Tempo.Header.of(snap, 939));
            // one byte less, and the 100 children fold: 99 into an aggregate beside the first (the slowest of equals)
            assertEquals("v1;x=2r.0;T=%3F;N=search:load;S=00.0.4fti/11.2s.dw/11.rs.1270+2r.dw.dw.2404.0;e=3", Tempo.Header.of(snap, 938));
            assertEquals(golden("fallback"), Tempo.Header.of(snap, 255));
            assertEquals(golden("fallback"), Tempo.Header.of(snap, 0));
        }
    }
}
