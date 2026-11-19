package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.Handoff;
import io.github.dualuse.tempo.Tempo.Name;
import io.github.dualuse.tempo.Tempo.Snapshot;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** The byte-budget ladder of FORMAT.md §7.1. */
class LadderTest {

    @AfterEach
    void noTraceLeftOnThread() {
        TempoTest.assertNothingLeftOpen();
    }

    static final Name RENDER = Tempo.name("GET /render"), PARSE = Tempo.name("parse"), LAYOUT = Tempo.name("layout"),
            MEASURE = Tempo.name("measure"), FLOW = Tempo.name("flow"), LINE = Tempo.name("line"),
            GLYPH = Tempo.name("glyph"), TILE = Tempo.name("tile"), RASTER = Tempo.name("raster"),
            ENCODE = Tempo.name("encode"), BYTES = Tempo.name("bytes"), CHARS = Tempo.name("chars"),
            QUEUED = Tempo.name("queued"), RATIO = Tempo.name("ratio");

    /**
     * The recording behind the {@code ladder*} vectors: a page render with repeated siblings (six
     * {@code measure}, one failed; five {@code tile} handoffs to two pool threads), three levels below
     * {@code layout} and attributes on every level.
     */
    static Snapshot ladderTrace() {
        try (GoldenTest.Script s = new GoldenTest.Script("ladder")) {
            Trace t = s.on(0, 0, () -> Tempo.trace(RENDER, s.options().cpu(true)));
            Span root = t;
            Span parse = s.on(0, 100, () -> root.span(PARSE).set(BYTES, 5120));
            s.run(0, 2100, parse::close);
            Span layout = s.on(0, 2200, () -> root.span(LAYOUT));
            long[] measure = {400, 450, 900, 500, 420, 430};
            for (int i = 0; i < measure.length; i++) {
                int k = i;
                Span m = s.on(0, 2300 + 1000 * i, () -> layout.span(MEASURE));
                s.run(0, 2300 + 1000 * i + measure[i], () -> (k == 4 ? m.fail() : m).close());
            }
            Span flow = s.on(0, 9000, () -> layout.span(FLOW));
            for (int i = 0; i < 3; i++) {
                long at = 9100 + 6000 * i;
                int chars = 60 + 7 * i;
                Span line = s.on(0, at, () -> flow.span(LINE).set(CHARS, chars));
                Span glyph = s.on(0, at + 200, () -> line.span(GLYPH));
                s.run(0, at + 1500 + 400 * i, glyph::close);
                s.run(0, at + 2000 + 400 * i, line::close);
            }
            s.run(0, 29000, flow::close);
            s.run(0, 30200, layout::close);
            Handoff[] tiles = new Handoff[5];
            for (int i = 0; i < tiles.length; i++) {
                int k = i;
                tiles[i] = s.on(0, 30300 + 50 * i, () -> root.handoff(TILE).set(QUEUED, k));
            }
            long[] begin = {30400, 30500, 33400, 34600, 37000}, run = {3000, 4100, 3200, 2300, 2800};
            for (int i = 0; i < tiles.length; i++) {
                int k = i, thread = 1 + i % 2;
                Span tile = s.cpu(0).on(thread, begin[i], tiles[i]::begin);
                Span raster = s.cpu(-1).on(thread, begin[i] + 100, () -> tile.span(RASTER));
                s.run(thread, begin[i] + run[i] - 100, raster::close);
                s.cpu(run[i] - 300 - 100 * k).run(thread, begin[i] + run[i], tile::close);
            }
            Span encode = s.cpu(-1).on(0, 50000, () -> root.span(ENCODE).set(RATIO, 0.42));
            s.run(0, 52000, encode::close);
            s.run(0, 52500, t::close);
            return s.on(0, 52500, t::snapshot);
        }
    }

    /**
     * The vectors of {@code testdata/ladder.json}, which {@code tools/tempo_ref.py} wrote from the
     * {@code ladder} vector: the recording renders each one byte for byte at its limit.
     */
    @Test
    void eachLadderStepReproducesItsVector() throws Exception {
        Snapshot snap = ladderTrace();
        Map<String, Object> manifest = Json.object(GoldenTest.read("ladder.json"));
        String source = (String) manifest.get("source");
        GoldenTest.assertHeader(source, GoldenTest.golden(source), snap.toHeader(1 << 16));
        List<String> tried = steps(snap);
        List<?> steps = (List<?>) manifest.get("steps");
        assertEquals(4, steps.size());
        for (Object o : steps) {
            Map<?, ?> step = (Map<?, ?>) o;
            String vector = (String) step.get("vector");
            int limit = ((Long) step.get("maxBytes")).intValue();
            String value = snap.toHeader(limit);
            GoldenTest.assertHeader(vector, GoldenTest.golden(vector), value);
            assertTrue(value.length() <= limit, vector);
            // a link as long as this value's carries it, or a step before it whose link is no longer
            int room = Tempo.Link.of(BASE, value).length();
            String link = snap.toLink(BASE, room);
            assertTrue(link.length() <= room, vector);
            assertTrue(tried.indexOf(LinkTest.unpack(link, BASE)) <= tried.indexOf(value), vector);
        }
    }

