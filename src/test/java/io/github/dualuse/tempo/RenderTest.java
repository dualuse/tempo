package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.Name;
import io.github.dualuse.tempo.Tempo.Options;
import io.github.dualuse.tempo.Tempo.Snapshot;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

import static io.github.dualuse.tempo.Tempo.STEP;
import static io.github.dualuse.tempo.Tempo.NOOP;
import static io.github.dualuse.tempo.TempoTest.onOtherThread;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The renderers of the labelled phases: {@code Runtime} text, generic {@code Server-Timing}, {@code
 * forEachTop}, {@code printOnClose}.
 */
class RenderTest {

    static final Name SEARCH = TempoTest.SEARCH, LOAD = TempoTest.LOAD, MATCH = TempoTest.MATCH,
            DECODE = Tempo.name("Decode Input"), RERANK = Tempo.name("Rerank & Filter=Top(10)"),
            ROUTING = Tempo.name("routing"), SCORE = Tempo.name("#Score: v2");

    final TempoTest.Clock clock = new TempoTest.Clock();

    Options opts() {
        return new Options().clock(clock);
    }

    @AfterEach
    void noTraceLeftOnThread() {
        TempoTest.assertNothingLeftOpen();
    }

    /** Three labelled phases of the root, back to back from its start; 1,751,234,572 ns in all. */
    private Trace threePhases(Trace t) {
        t.phase(DECODE).label("Decode Input #3");
        clock.add(1_234_567);
        t.phase(RERANK).label("Rerank & Filter = v2(a:b)");
        clock.add(250_000_000);
        t.phase(MATCH).label("match");
        clock.add(1_500_000_000);
        t.done(MATCH);
        clock.add(5);
        t.close();
        return t;
    }

    @Test
    void runtimeIsByteExact() {
        Trace t = threePhases(Tempo.trace(SEARCH, opts()));
        assertEquals("1.751s = 0.001s (Decode Input #3) + 0.250s (Rerank & Filter = v2(a:b)) + 1.500s (match)", t.toRuntime(3));
        assertEquals("1.751234572s = 0.001234567s (Decode Input #3) + 0.25s (Rerank & Filter = v2(a:b)) + 1.5s (match)",
                t.toRuntime(-1));
        assertEquals("2s = 0s (Decode Input #3) + 0s (Rerank & Filter = v2(a:b)) + 2s (match)", t.toRuntime(0));
        assertEquals("1.7512345720000s = 0.0012345670000s (Decode Input #3) + 0.2500000000000s (Rerank & Filter = v2(a:b))"
                + " + 1.5000000000000s (match)", t.toRuntime(13));
        assertEquals("9.0s = 0.001234567s (Decode Input #3) + 0.25s (Rerank & Filter = v2(a:b)) + 1.5s (match)",
                t.toRuntime(-1, 9_000_000_000L));
        assertEquals("0.000s = 0.001s (Decode Input #3) + 0.250s (Rerank & Filter = v2(a:b)) + 1.500s (match)",
                t.toRuntime(3, 499_999));
        assertEquals(0, t.misuses());
    }

    @Test
    void serverTimingIsByteExact() {
        Trace t = threePhases(Tempo.trace(SEARCH, opts()));
        assertEquals("decode-input;dur=1.235, rerank-filter-top-10-;dur=250.000, match;dur=1500.000, total;dur=1751.235",
                t.toServerTiming());

        Trace u = Tempo.trace(SEARCH, opts());
        u.phase(SCORE).label("Score");
        clock.add(7);
        u.done();
        clock.add(493);
        u.close();
        assertEquals("-score-v2;dur=0.000, total;dur=0.001", u.toServerTiming());
    }

    @Test
    void forEachTopVisitsTheLabelledPhasesInStartOrder() {
        Trace t = threePhases(Tempo.trace(SEARCH, opts()));
        List<String> got = new ArrayList<>();
        t.forEachTop((name, label, start, dur) -> got.add(name + "|" + label + "|" + start + "+" + dur));
        assertEquals(List.of("Decode Input|Decode Input #3|0+1234567",
                "Rerank & Filter=Top(10)|Rerank & Filter = v2(a:b)|1234567+250000000",
                "match|match|251234567+1500000000"), got);
    }

