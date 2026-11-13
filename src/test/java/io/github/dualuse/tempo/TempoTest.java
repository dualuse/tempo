package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.Name;
import io.github.dualuse.tempo.Tempo.Options;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static io.github.dualuse.tempo.Tempo.COARSE;
import static io.github.dualuse.tempo.Tempo.DEEP;
import static io.github.dualuse.tempo.Tempo.DETAIL;
import static io.github.dualuse.tempo.Tempo.NOOP;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TempoTest {

    static final Name SEARCH = Tempo.name("search"), DECODE = Tempo.name("decode"), RERANK = Tempo.name("rerank"),
            LOAD = Tempo.name("load"), MATCH = Tempo.name("match"), JOIN = Tempo.name("join"),
            BYTES = Tempo.name("bytes"), SCORE = Tempo.name("score");

    /** Manually advanced clock; starts away from zero so the epoch subtraction is exercised. */
    static final class Clock implements LongSupplier {
        long t = 1_000_000_000L;

        @Override
        public long getAsLong() {
            return t;
        }

        void add(long nanos) {
            t += nanos;
        }
    }

    /** Bytes for start, one child span, close and release of a trace; measured 848 B on JDK 24. */
    static final long SETUP_CEILING = 1024;

    final Clock clock = new Clock();

    Options opts() {
        return new Options().clock(clock);
    }

    Options strict() {
        return opts().strict(true);
    }

    @AfterEach
    void noTraceLeftOnThread() {
        assertSame(NOOP, Tempo.current(), "a test left a span open on the test thread");
    }

    /** One line per span: indent, name, start+duration and the flags/attributes that are set. */
    static String tree(Trace t) {
        StringBuilder b = new StringBuilder();
        t.forEachSpan(s -> {
            b.append("  ".repeat(s.depth())).append(s.name()).append(' ').append(s.startNanos()).append('+').append(s.durationNanos());
            if (s.level() == DETAIL) b.append(" detail");
            if (s.level() == DEEP) b.append(" deep");
            if (s.open()) b.append(" open");
            if (s.failed()) b.append(" failed");
            if (s.cancelled()) b.append(" cancelled");
            if (s.waiting()) b.append(" waiting");
            if (s.healed()) b.append(" healed");
            if (s.misuse()) b.append(" misuse");
            for (int i = 0; i < s.attributes(); i++) {
                b.append(' ').append(s.attributeKey(i)).append('=');
                if (s.attributeIsDouble(i)) b.append(s.doubleAttribute(i));
                else b.append(s.longAttribute(i));
            }
            b.append('\n');
        });
        return b.toString();
    }

    static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    static Throwable onOtherThread(Runnable r) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        t.start();
        t.join();
        return thrown.get();
    }

    // ---- names --------------------------------------------------------------------------------------

    @Test
    void namesAreInterned() {
        assertSame(SEARCH, Tempo.name("search"));
        assertNotSame(SEARCH, DECODE);
        assertEquals("search", SEARCH.toString());
        assertSame(Tempo.name(null), Tempo.name(""));
        assertEquals("?", Tempo.name(null).toString());
        assertSame(Tempo.name("?"), Tempo.name(null));
    }

    // ---- spans and nesting ----------------------------------------------------------------------------

    @Test
    void rootSpanHasExactDuration() {
        Trace t = Tempo.start(SEARCH, opts());
        clock.add(250);
        assertEquals(250, t.elapsedNanos());
        t.close();
        clock.add(1000);
        assertEquals(250, t.elapsedNanos());
        assertEquals(lines("search 0+250"), tree(t));
        assertEquals(0, t.misuses());
    }

    @Test
    void nestedSpansHaveExactStartsAndDurations() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span root = t.root();
            clock.add(10);
            try (Span d = root.span(DECODE)) {
                clock.add(5);
                try (Span l = d.span(LOAD)) {
                    clock.add(20);
                }
                clock.add(3);
            }
            clock.add(7);
            try (Span r = root.span(RERANK)) {
                clock.add(40);
            }
            clock.add(1);
            t.close();
            assertEquals(lines(
                    "search 0+86",
                    "  decode 10+28",
                    "    load 15+20",
                    "  rerank 45+40"), tree(t));
        }
    }

    @Test
    void forEachSpanReportsIdsParentsAndThread() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span root = t.root();
            root.span(DECODE).span(LOAD).close();
            root.span(RERANK).close(); // lands under the still-open decode
            List<String> seen = new ArrayList<>();
            t.close();
            t.forEachSpan(s -> seen.add(s.id() + "<" + s.parent() + ":" + s.name() + "@" + s.thread()));
            String th = Thread.currentThread().getName();
            assertEquals(List.of("0<-1:search@" + th, "1<0:decode@" + th, "2<1:load@" + th, "3<1:rerank@" + th), seen);
        }
    }

    @Test
    void parentIsTheInnermostOpenSpanOfTheLane() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span s = t.root();
            try (Span a = s.span(DECODE)) {
                clock.add(1);
                s.span(LOAD).close(); // called on the root handle, lands under DECODE
                clock.add(1);
            }
            t.close();
            assertEquals(lines(
                    "search 0+2",
                    "  decode 0+2",
                    "    load 1+0"), tree(t));
            assertEquals(0, t.misuses());
        }
    }

    @Test
    void openSpansRenderAsOpenAtOneNow() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            clock.add(10);
            Span d = t.root().span(DECODE);
            clock.add(30);
            assertEquals(30, d.elapsedNanos());
            assertEquals(lines(
                    "search 0+40 open",
                    "  decode 10+30 open"), tree(t));
            d.close();
            clock.add(5);
            assertEquals(30, d.elapsedNanos());
        }
    }

    @Test
    void spansOfARecordingLaneAreMarkedLive() {
        Trace t = Tempo.start(SEARCH, opts());
        t.root().span(DECODE).close();
        List<Boolean> live = new ArrayList<>();
        t.forEachSpan(s -> live.add(s.live()));
        assertEquals(List.of(true, true), live);
        t.close();
        t.root().fail(); // a late annotation leaves the lane idle again
        live.clear();
        t.forEachSpan(s -> live.add(s.live()));
        assertEquals(List.of(false, false), live);
    }

    @Test
    void elapsedOfNoopIsZero() {
        assertEquals(0, NOOP.elapsedNanos());
        assertFalse(NOOP.recording());
        assertFalse(NOOP.detailed());
    }

    @Test
    void stringNamesAreLookedUp() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            t.root().span("decode").close();
            t.close();
            assertEquals(lines("search 0+0", "  decode 0+0"), tree(t));
        }
    }

    // ---- misuse, non-strict -------------------------------------------------------------------------

    @Test
    void closingAParentHealsOpenChildren() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span d = t.root().span(DECODE);
            Span l = d.span(LOAD);
            clock.add(8);
            d.close();
            assertEquals(1, t.misuses());
            l.close(); // already healed
            assertEquals(2, t.misuses());
            t.close();
            assertEquals(lines(
                    "search 0+8",
                    "  decode 0+8",
                    "    load 0+8 healed"), tree(t));
        }
    }

    @Test
    void doubleCloseIsCountedAndChangesNothing() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span d = t.root().span(DECODE);
            clock.add(4);
            d.close();
            clock.add(4);
            d.close();
            assertEquals(1, t.misuses());
            t.close();
            t.close(); // a trace closes idempotently
            assertEquals(1, t.misuses());
            assertEquals(lines("search 0+8", "  decode 0+4"), tree(t));
        }
    }

    @Test
    void useAfterCloseReturnsNoop() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span d = t.root().span(DECODE);
            d.close();
            assertSame(NOOP, d.span(LOAD));
            assertEquals(1, t.misuses());
            t.close();
            assertSame(NOOP, t.root().span(LOAD));
            assertEquals(2, t.misuses());
            assertEquals(lines("search 0+0", "  decode 0+0"), tree(t));
        }
    }

    @Test
    void closingTheTraceHealsForgottenSpans() {
        Trace t = Tempo.start(SEARCH, opts());
        t.root().span(DECODE);
        clock.add(3);
        t.close();
        assertEquals(1, t.misuses());
        assertEquals(lines("search 0+3", "  decode 0+3 healed"), tree(t));
    }

    @Test
    void foreignThreadCallsAreIgnoredAndCounted() throws Exception {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span d = t.root().span(DECODE);
            AtomicReference<Span> child = new AtomicReference<>();
            assertNull(onOtherThread(() -> {
                d.close();
                child.set(d.span(LOAD));
                d.set(BYTES, 1).fail();
            }));
            assertSame(NOOP, child.get());
            assertEquals(4, t.misuses());
            clock.add(6);
            t.close(); // the owner heals the row the foreign thread failed to close
            assertEquals(5, t.misuses());
            assertEquals(lines("search 0+6", "  decode 0+6 healed"), tree(t));
        }
    }

    @Test
    void foreignThreadCannotCloseTheTrace() throws Exception {
        Trace t = Tempo.start(SEARCH, opts());
        assertNull(onOtherThread(t::close));
        assertEquals(1, t.misuses());
        clock.add(2);
        t.close();
        assertEquals(lines("search 0+2"), tree(t));
    }

    @Test
    void nullAndEmptyNamesBecomeQuestionMark() {
        Trace t = Tempo.start(null, opts());
        Span root = t.root();
        root.span((Name) null).close();
        root.span((String) null).close();
        root.span("").close();
        root.span(Tempo.name("")).close(); // an interned "?" is a legitimate name
        root.set(null, 1);
        t.close();
        assertEquals(5, t.misuses());
        assertEquals(lines(
                "? 0+0 misuse ?=1",
                "  ? 0+0 misuse",
                "  ? 0+0 misuse",
                "  ? 0+0 misuse",
                "  ? 0+0"), tree(t));
    }

    @Test
    void depthIsCappedAt35() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span s = t.root();
            for (int d = 1; d < 35; d++) s = s.span(LOAD);
            assertTrue(s.recording());
            assertSame(NOOP, s.span(MATCH));
            assertEquals(1, t.dropped());
            assertEquals(0, t.misuses());
            t.close();
            int[] spans = {0}, maxDepth = {0};
            t.forEachSpan(v -> {
                spans[0]++;
                maxDepth[0] = Math.max(maxDepth[0], v.depth());
            });
            assertEquals(35, spans[0]);
            assertEquals(34, maxDepth[0]);
            assertEquals(1, t.misuses()); // one close healed the 34 open spans
        }
    }

    @Test
    void rowsAreCappedPerLane() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span root = t.root();
            for (int i = 1; i < Tempo.Lane.MAX_ROWS; i++) root.span(LOAD).close();
            assertFalse(t.root().span(LOAD).recording());
            assertEquals(1, t.dropped());
            t.close();
            int[] n = {0};
            t.forEachSpan(v -> n[0]++);
            assertEquals(Tempo.Lane.MAX_ROWS, n[0]);
        }
    }

    // ---- misuse, strict -------------------------------------------------------------------------------

    @Test
    void strictThrowsOnDoubleClose() {
        try (Trace t = Tempo.start(SEARCH, strict())) {
            Span d = t.root().span(DECODE);
            d.close();
            AssertionError e = assertThrows(AssertionError.class, d::close);
            assertEquals("Tempo misuse: span used after close", e.getMessage());
            assertEquals(1, t.misuses());
        }
    }

    @Test
    void strictThrowsOnOutOfOrderCloseAfterHealing() {
        Trace t = Tempo.start(SEARCH, strict());
        Span d = t.root().span(DECODE);
        d.span(LOAD);
        clock.add(5);
        assertThrows(AssertionError.class, d::close);
        t.close();
        assertEquals(lines("search 0+5", "  decode 0+5", "    load 0+5 healed"), tree(t));
    }

    @Test
    void strictThrowsOnNullNamesBeforeRecording() {
        Trace t = Tempo.start(SEARCH, strict());
        assertThrows(AssertionError.class, () -> t.root().span((Name) null));
        assertThrows(AssertionError.class, () -> t.root().span(""));
        assertThrows(AssertionError.class, () -> t.root().set(null, 1L));
        assertThrows(AssertionError.class, () -> Tempo.start(null, strict()));
        t.close();
        assertEquals(lines("search 0+0"), tree(t));
    }

    @Test
    void strictThrowsOnTheForeignThread() throws Exception {
        try (Trace t = Tempo.start(SEARCH, strict())) {
            Throwable e = onOtherThread(() -> t.root().span(DECODE));
            assertTrue(e instanceof AssertionError);
            assertEquals("Tempo misuse: span used on a foreign thread", e.getMessage());
        }
    }

    @Test
    void strictThrowsOnRefusedRaise() {
        try (Trace t = Tempo.start(SEARCH, strict())) {
            assertTrue(t.raise(DETAIL));
            assertThrows(AssertionError.class, () -> t.raise(DEEP));
            assertEquals(DETAIL, t.level());
        }
    }

    @Test
    void strictStaysSilentOnCorrectUse() {
        try (Trace t = Tempo.start(SEARCH, strict().level(DEEP))) {
            t.root().run(DECODE, () -> t.root().detail(LOAD).set(BYTES, 3).close());
            t.root().deep(MATCH).fail().close();
            assertEquals(0, t.misuses());
        }
    }

    // ---- levels ---------------------------------------------------------------------------------------

    @Test
    void coarseRecordsNoDetail() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span root = t.root();
            assertEquals(COARSE, t.level());
            assertFalse(root.detailed());
            Span d = root.detail(LOAD);
            assertSame(NOOP, d);
            assertSame(NOOP, d.span(MATCH));
            assertSame(NOOP, d.set(BYTES, 1).set(SCORE, 0.5).fail().cancel().waiting());
            d.close();
            assertSame(NOOP, root.deep(MATCH));
            t.close();
            assertEquals(lines("search 0+0"), tree(t));
            assertEquals(0, t.misuses());
        }
    }

    @Test
    void detailAndDeepFollowTheLevel() {
        try (Trace t = Tempo.start(SEARCH, opts().level(DETAIL))) {
            Span root = t.root();
            assertTrue(root.detailed());
            root.detail(LOAD).close();
            assertSame(NOOP, root.deep(MATCH));
            t.close();
            assertEquals(lines("search 0+0", "  load 0+0 detail"), tree(t));
        }
        try (Trace t = Tempo.start(SEARCH, opts().level(DEEP))) {
            try (Span l = t.root().detail(LOAD)) {
                l.deep(MATCH).close();
            }
            t.close();
            assertEquals(lines("search 0+0", "  load 0+0 detail", "    match 0+0 deep"), tree(t));
        }
    }

    @Test
    void levelsAreClamped() {
        try (Trace t = Tempo.start(SEARCH, opts().level(7))) {
            assertEquals(DEEP, t.level());
        }
        try (Trace t = Tempo.start(SEARCH, opts().level(-3))) {
            assertEquals(COARSE, t.level());
        }
    }

    @Test
    void levelRaisesOnceBeforeAnyDetailSpan() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span root = t.root();
            assertSame(NOOP, root.detail(LOAD)); // not recorded, so it does not block the raise
            assertTrue(t.raise(COARSE));         // asking for what is there is not a raise
            assertTrue(t.raise(DETAIL));
            assertEquals(DETAIL, t.level());
            root.detail(LOAD).close();
            assertFalse(t.raise(DEEP));
            assertEquals(DETAIL, t.level());
            assertTrue(t.raise(DETAIL));
            assertEquals(1, t.misuses());
        }
    }

    @Test
    void levelCannotBeRaisedAfterDetailSpansExist() {
        try (Trace t = Tempo.start(SEARCH, opts().level(DETAIL))) {
            t.root().detail(LOAD).close();
            assertFalse(t.raise(DEEP));
            assertEquals(DETAIL, t.level());
            assertEquals(1, t.misuses());
        }
    }

    // ---- attributes and flags -------------------------------------------------------------------------

    @Test
    void attributesKeepTypeValueAndOrder() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span root = t.root();
            try (Span l = root.span(LOAD)) {
                l.set(BYTES, 48213L).set(SCORE, 0.83).set(BYTES, -1L);
            }
            root.set(SCORE, -0.0).set(BYTES, Long.MAX_VALUE);
            t.close();
            assertEquals(lines(
                    "search 0+0 score=-0.0 bytes=9223372036854775807",
                    "  load 0+0 bytes=48213 score=0.83 bytes=-1"), tree(t));
            t.forEachSpan(v -> {
                if (v.depth() == 1) {
                    assertEquals(0.83, v.doubleAttribute(1));
                    assertEquals(0, v.longAttribute(1));
                    assertEquals(48213.0, v.doubleAttribute(0));
                    assertThrows(IndexOutOfBoundsException.class, () -> v.attributeKey(3));
                }
            });
        }
    }

    @Test
    void flagsAreRecorded() {
        try (Trace t = Tempo.start(SEARCH, opts().level(DETAIL))) {
            Span root = t.root();
            root.span(DECODE).fail().close();
            root.span(LOAD).fail(new IOException("gone")).close();
            root.span(MATCH).cancel().close();
            try (Span j = root.detail(JOIN).waiting()) {
                clock.add(9);
            }
            t.close();
            assertEquals(lines(
                    "search 0+9",
                    "  decode 0+0 failed",
                    "  load 0+0 failed",
                    "  match 0+0 cancelled",
                    "  join 0+9 detail waiting"), tree(t));
        }
    }

    @Test
    void annotationsWorkAfterCloseForCatchBlocks() {
        Trace t = Tempo.start(SEARCH, opts());
        Span d = t.root().span(DECODE);
        try (d) {
            throw new IllegalStateException("bad input");
        } catch (IllegalStateException e) {
            d.fail(e).set(BYTES, 7);
        }
        t.close();
        t.root().fail().set(SCORE, 1.5); // the lane root has closed too
        assertEquals(0, t.misuses());
        assertEquals(lines("search 0+0 failed score=1.5", "  decode 0+0 failed bytes=7"), tree(t));
        t.release();
        t.root().fail(); // after release: ignored
        assertEquals("", tree(t));
    }

    // ---- run / call -----------------------------------------------------------------------------------

    @Test
    void runAndCallTimeTheirBody() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span root = t.root();
            root.run(DECODE, () -> clock.add(12));
            int n = root.call(LOAD, () -> {
                clock.add(30);
                return 42;
            });
            assertEquals(42, n);
            t.close();
            assertEquals(lines("search 0+42", "  decode 0+12", "  load 12+30"), tree(t));
        }
    }

    @Test
    void runAndCallFlagFailuresAndRethrowUnwrapped() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span root = t.root();
            IOException io = new IOException("disk");
            try {
                root.run(LOAD, () -> {
                    clock.add(3);
                    throw io;
                });
                fail();
            } catch (IOException e) { // the checked type is preserved
                assertSame(io, e);
            }
            RuntimeException boom = new IllegalArgumentException("boom");
            assertSame(boom, assertThrows(IllegalArgumentException.class, () -> root.call(MATCH, () -> {
                throw boom;
            })));
            t.close();
            assertEquals(lines("search 0+3", "  load 0+3 failed", "  match 3+0 failed"), tree(t));
            assertEquals(0, t.misuses());
        }
    }

    @Test
    void runOnNoopStillRunsTheBody() throws Exception {
        int[] ran = {0};
        NOOP.run(LOAD, () -> ran[0]++);
        int seven = NOOP.call(LOAD, () -> 7);
        assertEquals(7, seven);
        assertEquals(1, ran[0]);
    }

    // ---- thread slot ----------------------------------------------------------------------------------

    @Test
    void currentFollowsTheInnermostOpenSpan() {
        assertSame(NOOP, Tempo.current());
        try (Trace t = Tempo.start(SEARCH, opts())) {
            assertSame(t.root(), Tempo.current());
            try (Span d = t.root().span(DECODE)) {
                assertSame(d, Tempo.current());
                Tempo.current().span(LOAD).close();
            }
            assertSame(t.root(), Tempo.current());
            t.close();
            assertSame(NOOP, Tempo.current());
            assertEquals(lines("search 0+0", "  decode 0+0", "    load 0+0"), tree(t));
        }
    }

    @Test
    void nestedTracesRestoreTheOuterOne() {
        try (Trace outer = Tempo.start(SEARCH, opts())) {
            try (Trace inner = Tempo.start(RERANK, opts())) {
                assertSame(inner.root(), Tempo.current());
            }
            assertSame(outer.root(), Tempo.current());
        }
        assertSame(NOOP, Tempo.current());
    }

    @Test
    void tracesClosedOutOfOrderLeaveNoStaleCurrent() {
        Trace a = Tempo.start(SEARCH, opts());
        Trace b = Tempo.start(DECODE, opts());
        Trace c = Tempo.start(RERANK, opts());
        b.close();
        assertSame(c.root(), Tempo.current());
        c.close();
        assertSame(a.root(), Tempo.current()); // skips b, which closed meanwhile
        a.close();
        assertSame(NOOP, Tempo.current());
    }

    @Test
    void otherThreadsSeeNoCurrentSpan() throws Exception {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            AtomicReference<Span> seen = new AtomicReference<>();
            onOtherThread(() -> seen.set(Tempo.current()));
            assertSame(NOOP, seen.get());
        }
    }

    // ---- release and memory ---------------------------------------------------------------------------

    @Test
    void releaseEmptiesEveryReadAndIsIdempotent() {
        Trace t = Tempo.start(SEARCH, opts());
        t.root().span(DECODE).close();
        clock.add(5);
        t.close();
        assertEquals(5, t.elapsedNanos());
        t.release();
        t.release();
        assertEquals(0, t.elapsedNanos());
        assertEquals(0, t.root().elapsedNanos());
        assertEquals("", tree(t));
        assertSame(NOOP, t.root().span(LOAD)); // use after close
    }

    @Test
    void releaseBeforeCloseRetiresTheLaneAtClose() {
        Trace t = Tempo.start(SEARCH, opts());
        Span load = t.root().span(LOAD);
        clock.add(50);
        t.release();
        assertSame(load, Tempo.current());
        assertEquals(0, load.elapsedNanos()); // reads are empty from release on, not only once retired
        assertEquals(0, t.root().elapsedNanos());
        assertEquals(0, t.elapsedNanos());
        assertEquals("", tree(t));
        load.close();
        t.close();
        assertSame(NOOP, Tempo.current());
        assertEquals(0, t.elapsedNanos());
        assertEquals("", tree(t));
    }

    /** Bytes {@code body} allocates on this thread, net of the probe's own allocation. */
    static long allocatedBy(Runnable body) {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(mx.isThreadAllocatedMemorySupported() && mx.isThreadAllocatedMemoryEnabled());
        long tid = Thread.currentThread().getId();
        long a = mx.getThreadAllocatedBytes(tid), b = mx.getThreadAllocatedBytes(tid);
        body.run();
        long c = mx.getThreadAllocatedBytes(tid);
        return (c - b) - (b - a);
    }

    @Test
    void recordedSpansAllocateNothingOnceWarm() {
        int spans = 8_000;
        for (int round = 0; round < 3; round++) { // fills the chunk pool and loads every class on the path
            Trace warm = Tempo.start(SEARCH, opts().level(DETAIL));
            record(warm.root(), spans);
            warm.close();
            warm.release();
        }

        Trace t = Tempo.start(SEARCH, opts().level(DETAIL));
        record(t.root(), 1); // creates the depth-1 handle and the event table
        assertEquals(0, allocatedBy(() -> record(t.root(), spans)), "bytes allocated by " + spans + " spans");
        t.close();
        t.release();
    }

    @Test
    void disabledDetailAndDeepAllocateNothing() {
        Trace coarse = Tempo.start(SEARCH, opts()), detail = Tempo.start(RERANK, opts().level(DETAIL));
        Span c = coarse.root(), d = detail.root();
        Runnable calls = () -> {
            for (int i = 0; i < 8_000; i++) {
                c.detail(MATCH).close();
                c.deep(MATCH).close();
                d.deep(MATCH).close();
                NOOP.span(LOAD).set(BYTES, i).fail().close();
            }
        };
        calls.run();
        assertEquals(0, allocatedBy(calls));
        detail.close();
        coarse.close();
        assertEquals(0, coarse.misuses() + detail.misuses());
        assertEquals(lines("search 0+0"), tree(coarse));
    }

    @Test
    void traceSetupStaysUnderItsCeiling() {
        Options o = opts();
        Runnable lifecycle = () -> {
            Trace t = Tempo.start(SEARCH, o);
            t.root().span(LOAD).close();
            t.close();
            t.release();
        };
        for (int i = 0; i < 1_000; i++) lifecycle.run();
        long bytes = allocatedBy(lifecycle);
        assertTrue(bytes > 0 && bytes <= SETUP_CEILING, "trace setup took " + bytes + " B");
    }

    private void record(Span root, int spans) {
        for (int i = 0; i < spans; i++) {
            try (Span s = root.span(LOAD)) {
                s.set(BYTES, i);
                clock.add(1);
            }
            root.detail(MATCH).close();
        }
    }
}