    /** Every value the ladder tries for {@code snap}, in order. */
    static List<String> steps(Snapshot snap) {
        List<String> tried = new ArrayList<>();
        new Tempo.Ladder(snap.toHeader(Integer.MAX_VALUE)).run(v -> !tried.add(v.toString()), new StringBuilder());
        return tried;
    }

    static final String BASE = Tempo.Link.DEFAULT_LINK_BASE;

    /** The fallback stands for every span but the root, a non-finite attribute value counted with them. */
    @Test
    void theFallbackCountsEverySpanButTheRoot() {
        Snapshot snap = ladderTrace();
        assertEquals("v1;x=0.q;T=%3F;N=%3F;S=00.0.14ic;e=1", snap.toHeader(255)); // 26 spans below the root
        assertEquals(snap.toHeader(255), snap.toHeader(0));
        assertEquals(snap.toHeader(255), snap.toHeader(-1));
    }

    /**
     * For random recordings and every limit from 256 bytes, the value keeps the limit and decodes; for a
     * few of them literally every limit up to their length, for the others a sample.
     */
    @Test
    void everyLimitFrom256IsKept() {
        SplittableRandom rnd = new SplittableRandom(20261114);
        for (int round = 0; round < 120; round++) {
            Snapshot snap = HeaderTest.randomSnapshot(rnd, round, new HashSet<>());
            String full = snap.toHeader(Integer.MAX_VALUE);
            int[] limits = round < 8 ? IntStream.rangeClosed(256, full.length()).toArray()
                    : rnd.ints(24, 256, Math.max(257, full.length() + 1)).toArray();
            for (int limit : limits) {
                String h = snap.toHeader(limit);
                assertTrue(h.length() <= limit, () -> limit + ": " + h);
                assertFalse((Boolean) WireDecoder.decode(h).get("truncated"), h);
                if (limit >= full.length()) assertEquals(full, h);
            }
        }
    }

    /** Links keep their limit, base included, once it leaves 256 bytes besides the base; they carry a value that decodes. */
    @Test
    void everyLinkLimitIsKept() throws Exception {
        SplittableRandom rnd = new SplittableRandom(20261115);
        String[] bases = {BASE, "http://127.0.0.1/v/", "https://viewer.example/a/rather/long/path/to/the/tempo/timeline/index.html"};
        for (int round = 0; round < 60; round++) {
            Snapshot snap = HeaderTest.randomSnapshot(rnd, round, new HashSet<>());
            String base = bases[round % bases.length], whole = snap.toLink(base, Integer.MAX_VALUE);
            assertEquals(snap.toHeader(Integer.MAX_VALUE), LinkTest.unpack(whole, base));
            for (int i = 0; i < 12; i++) {
                int limit = base.length() + 256 + rnd.nextInt(Math.max(1, whole.length() - base.length() - 255));
                String link = snap.toLink(base, limit);
                assertTrue(link.length() <= limit, () -> limit + ": " + link);
                assertFalse((Boolean) WireDecoder.decode(LinkTest.unpack(link, base)).get("truncated"), link);
            }
        }
    }

    /**
     * A sample of values and links from random recordings, checked outside the JVM: the reference ladder
     * of {@code tools/tempo_ref.py} fits each value to the same bytes, its link decoder and the viewer's
     * parser read every value and link, and {@code tools/tempo --check} accepts them all. Skipped where
     * python3 or node is not installed.
     */
    @Test
    void theReferenceAndTheViewersAgree() throws Exception {
        SplittableRandom rnd = new SplittableRandom(20261116);
        StringBuilder corpus = new StringBuilder();
        Snapshot ladder = ladderTrace();
        for (int limit : new int[] {100, 256, 300, 400, 500}) line(corpus, "header", limit, ladder, ladder.toHeader(limit));
        for (int round = 0; round < 80; round++) {
            Snapshot snap = HeaderTest.randomSnapshot(rnd, round, new HashSet<>());
            int length = snap.toHeader(Integer.MAX_VALUE).length();
            for (int i = 0; i < 3; i++) {
                int limit = 256 + rnd.nextInt(Math.max(1, length - 255));
                line(corpus, "header", limit, snap, snap.toHeader(limit));
            }
            int limit = BASE.length() + 256 + rnd.nextInt(Math.max(1, length - 200));
            line(corpus, "link", limit, snap, snap.toLink(BASE, limit));
        }
        Path file = Files.createTempFile("tempo-java-outputs", ".tsv");
        try {
            Files.write(file, corpus.toString().getBytes(StandardCharsets.UTF_8));
            run("python3", "tests/check_java_outputs.py", file.toString());
            run("node", "tests/check_java_outputs.mjs", file.toString());
        } finally {
            Files.delete(file);
        }
    }

    private static void line(StringBuilder corpus, String kind, int limit, Snapshot snap, String output) {
        corpus.append(kind).append('\t').append(limit).append('\t').append(snap.toHeader(Integer.MAX_VALUE)).append('\t')
                .append(output).append('\n');
    }

    /** Runs a check script from the repository root; skips the test if the interpreter is missing. */
    static void run(String... command) throws Exception {
        Process p;
        try {
            p = new ProcessBuilder(command).directory(GoldenTest.DATA.toAbsolutePath().getParent().toFile())
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            Assumptions.abort(command[0] + " is not installed");
            return;
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), () -> String.join(" ", command) + ":\n" + out);
    }
}