    @Test
    void aTraceWithoutLabelledPhasesPrintsTheTotalOnly() {
        Trace t = Tempo.trace(SEARCH, opts());
        t.phase(MATCH);
        clock.add(250_000_000);
        t.done(LOAD); // a phase without a label, and one named after the fact
        t.close();
        assertEquals("0.250s", t.toRuntime(3));
        assertEquals("0.25s", t.toRuntime(-1));
        assertEquals("total;dur=250.000", t.toServerTiming());
        t.forEachTop((name, label, start, dur) -> fail("no labelled phase"));
    }

    @Test
    void phasesWithoutALabelDoNotChangeTheRuntimeText() throws Exception {
        Trace plain = threePhases(Tempo.trace(SEARCH, opts()));

        Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
        Span s = t;
        s.phase(ROUTING);                                   // a phase without a label
        clock.add(3_000);
        s.phase(DECODE).label("Decode Input #3");
        try (Span x = s.span(LOAD)) {                       // a child of the root, which no phase adopts
            x.phase(SCORE).label("Nested phase");           // labelled, but not a phase of the root
            clock.add(1_234_567);
        }
        s.phase(RERANK).label("Rerank & Filter = v2(a:b)");
        try (Span d = s.step(SCORE)) {
            clock.add(100);
        }
        Tempo.task(LOAD).tryBegin().close();                    // inline task
        clock.add(250_000_000 - 100);
        s.phase(MATCH).label("match");
        clock.add(1_500_000_000);
        s.done();
        s.phase(LOAD).fail();                               // a failed phase without a label, no time
        s.phase(ROUTING);                                   // still open at close
        clock.add(77);
        t.close();

        long total = plain.elapsedNanos();
        for (int digits = -1; digits <= 9; digits++) {
            assertEquals(plain.toRuntime(digits), t.toRuntime(digits, total));
            assertEquals(plain.toRuntime(digits), t.snapshot().toRuntime(digits, total));
        }
        assertEquals("1.751s = 0.001s (Decode Input #3) + 0.250s (Rerank & Filter = v2(a:b)) + 1.500s (match)",
                t.toRuntime(3, total));
        assertEquals("1.751237644s = 0.001234567s (Decode Input #3) + 0.25s (Rerank & Filter = v2(a:b)) + 1.5s (match)",
                t.toRuntime(-1));
        assertTrue(TempoTest.tree(t).contains("phase 'Nested phase'"), TempoTest.tree(t));
        assertEquals(0, t.misuses());
    }

    @Test
    void anOpenTraceRendersUpToNow() throws Exception {
        Trace t = Tempo.trace(SEARCH, opts());
        Span s = t;
        s.phase(DECODE).label("Decode");
        clock.add(100_000_000);
        s.phase(RERANK).label("Rerank & Filter=Top(10)");   // open and labelled
        clock.add(20_000_000);
        assertEquals("0.120s = 0.100s (Decode) + 0.020s (Rerank & Filter=Top(10))", t.toRuntime(3));
        assertEquals("decode-input;dur=100.000, rerank-filter-top-10-;dur=20.000, total;dur=120.000", t.toServerTiming());
        clock.add(5_000_000);
        assertEquals("0.125s = 0.1s (Decode) + 0.025s (Rerank & Filter=Top(10))", t.toRuntime(-1));
        assertEquals("0.125s = 0.1s (Decode) + 0.025s (Rerank & Filter=Top(10))", t.snapshot().toRuntime(-1));
        String[] seen = new String[1];
        assertNull(onOtherThread(() -> seen[0] = t.toRuntime(3)));
        assertEquals("0.125s = 0.100s (Decode) + 0.025s (Rerank & Filter=Top(10))", seen[0]);
        t.close();
        clock.add(1_000_000);
        assertEquals("0.125s = 0.100s (Decode) + 0.025s (Rerank & Filter=Top(10))", t.toRuntime(3));
        assertEquals(0, t.misuses());
    }

