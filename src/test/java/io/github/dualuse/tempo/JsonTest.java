package io.github.dualuse.tempo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import com.fasterxml.jackson.dataformat.smile.databind.SmileMapper;
import io.github.dualuse.tempo.Tempo.Handoff;
import io.github.dualuse.tempo.Tempo.Name;
import io.github.dualuse.tempo.Tempo.Options;
import io.github.dualuse.tempo.Tempo.Snapshot;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.msgpack.jackson.dataformat.MessagePackFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JSON form of FORMAT.md §8: {@link Snapshot#writeJson} writes what the whole header value decodes
 * to, {@link TempoJackson} writes the same text through Jackson, and CBOR, Smile and MessagePack carry
 * the same tree. The golden vectors get these checks where {@link GoldenTest} records them.
 */
class JsonTest {

    @AfterEach
    void noTraceLeftOnThread() {
        TempoTest.assertNothingLeftOpen();
    }

    static final ObjectMapper JSON = new ObjectMapper().registerModule(TempoJackson.module());
    static final ObjectMapper[] BINARY = {
            new CBORMapper().registerModule(TempoJackson.module()),
            new SmileMapper().registerModule(TempoJackson.module()),
            new ObjectMapper(new MessagePackFactory()).registerModule(TempoJackson.module())};

    static final Name QUOTED = Tempo.name("say \"hi\"\\\u0001"), RATIO = Tempo.name("ratio"), K = Tempo.name("k"),
            BIG = Tempo.name("big"), NEG = Tempo.name("neg"), NAN = Tempo.name("nan"), BROKEN = Tempo.name("x\uD800y");

    /** {@link Snapshot#writeJson} into a StringBuilder, asserted equal to what any other Appendable receives. */
    static String json(Snapshot snap) throws IOException {
        StringBuilder b = new StringBuilder("prefix:");
        snap.writeJson(b);
        StringWriter w = new StringWriter();
        snap.writeJson(w);
        assertEquals(b.substring("prefix:".length()), w.toString());
        return w.toString();
    }

    /**
     * The checks every rendered snapshot gets: its JSON is what its whole header value decodes to; Jackson
     * writes the same text; CBOR, Smile and MessagePack give back the same tree, integers as integers.
     * Returns the JSON.
     */
    static String check(Snapshot snap) throws IOException {
        String json = json(snap);
        Map<String, Object> doc = Json.object(json);
        String header = snap.toHeader(Integer.MAX_VALUE);
        if (!header.isEmpty()) {
            Map<String, Object> decoded = WireDecoder.decode(header);
            assertTrue(Json.same(decoded, doc), () -> header + "\njson: " + json + "\nwant: " + Json.show(decoded));
        }
        assertEquals(json, JSON.writeValueAsString(snap));
        Object tree = plain(JSON.readTree(json));
        for (ObjectMapper m : BINARY) {
            byte[] bytes = m.writeValueAsBytes(snap);
            assertEquals(tree, plain(m.readTree(bytes)), m.getFactory().getFormatName());
        }
        return json;
    }

    /** A Jackson tree as maps, lists, longs, doubles (also integers past a long), strings and booleans. */
    static Object plain(JsonNode n) {
        if (n.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> m.put(e.getKey(), plain(e.getValue())));
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonNode x : n) l.add(plain(x));
            return l;
        }
        if (n.isIntegralNumber() && n.canConvertToLong()) return n.longValue();
        if (n.isNumber()) return n.doubleValue();
        if (n.isTextual()) return n.textValue();
        if (n.isBoolean()) return n.booleanValue();
        throw new AssertionError(n.getNodeType() + ": " + n);
    }

    @Test
    void exactText() throws IOException {
        try (GoldenTest.Script s = new GoldenTest.Script(1794567890123L, "http \"1\"", "cpu\t2")) {
            Trace t = s.on(0, 0, () -> Tempo.trace(TempoTest.SEARCH, s.options()));
            Span root = t;
            Span q = s.on(0, 10, () -> root.span(QUOTED).set(RATIO, 0.000001).set(K, 2.0).set(BIG, 1e20)
                    .set(NEG, -0.0).set(NAN, Double.NaN).set(K, 7.25));
            s.run(0, 1010, q::close);
            Span b = s.on(0, 1012, () -> root.span(BROKEN));
            s.run(0, 1015, b::close);
            Handoff h = s.on(0, 1020, () -> root.handoff(TempoTest.LOAD));
            Span l = s.on(1, 1520, h::begin);
            s.run(1, 2020, () -> l.fail().close());
            s.run(0, 3000, t::close);
            Snapshot snap = s.at(0, t::snapshot);
            String want = "{\"v\":1,\"unit\":\"us\",\"wall\":1794567890123,\"folded\":0,\"dropped\":1,"
                    + "\"threads\":[\"http \\\"1\\\"\",\"cpu\\t2\"],\"live\":[],\"truncated\":false,\"spans\":["
                    + "{\"id\":0,\"parent\":-1,\"name\":\"search\",\"start\":0,\"dur\":3000,\"thread\":0},"
                    + "{\"id\":1,\"parent\":0,\"name\":\"say \\\"hi\\\"\\\\\\u0001\",\"start\":10,\"dur\":1000,\"thread\":0,"
                    + "\"attrs\":{\"ratio\":0.000001,\"k\":7.25,\"big\":100000000000000000000,\"neg\":0}},"
                    + "{\"id\":2,\"parent\":0,\"name\":\"x?y\",\"start\":1012,\"dur\":3,\"thread\":0},"
                    + "{\"id\":3,\"parent\":0,\"name\":\"load\",\"start\":1020,\"dur\":500,\"thread\":1,\"wait\":500,\"flags\":\"f\"}]}";
            assertEquals(want, check(snap));
            StringBuilder fromTrace = new StringBuilder();
            s.at(0, () -> {
                t.writeJson(fromTrace);
                return null;
            });
            assertEquals(want, fromTrace.toString());
        }
    }

    /** An aggregate, a task still running on a live thread, an open root, and no wall clock. */
    @Test
    void aggregatesLiveAndOpen() throws IOException {
        try (GoldenTest.Script s = new GoldenTest.Script(0, "main", "pool")) {
            Trace t = s.on(0, 0, () -> Tempo.trace(TempoTest.SEARCH, s.options().budget(2)).records(Tempo.STEP));
            Span root = t;
            Handoff h = s.on(0, 50, () -> root.handoff(TempoTest.MATCH));
            for (int i = 0; i < 3; i++) {
                long at = 100 + 100 * i;
                Span d = s.on(0, at, () -> root.step(TempoTest.DECODE));
                s.run(0, at + 10 + i, d::close);
            }
            s.on(1, 700, h::begin);
            assertEquals("{\"v\":1,\"unit\":\"us\",\"folded\":2,\"dropped\":0,\"threads\":[\"main\",\"pool\"],"
                    + "\"live\":[0,1],\"truncated\":false,\"spans\":["
                    + "{\"id\":0,\"parent\":-1,\"name\":\"search\",\"start\":0,\"dur\":900,\"thread\":0,\"flags\":\"o\"},"
                    + "{\"id\":1,\"parent\":0,\"name\":\"match\",\"start\":50,\"dur\":200,\"thread\":1,\"wait\":650,\"flags\":\"o\"},"
                    + "{\"id\":2,\"parent\":0,\"name\":\"decode\",\"start\":100,\"dur\":10,\"thread\":0},"
                    + "{\"id\":3,\"parent\":0,\"name\":\"decode\",\"start\":200,\"dur\":23,\"thread\":0,"
                    + "\"agg\":{\"count\":2,\"min\":11,\"max\":12,\"extent\":112,\"maxAt\":100}}]}",
                    check(s.on(0, 900, t::snapshot)));
        }
    }

    @Test
    void emptyAfterRelease() throws IOException {
        String empty = "{\"v\":1,\"unit\":\"us\",\"folded\":0,\"dropped\":0,\"threads\":[],\"live\":[],\"truncated\":false,\"spans\":[]}";
        Trace t = Tempo.trace(TempoTest.SEARCH, new Options().clock(new TempoTest.Clock()));
        t.close();
        t.release();
        StringBuilder b = new StringBuilder();
        t.writeJson(b);
        assertEquals(empty, b.toString());
        assertEquals(empty, check(t.snapshot()));
    }

    /** The JSON form has no size limit: a value cut down to its fallback still has every span in JSON. */
    @Test
    void noSizeLimit() throws IOException {
        TempoTest.Clock clock = new TempoTest.Clock();
        Trace t = Tempo.trace(TempoTest.SEARCH, new Options().clock(clock).headerBytes(300));
        try {
            for (int i = 0; i < 2000; i++) {
                clock.add(1000);
                try (Span s = t.span(i % 2 == 0 ? TempoTest.LOAD : TempoTest.MATCH)) {
                    s.set(K, i);
                    clock.add(500);
                }
            }
            t.close();
            Snapshot snap = t.snapshot();
            assertTrue(snap.toHeader().length() <= 300);
            String json = check(snap);
            assertTrue(json.length() > 100_000, () -> "" + json.length());
            List<?> spans = (List<?>) Json.object(json).get("spans");
            assertEquals(2001, spans.size());
            assertEquals(0L, Json.object(json).get("dropped"));
        } finally {
            t.release();
        }
    }

    @Test
    void writeErrorsReachTheCaller() throws IOException {
        TempoTest.Clock clock = new TempoTest.Clock();
        Trace t = Tempo.trace(TempoTest.SEARCH, new Options().clock(clock));
        for (int i = 0; i < 400; i++) t.span(TempoTest.LOAD).close();
        t.close();
        Snapshot snap = t.snapshot();
        t.release();
        IOException failure = new IOException("closed");
        Appendable broken = new Appendable() {
            @Override
            public Appendable append(CharSequence csq) throws IOException {
                throw failure;
            }

            @Override
            public Appendable append(CharSequence csq, int start, int end) throws IOException {
                throw failure;
            }

            @Override
            public Appendable append(char c) throws IOException {
                throw failure;
            }
        };
        assertSame(failure, assertThrows(IOException.class, () -> snap.writeJson(broken)));
        assertThrows(NullPointerException.class, () -> snap.writeJson(null));
        check(snap); // the thread's builder is still usable
    }

    static final class Response {
        @JsonSerialize(using = TempoJackson.SnapshotSerializer.class)
        public Snapshot tempo;
    }

    @Test
    void serializerOnAFieldWithoutTheModule() throws IOException {
        Trace t = Tempo.trace(TempoTest.SEARCH, new Options().clock(new TempoTest.Clock()));
        t.span(TempoTest.LOAD).close();
        t.close();
        Response r = new Response();
        r.tempo = t.snapshot();
        t.release();
        StringBuilder b = new StringBuilder("{\"tempo\":");
        r.tempo.writeJson(b);
        assertEquals(b.append('}').toString(), new ObjectMapper().writeValueAsString(r));
    }

    /** Random recordings (see {@link HeaderTest#randomSnapshot}), closed or open, through every check. */
    @Test
    void randomRecordings() throws IOException {
        SplittableRandom rnd = new SplittableRandom(20261114);
        for (int round = 0; round < 120; round++) check(HeaderTest.randomSnapshot(rnd, round, new HashSet<>()));
    }
}