    @Test
    void labelsPastTheFirstLabelTableAreRead() {
        Trace t = Tempo.trace(SEARCH, opts());
        StringBuilder expected = new StringBuilder("0.020s");
        for (int i = 0; i < 20; i++) {
            t.phase(LOAD).label("Load #" + i);
            clock.add(1_000_000);
            expected.append(i == 0 ? " = " : " + ").append("0.001s (Load #").append(i).append(')');
        }
        t.done();
        t.close();
        assertEquals(expected.toString(), t.toRuntime(3));
        assertEquals(expected.toString(), t.snapshot().toRuntime(3));
    }

    @Test
    void snapshotRendersAfterRelease() {
        Trace t = threePhases(Tempo.trace(SEARCH, opts()));
        Snapshot before = t.snapshot();
        t.release();
        assertEquals("0.000s", t.toRuntime(3));
        assertEquals("0.0s", t.toRuntime(-1));
        assertEquals("total;dur=0.000", t.toServerTiming());
        t.forEachTop((name, label, start, dur) -> fail("released"));
        assertEquals("1.751s = 0.001s (Decode Input #3) + 0.250s (Rerank & Filter = v2(a:b)) + 1.500s (match)", before.toRuntime(3));
        assertEquals("decode-input;dur=1.235, rerank-filter-top-10-;dur=250.000, match;dur=1500.000, total;dur=1751.235",
                before.toServerTiming());
        List<String> got = new ArrayList<>();
        before.forEachTop((name, label, start, dur) -> got.add(name + "|" + label + "|" + start + "+" + dur));
        assertEquals(3, got.size());
        assertEquals("0.000s", t.snapshot().toRuntime(3));
    }

    @Test
    void printOnCloseWritesOneLine() {
        List<String> out = new ArrayList<>();
        Trace t = Tempo.trace(SEARCH, opts()).printOnClose(out::add, "Batch #1: x=(a&b)");
        t.phase(DECODE).label("Decode");
        clock.add(500_000_000);
        t.phase(RERANK).label("Rerank & Filter=Top(10)");
        clock.add(1_000_000_000);
        assertEquals(List.of(), out);
        t.close();                                   // the root's close prints, not just Trace.close
        t.close();
        assertEquals(List.of("Batch #1: x=(a&b): 1.5s = 0.5s (Decode) + 1.0s (Rerank & Filter=Top(10))"), out);

        out.clear();
        try (Trace u = Tempo.trace(SEARCH, opts()).printOnClose(out::add, "Job")) {
            clock.add(250_000_000);
        }
        assertEquals(List.of("Job: 0.25s"), out);

        out.clear();
        try (Trace u = Tempo.trace(SEARCH, opts()).printOnClose(out::add, null)) {
            clock.add(1);
        }
        assertEquals(List.of("1.0E-9s"), out);

        out.clear();
        Trace closed = Tempo.trace(SEARCH, opts());
        clock.add(2_000_000_000);
        closed.close();
        closed.printOnClose(out::add, "Late");              // already closed: printed at once
        assertEquals(List.of("Late: 2.0s"), out);
        assertEquals(0, t.misuses() + closed.misuses());
    }

    @Test
    void printOnCloseMisuse() throws Exception {
        Trace t = Tempo.trace(SEARCH, opts());
        t.printOnClose(null, "x");
        assertNull(onOtherThread(() -> t.printOnClose(line -> fail("foreign"), "x")));
        t.printOnClose(line -> { throw new IllegalStateException("printer"); }, "x");
        t.close();                                          // the printer's throw is counted, not thrown
        assertEquals(3, t.misuses());

        Trace s = Tempo.trace(SEARCH, opts().strict(true));
        s.printOnClose(line -> { throw new IllegalStateException("printer"); }, "x");
        AssertionError e = assertThrows(AssertionError.class, s::close);
        assertEquals("printer", e.getCause().getMessage());
        assertSame(NOOP, Tempo.current());                  // the root closed before the rethrow
        assertThrows(AssertionError.class, () -> s.printOnClose(null, "x"));
    }

    @Test
    void runtimeReadsTheRootLaneInPlace() {
        Trace t = Tempo.trace(SEARCH, opts());
        Span s = t;
        for (int i = 0; i < 5_000; i++) s.span(LOAD).set(SCORE, i).close();
        s.phase(DECODE).label("Decode Input #3");
        s.phase(MATCH).label("match");
        t.close();
        int calls = 1_000;
        Runnable reads = () -> {
            for (int i = 0; i < calls; i++) t.toRuntime(3);
        };
        for (int i = 0; i < 3; i++) reads.run();
        long bytes = TempoTest.allocatedBy(reads);
        // measured 648 B: the phase arrays and the text; a copy of the 5,002 rows would be over 200 KB
        assertTrue(bytes <= 1_024L * calls, bytes + " B for " + calls + " calls");
        assertEquals("0.000s = 0.000s (Decode Input #3) + 0.000s (match)", t.toRuntime(3));
    }

    // Property tests ---------------------------------------------------------------------------------

    /** Nanosecond values over every magnitude, with the edges that switch Double.toString's notation. */
    private static long[] samples(int n) {
        SplittableRandom r = new SplittableRandom(20261113);
        long[] v = new long[n];
        long[] edges = {0, 1, 5, 999, 999_999, 1_000_000, 1_000_001, 499_999, 500_000, 1_500_000_000,
                9_999_999_999_999_999L, 10_000_000_000_000_000L, 999_999_999_999_999L, 1_000_000_000_000_000L,
                Long.MAX_VALUE, Long.MAX_VALUE - 1};
        System.arraycopy(edges, 0, v, 0, edges.length);
        for (int i = edges.length; i < n; i++) {
            long bound = (long) Math.pow(10, r.nextInt(1, 19));
            v[i] = r.nextLong(bound);
        }
        return v;
    }

    @Test
    void runtimeWithoutDigitsIsDoubleToString() {
        Trace t = Tempo.trace(SEARCH, opts());
        t.close();
        for (long nanos : samples(200_000)) assertEquals(Double.toString(nanos / 1e9) + "s", t.toRuntime(-1, nanos));
    }

    @Test
    void runtimeWithDigitsRoundsTheExactValueHalfUp() {
        Trace t = Tempo.trace(SEARCH, opts());
        t.close();
        boolean formatter = Runtime.version().feature() >= 21; // since then %f rounds the shortest repr, as Double.toString
        SplittableRandom r = new SplittableRandom(7);
        for (long nanos : samples(200_000)) {
            long v = r.nextInt(8) == 0 ? -nanos : nanos;
            int digits = r.nextInt(13);
            String got = t.toRuntime(digits, v);
            if (Math.abs(v) < 1_000_000_000_000_000L) { // like %f, a negative value keeps its sign when it rounds to 0
                String exact = BigDecimal.valueOf(Math.abs(v), 9).setScale(digits, RoundingMode.HALF_UP).toPlainString();
                assertEquals((v < 0 ? "-" : "") + exact + "s", got, "nanos=" + v);
            }
            if (formatter) assertEquals(String.format(Locale.ROOT, "%." + digits + "f", v / 1e9) + "s", got, "nanos=" + v);
        }
    }

    @Test
    void serverTimingMillisecondsRoundHalfUp() {
        for (long nanos : samples(100_000)) {
            String expected = BigDecimal.valueOf(nanos, 6).setScale(3, RoundingMode.HALF_UP).toPlainString();
            assertEquals(expected, Tempo.Tops.fixed(new StringBuilder(), nanos, 6, 3).toString());
        }
    }
}
