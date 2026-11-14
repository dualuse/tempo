package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.Handoff;
import io.github.dualuse.tempo.Tempo.Name;
import io.github.dualuse.tempo.Tempo.Options;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
        volatile long t = 1_000_000_000L; // tasks on other threads read it; latches and futures order the writes

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

    /**
     * One line per span: indent, name, start+duration, the flags that are set, for an aggregate its
     * count and extremes, for a handoff its queue wait, the thread where it differs from the root's, and
     * the attributes.
     */
    static String tree(Trace t) {
        return tree(t::forEachSpan);
    }

    static String tree(Tempo.Snapshot s) {
        return tree(s::forEachSpan);
    }

    static String tree(Consumer<Tempo.SpanVisitor> spans) {
        StringBuilder b = new StringBuilder();
        String[] rootThread = {null};
        spans.accept(s -> {
            if (rootThread[0] == null) rootThread[0] = s.thread();
            b.append("  ".repeat(s.depth())).append(s.name()).append(' ').append(s.startNanos()).append('+').append(s.durationNanos());
            if (s.level() == DETAIL) b.append(" detail");
            if (s.level() == DEEP) b.append(" deep");
            if (s.phase()) b.append(s.retro() ? " lap" : " phase");
            if (s.legacy()) b.append(" legacy");
            if (s.label() != null) b.append(" '").append(s.label()).append('\'');
            if (s.open()) b.append(" open");
            if (s.failed()) b.append(" failed");
            if (s.cancelled()) b.append(" cancelled");
            if (s.waiting()) b.append(" waiting");
            if (s.healed()) b.append(" healed");
            if (s.misuse()) b.append(" misuse");
            if (s.aggregate()) b.append(" agg*").append(s.count()).append(" min=").append(s.minNanos())
                    .append(" max=").append(s.maxNanos()).append(" maxAt=").append(s.maxAtNanos()).append(" extent=").append(s.extentNanos());
            if (s.handoff()) b.append(" handoff~").append(s.waitNanos());
            if (s.handoff() && s.aggregate()) b.append('/').append(s.waitMaxNanos());
            if (s.neverStarted()) b.append(" unstarted");
            if (s.mixedThreads()) b.append(" @_");
            else if (!s.thread().equals(rootThread[0])) b.append(" @").append(s.thread());
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
        return onThread("other", r);
    }

    static Throwable onThread(String name, Runnable r) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, name);
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

    /**
     * Pins current behaviour, documented in the Span Javadoc, not a guarantee: a thread reuses one handle
     * per depth, so once the next span opens at that depth the old variable is the new span, and a late
     * call through it lands there, undetected even under strict. A redesign of the handles changes this.
     */
    @Test
    void pinsCurrentBehaviourAHandleIsTheNextSpanOpenedAtItsDepth() {
        Trace t = Tempo.start(SEARCH, strict());
        Span d = t.root().span(DECODE);
        d.close();
        d.set(BYTES, 7);                 // still decode: right after close is fine
        Span l = t.root().span(LOAD);    // the next span at the same depth on this thread
        assertSame(d, l);                // ...is the same handle
        d.fail().set(SCORE, 0.5);        // so these land on load
        l.close();
        t.close();
        assertEquals(lines("search 0+0", "  decode 0+0 bytes=7", "  load 0+0 failed score=0.5"), tree(t));
        assertEquals(0, t.misuses());
    }

    /**
     * As the Span Javadoc says: a span folded past the budget has no row, so it keeps no attributes, and
     * flags only while it is open; a flag after close (as in a catch block) is dropped, without misuse.
     */
    @Test
    void aFoldedSpanTakesFlagsOnlyWhileOpenAndNoAttributes() {
        Trace t = Tempo.start(SEARCH, strict().level(DETAIL).budget(0));
        Span open = t.root().detail(LOAD);
        open.fail().set(BYTES, 7);       // open: the flag shows on the aggregate, the attribute is dropped
        open.close();
        Span late = t.root().detail(DECODE);
        late.close();
        late.fail();                     // closed: dropped
        t.close();
        assertEquals(0, t.misuses());
        String tree = tree(t);
        assertTrue(tree.contains("load") && tree.contains("decode"), tree);
        for (String line : tree.split("\n")) {
            assertFalse(line.contains("bytes"), tree);
            if (line.contains("load")) assertTrue(line.contains("failed"), tree);
            if (line.contains("decode")) assertFalse(line.contains("failed"), tree);
        }
    }

    // ---- phases and laps ------------------------------------------------------------------------------

    @Test
    void phasesHaveExactDurationsAndNestTheSpansOpenedInThem() {
        Trace t = Tempo.start(SEARCH, opts());
        Span s = t.root();
        clock.add(5);
        assertSame(s, s.phase(DECODE));
        clock.add(10);
        try (Span l = s.span(LOAD)) {
            clock.add(4);
        }
        clock.add(1);
        s.phase(RERANK); // one clock read ends decode and starts rerank
        clock.add(30);
        s.span(MATCH).close();
        assertEquals(2, s.lapOrdinal());
        clock.add(10);
        t.close(); // ends the open phase normally: neither healed nor misuse
        assertEquals(lines(
                "search 0+60",
                "  decode 5+15 phase",
                "    load 15+4",
                "  rerank 20+40 phase",
                "    match 50+0"), tree(t));
        assertEquals(0, t.misuses());
    }

    @Test
    void currentIsThePhasesOwner() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span s = t.root();
            s.phase(DECODE);
            assertSame(s, Tempo.current());
            Tempo.current().span(LOAD).close();  // still nests under the phase
            Tempo.current().phase(RERANK);        // a boundary of the owner, not a phase inside decode
            clock.add(3);
            Tempo.current().done(RERANK);
            t.close();
            assertEquals(lines(
                    "search 0+3",
                    "  decode 0+0 phase",
                    "    load 0+0",
                    "  rerank 0+3 phase"), tree(t));
            assertEquals(0, t.misuses());
        }
    }

    @Test
    void labelAfterLapsAdoptTheSpansOpenedDuringThem() {
        Trace t = Tempo.start(SEARCH, opts());
        Span s = t.root();
        clock.add(2);
        try (Span l = s.span(LOAD)) {
            clock.add(3);
            l.span(MATCH).close();
            clock.add(5);
            l.span(MATCH).close();
        }
        clock.add(5);
        assertSame(s, s.done(DECODE));
        clock.add(5);
        try (Span m = s.span(MATCH)) {
            clock.add(7);
        }
        clock.add(3);
        s.done(RERANK);
        clock.add(7);
        s.span(LOAD).close(); // after the last lap: the root's own
        clock.add(3);
        t.close();
        assertEquals(lines(
                "search 0+40",
                "  decode 0+15 lap",
                "    load 2+8",
                "      match 5+0",
                "      match 10+0",
                "  rerank 15+15 lap",
                "    match 20+7",
                "  load 37+0"), tree(t));
        List<String> ids = new ArrayList<>();
        t.forEachSpan(v -> ids.add(v.id() + "<" + v.parent() + "@" + v.depth()));
        assertEquals(List.of("0<-1@0", "1<0@1", "2<1@2", "3<2@3", "4<2@3", "5<0@1", "6<5@2", "7<0@1"), ids);
        assertEquals(2, s.lapOrdinal());
        assertEquals(0, t.misuses());
    }

    @Test
    void timeAfterTheLastLapStaysSelfTimeOfTheOwner() {
        Trace t = Tempo.start(SEARCH, opts());
        clock.add(1);
        try (Span r = t.root().span(RERANK)) {
            clock.add(4);
            r.done(LOAD);       // the first lap of a span starts at its open
            clock.add(2);
            r.done(MATCH);
            clock.add(9);       // self-time of rerank, not part of a lap
        }
        t.close();
        assertEquals(lines(
                "search 0+16",
                "  rerank 1+15",
                "    load 1+4 lap",
                "    match 5+2 lap"), tree(t));
    }

    @Test
    void doneEndsAnOpenPhaseAndTheNextLapStartsThere() {
        Trace t = Tempo.start(SEARCH, opts());
        Span s = t.root();
        clock.add(1);
        s.phase(DECODE);
        clock.add(8);
        s.done(DECODE);
        assertEquals(1, s.lapOrdinal()); // ending a phase is not another boundary
        clock.add(2);
        s.span(LOAD).close();
        s.done(RERANK);
        clock.add(4);
        t.close();
        assertEquals(lines(
                "search 0+15",
                "  decode 1+8 phase",
                "  rerank 9+2 lap",
                "    load 11+0"), tree(t));
        assertEquals(2, s.lapOrdinal());
        assertEquals(0, t.misuses());
    }

    @Test
    void mismatchedDoneEndsThePhaseUnderItsOwnName() {
        Trace t = Tempo.start(SEARCH, opts());
        Span s = t.root();
        s.phase(DECODE);
        clock.add(5);
        s.done(RERANK);
        assertEquals(1, t.misuses());
        clock.add(3);
        s.span(MATCH).close();
        s.done(LOAD); // starts at the phase's end and adopts only what came after the misuse entry
        t.close();
        assertEquals(lines(
                "search 0+8",
                "  decode 0+5 phase",
                "  rerank 5+0 lap misuse",
                "  load 5+3 lap",
                "    match 8+0"), tree(t));
        assertEquals(1, t.misuses());
    }

    @Test
    void strictThrowsOnMismatchedDoneAfterRecording() {
        Trace t = Tempo.start(SEARCH, strict());
        Span s = t.root();
        s.phase(DECODE);
        clock.add(5);
        AssertionError e = assertThrows(AssertionError.class, () -> s.done(RERANK));
        assertEquals("Tempo misuse: done(rerank) while phase decode is open", e.getMessage());
        clock.add(1);
        t.close();
        assertEquals(lines(
                "search 0+6",
                "  decode 0+5 phase",
                "  rerank 5+0 lap misuse"), tree(t));
    }

    @Test
    void boundariesHealASpanLeftOpen() {
        Trace t = Tempo.start(SEARCH, opts());
        Span s = t.root();
        s.span(LOAD);
        clock.add(4);
        s.phase(DECODE);
        assertEquals(1, t.misuses());
        s.span(MATCH);
        clock.add(2);
        s.done(DECODE); // ends match (healed) and the phase
        assertEquals(2, t.misuses());
        s.span(JOIN);
        clock.add(3);
        s.done(RERANK);
        assertEquals(3, t.misuses());
        t.close();
        assertEquals(lines(
                "search 0+9",
                "  load 0+4 healed",
                "  decode 4+2 phase",
                "    match 4+2 healed",
                "  rerank 6+3 lap",
                "    join 6+3 healed"), tree(t));
    }

    @Test
    void strictThrowsWhenABoundaryHeals() {
        Trace t = Tempo.start(SEARCH, strict());
        Span s = t.root();
        s.span(LOAD);
        clock.add(4);
        AssertionError e = assertThrows(AssertionError.class, () -> s.phase(DECODE));
        assertEquals("Tempo misuse: phase started while a child span was open", e.getMessage());
        s.span(MATCH);
        assertThrows(AssertionError.class, () -> s.done(RERANK));
        t.close();
        assertEquals(lines(
                "search 0+4",
                "  load 0+4 healed",
                "  decode 4+0 phase",
                "    match 4+0 healed",
                "  rerank 4+0 lap misuse"), tree(t)); // recorded in full; the heal is reported first
    }

    @Test
    void failedLapIsFlaggedNeverLegacyAndRethrown() {
        Trace t = Tempo.start(SEARCH, opts());
        Span s = t.root();
        IOException io = new IOException("gone");
        clock.add(3);
        try {
            s.lap(DECODE, () -> {
                try (Span l = s.span(LOAD)) {
                    clock.add(4);
                }
                throw io;
            });
            fail();
        } catch (IOException e) {
            assertSame(io, e);
        }
        int n = s.lap(RERANK, () -> {
            clock.add(5);
            return 42;
        });
        assertEquals(42, n);
        int seven = NOOP.lap(MATCH, () -> 7);
        assertEquals(7, seven);
        t.close();
        assertEquals(lines(
                "search 0+12",
                "  decode 0+7 lap failed",
                "    load 3+4",
                "  rerank 7+5 lap"), tree(t));
        assertEquals(0, t.misuses());
    }

    @Test
    void strictLapKeepsTheBodysThrowable() {
        Trace t = Tempo.start(SEARCH, strict());
        Span s = t.root();
        IOException io = new IOException("gone");
        IOException thrown = assertThrows(IOException.class, () -> s.lap(DECODE, () -> {
            s.span(LOAD); // left open by the throw
            clock.add(2);
            throw io;
        }));
        assertSame(io, thrown);
        assertEquals(1, io.getSuppressed().length);
        assertTrue(io.getSuppressed()[0] instanceof AssertionError);
        t.close();
        assertEquals(lines(
                "search 0+2",
                "  decode 0+2 lap failed",
                "    load 0+2 healed"), tree(t));
    }

    @Test
    void legacyLabelsAndPhaseAttributes() {
        Trace t = Tempo.start(SEARCH, opts());
        Span s = t.root();
        clock.add(4);
        s.done(DECODE, "Decode Input #3 = v2(x)");
        s.phase(RERANK).phaseSet(BYTES, 7).legacy(); // on the open phase
        clock.add(6);
        s.done(RERANK, "Rerank & Match");
        clock.add(2);
        s.done(MATCH).phaseSet(BYTES, 3);           // on the last lap
        s.done(LOAD, null).legacy();
        t.close();
        assertEquals(lines(
                "search 0+12",
                "  decode 0+4 lap legacy 'Decode Input #3 = v2(x)'",
                "  rerank 4+6 phase legacy 'Rerank & Match' bytes=7",
                "  match 10+2 lap bytes=3",
                "  load 12+0 lap legacy"), tree(t));
        assertEquals(0, t.misuses());
    }

    @Test
    void manyLabelsKeepTheirLaps() {
        Trace t = Tempo.start(SEARCH, opts());
        for (int i = 0; i < 20; i++) {
            clock.add(1);
            t.root().done(LOAD, "load #" + i);
        }
        t.close();
        List<String> labels = new ArrayList<>();
        t.forEachSpan(v -> labels.add(v.startNanos() + ":" + v.label()));
        assertEquals("0:null", labels.get(0));
        for (int i = 0; i < 20; i++) assertEquals(i + ":load #" + i, labels.get(i + 1));
    }

    /** A render racing the label table's growth never sees a committed lap without its label (stress, weak memory). */
    @Test
    void liveRenderSeesEveryCommittedLabel() throws Exception {
        String[] text = new String[64];
        for (int i = 0; i < text.length; i++) text[i] = "load #" + i;
        AtomicReference<Trace> live = new AtomicReference<>();
        AtomicReference<String> bad = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            for (int round = 0; round < 20000 && bad.get() == null; round++) {
                Trace t = Tempo.start(SEARCH, opts());
                live.set(t);
                for (String s : text) t.root().done(LOAD, s);
                t.close();
            }
            live.set(null);
        });
        owner.start();
        while (owner.isAlive()) {
            Trace t = live.get();
            if (t != null) t.forEachSpan(v -> {
                if (v.retro() && v.label() == null) bad.set("lap " + v.id() + " rendered without its label");
            });
        }
        owner.join();
        assertNull(bad.get());
    }

    @Test
    void legacyAndPhaseSetNeedAPhaseOrLap() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span s = t.root();
            s.done(DECODE);
            try (Span j = s.span(JOIN)) {
                j.legacy().phaseSet(BYTES, 1); // the root's lap is not this span's
            }
            assertEquals(2, t.misuses());
            t.close();
            assertEquals(lines("search 0+0", "  decode 0+0 lap", "  join 0+0"), tree(t));
        }
        try (Trace t = Tempo.start(SEARCH, strict())) {
            AssertionError e = assertThrows(AssertionError.class, () -> t.root().legacy());
            assertEquals("Tempo misuse: legacy() without a phase or lap", e.getMessage());
        }
    }

    @Test
    void nullNamesInPhasesAndLaps() {
        Trace t = Tempo.start(SEARCH, opts());
        Span s = t.root();
        s.phase(null);
        clock.add(2);
        s.done(null);     // ends the open phase; no second misuse for the mismatch
        clock.add(3);
        s.done(null);
        t.close();
        assertEquals(3, t.misuses());
        assertEquals(lines(
                "search 0+5",
                "  ? 0+2 phase misuse",
                "  ? 2+3 lap misuse"), tree(t));
        Trace u = Tempo.start(SEARCH, strict());
        assertThrows(AssertionError.class, () -> u.root().phase(null));
        assertThrows(AssertionError.class, () -> u.root().done(null));
        u.close();
        assertEquals(lines("search 0+0"), tree(u));
    }

    @Test
    void phasesOnAForeignThreadOrNoopAreIgnored() throws Exception {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span s = t.root();
            assertNull(onOtherThread(() -> s.phase(DECODE).done(DECODE).done(LOAD, "x").legacy().phaseSet(BYTES, 1)));
            assertEquals(5, t.misuses());
            assertSame(NOOP, NOOP.phase(DECODE).done(DECODE).done(LOAD, "x").legacy().phaseSet(BYTES, 1));
            assertEquals(0, NOOP.lapOrdinal());
            t.close();
            assertEquals(lines("search 0+0"), tree(t));
        }
    }

    @Test
    void lapsWorkAtTheDepthCapAndPhasesAreDropped() {
        try (Trace t = Tempo.start(SEARCH, opts())) {
            Span s = t.root();
            for (int d = 1; d < 35; d++) s = s.span(LOAD);
            clock.add(1);
            s.phase(DECODE); // no room on the stack: dropped, but still a boundary
            s.legacy().phaseSet(BYTES, 1);
            clock.add(2);
            s.done(MATCH);   // a lap is not on the stack, so it fits
            assertEquals(1, t.dropped());
            assertEquals(0, t.misuses());
            assertEquals(2, s.lapOrdinal());
            List<String> deepest = new ArrayList<>();
            t.forEachSpan(v -> {
                if (v.depth() == 35) deepest.add(v.name() + " " + v.startNanos() + "+" + v.durationNanos());
            });
            assertEquals(List.of("match 1+2"), deepest);
        }
    }

    @Test
    void strictStaysSilentOnCorrectPhasesAndLaps() throws Exception {
        try (Trace t = Tempo.start(SEARCH, strict())) {
            Span s = t.root();
            s.done(DECODE, "Decode");
            s.phase(RERANK).phaseSet(BYTES, 1);
            s.span(LOAD).close();
            s.done(RERANK).legacy();
            s.lap(MATCH, () -> 1);
            assertEquals(0, t.misuses());
        }
    }

    @Test
    void phasesAndLapsAllocateNothingOnceWarm() {
        int rounds = 2_000; // four rows per round
        for (int i = 0; i < 3; i++) {
            Trace warm = Tempo.start(SEARCH, opts());
            laps(warm.root(), rounds);
            warm.close();
            warm.release();
        }
        Trace t = Tempo.start(SEARCH, opts());
        laps(t.root(), 1); // creates the phase state, the handles and the event table
        assertEquals(0, allocatedBy(() -> laps(t.root(), rounds)), "bytes allocated by " + rounds + " rounds");
        t.close();
        assertEquals(0, t.misuses());
        t.release();
    }

    static final Tempo.ThrowingSupplier<Object, RuntimeException> NOTHING = () -> null;

    private void laps(Span root, int rounds) {
        for (int i = 0; i < rounds; i++) {
            root.phase(DECODE).phaseSet(BYTES, i);
            root.span(LOAD).close();
            clock.add(1);
            root.done(DECODE).done(RERANK).legacy();
            root.lap(MATCH, NOTHING);
        }
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

    // ---- handoffs -------------------------------------------------------------------------------------

    static final Name QUEUED = Tempo.name("queued"), POOL_WAIT = Tempo.name("pool-wait");

    /** One named thread; tasks run one after another in submit order. */
    static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(r -> new Thread(r, name));
    }

    @Test
    void handoffRecordsQueueWaitAndRunOnTheWorker() throws Exception {
        ExecutorService pool = worker("worker-1");
        try {
            Trace t = Tempo.start(SEARCH, opts());
            Span root = t.root();
            clock.add(10);
            Handoff h = root.handoff(MATCH).set(QUEUED, 3);
            clock.add(30);
            pool.submit(() -> {
                assertSame(NOOP, Tempo.current());
                try (Span m = h.begin()) {
                    assertSame(m, Tempo.current());
                    clock.add(5);
                    try (Span l = m.span(LOAD)) {
                        l.set(BYTES, 7);
                        clock.add(20);
                    }
                    m.set(SCORE, 0.5);
                }
                assertSame(NOOP, Tempo.current());
            }).get();
            clock.add(15);
            t.close();
            assertEquals(lines(
                    "search 0+80",
                    "  match 10+25 handoff~30 @worker-1 queued=3 score=0.5",
                    "    load 45+20 @worker-1 bytes=7"), tree(t));
            List<String> ids = new ArrayList<>();
            t.forEachSpan(v -> ids.add(v.id() + "<" + v.parent() + "@" + v.depth()));
            assertEquals(List.of("0<-1@0", "1<0@1", "2<1@2"), ids);
            assertEquals(0, t.misuses());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void wrappersMarkFailuresAndRethrow() throws Exception {
        ExecutorService pool = worker("worker-1");
        try {
            Trace t = Tempo.start(SEARCH, opts());
            Span root = t.root();
            IOException io = new IOException("gone");
            Future<Object> c = pool.submit(root.handoff(LOAD).callable(() -> {
                clock.add(4);
                throw io;
            }));
            assertSame(io, assertThrows(ExecutionException.class, c::get).getCause());
            RuntimeException boom = new IllegalStateException("boom");
            Future<?> r = pool.submit(root.handoff(MATCH).runnable(() -> {
                throw boom;
            }));
            assertSame(boom, assertThrows(ExecutionException.class, r::get).getCause());
            Supplier<Integer> s = root.handoff(DECODE).supplier(() -> {
                clock.add(2);
                return 42;
            });
            assertEquals(42, CompletableFuture.supplyAsync(s, pool).get());
            t.close();
            assertEquals(lines(
                    "search 0+6",
                    "  load 0+4 failed handoff~0 @worker-1",
                    "  match 4+0 failed handoff~0 @worker-1",
                    "  decode 4+2 handoff~0 @worker-1"), tree(t));
            assertEquals(0, t.misuses());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void inlineHandoffIsAChildOnTheSubmittingThread() {
        Trace t = Tempo.start(SEARCH, opts());
        try (Span r = t.root().span(RERANK)) {
            Handoff h = r.handoff(MATCH);
            clock.add(3);
            h.runnable(() -> { // caller runs
                try (Span l = Tempo.current().span(LOAD)) {
                    clock.add(4);
                }
            }).run();
            assertSame(r, Tempo.current());
        }
        t.close();
        assertEquals(lines(
                "search 0+7",
                "  rerank 0+7",
                "    match 0+4 handoff~3",
                "      load 3+4"), tree(t));
        assertEquals(0, t.misuses());
    }

    @Test
    void cancelledRejectedAndNeverStartedHandoffs() {
        Trace t = Tempo.start(SEARCH, opts());
        Span root = t.root();
        Handoff a = root.handoff(MATCH), b = root.handoff(LOAD), c = root.handoff(DECODE);
        clock.add(5);
        assertTrue(a.cancel());
        clock.add(2);
        assertTrue(b.reject());
        assertFalse(a.cancel()); // once is enough
        assertFalse(a.reject());
        assertSame(NOOP, a.begin()); // the cancel won the race: not misuse
        assertNull(a.tryBegin());
        assertEquals(0, t.misuses());
        clock.add(3);
        t.close();
        assertEquals(lines(
                "search 0+10",
                "  match 0+0 cancelled handoff~5",
                "  load 0+0 cancelled handoff~7",
                "  decode 0+0 handoff~10 unstarted"), tree(t));
        clock.add(4);
        assertEquals(lines( // an unstarted handoff keeps waiting
                "search 0+10",
                "  match 0+0 cancelled handoff~5",
                "  load 0+0 cancelled handoff~7",
                "  decode 0+0 handoff~14 unstarted"), tree(t));
        assertEquals(0, t.misuses());
    }

    @Test
    void cancelOnAnotherThreadIsRecordedThere() throws Exception {
        Trace t = Tempo.start(SEARCH, opts());
        Handoff h = t.root().handoff(MATCH);
        clock.add(6);
        assertNull(onThread("canceller", h::cancel));
        Span s = h.begin();
        assertSame(NOOP, s);
        t.close();
        assertEquals(lines("search 0+6", "  match 0+0 cancelled handoff~6 @canceller"), tree(t));
        assertEquals(0, t.misuses());
    }

    @Test
    void beginTwiceIsMisuseAndCancelAfterBeginIsNot() throws Exception {
        Trace t = Tempo.start(SEARCH, opts());
        Handoff h = t.root().handoff(MATCH);
        Span s = h.begin();
        assertTrue(s.recording());
        assertSame(NOOP, h.begin());
        assertEquals(1, t.misuses());
        s.close();
        assertFalse(h.cancel());
        assertEquals(1, t.misuses());
        assertNull(onOtherThread(() -> h.set(QUEUED, 1)));
        assertEquals(2, t.misuses());
        t.close();
        assertEquals(lines("search 0+0", "  match 0+0 handoff~0"), tree(t));

        Trace u = Tempo.start(SEARCH, strict());
        Handoff g = u.root().handoff(MATCH);
        g.begin().close();
        AssertionError e = assertThrows(AssertionError.class, g::begin);
        assertEquals("Tempo misuse: handoff begun twice", e.getMessage());
        Handoff k = u.root().handoff(LOAD);
        assertTrue(k.cancel());
        assertSame(NOOP, k.begin()); // begin after cancel is a race the cancel won, not misuse
        assertEquals("Tempo misuse: handoff begun twice", assertThrows(AssertionError.class, g::tryBegin).getMessage());
        Throwable foreign = onOtherThread(() -> g.set(QUEUED, 1));
        assertEquals("Tempo misuse: handoff set on a foreign thread", foreign.getMessage());
        u.close();
    }

    @Test
    void handoffsOnNoopOrAForeignThreadRecordNothing() throws Exception {
        assertSame(Handoff.NOOP, NOOP.handoff(MATCH));
        Runnable r = () -> { };
        Callable<Integer> c = () -> 1;
        Supplier<Integer> s = () -> 2;
        assertSame(r, Handoff.NOOP.runnable(r));
        assertSame(c, Handoff.NOOP.callable(c));
        assertSame(s, Handoff.NOOP.supplier(s));
        assertSame(NOOP, Handoff.NOOP.begin());
        assertFalse(Handoff.NOOP.set(QUEUED, 1).cancel());
        assertFalse(Handoff.NOOP.reject());
        assertSame(NOOP, Handoff.NOOP.tryBegin());

        Trace t = Tempo.start(SEARCH, opts());
        AtomicReference<Handoff> h = new AtomicReference<>();
        assertNull(onOtherThread(() -> h.set(t.root().handoff(MATCH))));
        assertSame(Handoff.NOOP, h.get());
        assertEquals(1, t.misuses());
        t.root().handoff(null).begin().close();
        assertEquals(2, t.misuses());
        t.close();
        assertEquals(lines("search 0+0", "  ? 0+0 misuse handoff~0"), tree(t));
    }

    @Test
    void handoffsChainAcrossWorkers() throws Exception {
        ExecutorService one = worker("worker-1"), two = worker("worker-2");
        try {
            Trace t = Tempo.start(SEARCH, opts().level(DETAIL));
            Handoff h = t.root().handoff(MATCH);
            one.submit(h.runnable(() -> {
                clock.add(2);
                Handoff g = Tempo.current().handoff(LOAD);
                clock.add(1);
                try {
                    two.submit(g.runnable(() -> {
                        try (Span d = Tempo.current().detail(DECODE)) {
                            clock.add(5);
                        }
                    })).get();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })).get();
            t.close();
            assertEquals(lines(
                    "search 0+8",
                    "  match 0+8 handoff~0 @worker-1",
                    "    load 2+5 handoff~1 @worker-2",
                    "      decode 3+5 detail @worker-2"), tree(t));
        } finally {
            one.shutdown();
            two.shutdown();
        }
    }

    @Test
    void aTaskOfTheSameTraceInsideAnotherRunsInlineButIsDrawnAtItsHandoff() throws Exception {
        ExecutorService pool = worker("worker-1");
        try {
            Trace t = Tempo.start(SEARCH, opts());
            Handoff first = t.root().handoff(MATCH), second = t.root().handoff(LOAD);
            pool.submit(() -> {
                try (Span m = first.begin()) {
                    clock.add(2);
                    second.runnable(() -> clock.add(3)).run(); // e.g. a pool that helps while it joins
                    assertSame(m, Tempo.current());
                }
            }).get();
            t.close();
            assertEquals(lines(
                    "search 0+5",
                    "  match 0+5 handoff~0 @worker-1",
                    "  load 0+3 handoff~2 @worker-1"), tree(t));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void interleavedTracesShareOnePoolThread() throws Exception {
        ExecutorService pool = worker("worker-1");
        try {
            Trace a = Tempo.start(SEARCH, opts());
            Trace b = Tempo.start(RERANK, opts()); // two requests alive at once
            for (int i = 0; i < 2; i++) {
                for (Trace t : List.of(a, b)) {
                    Handoff h = t.root().handoff(MATCH).set(QUEUED, i);
                    pool.submit(() -> {
                        try (Span m = h.begin()) {
                            assertSame(m, Tempo.current());
                            m.span(LOAD).close();
                            clock.add(1);
                        }
                        assertSame(NOOP, Tempo.current());
                    }).get();
                }
            }
            b.close();
            a.close();
            assertEquals(lines(
                    "search 0+4",
                    "  match 0+1 handoff~0 @worker-1 queued=0",
                    "    load 0+0 @worker-1",
                    "  match 2+1 handoff~0 @worker-1 queued=1",
                    "    load 2+0 @worker-1"), tree(a));
            assertEquals(lines(
                    "rerank 0+4",
                    "  match 1+1 handoff~0 @worker-1 queued=0",
                    "    load 1+0 @worker-1",
                    "  match 3+1 handoff~0 @worker-1 queued=1",
                    "    load 3+0 @worker-1"), tree(b));
            assertEquals(2, pool.submit(TempoTest::cachedLanes).get()); // one lane per trace, reused
        } finally {
            pool.shutdown();
        }
    }

    /** More traces than the slot caches interleave on one pool thread; each keeps one lane there. */
    @Test
    void moreInterleavedTracesThanCachedLanesReuseTheirLane() throws Exception {
        ExecutorService pool = worker("worker-1");
        try {
            List<Trace> traces = new ArrayList<>();
            for (int k = 0; k < 6; k++) traces.add(Tempo.start(SEARCH, opts()));
            for (int i = 0; i < 3; i++) {
                for (Trace t : traces) {
                    Handoff h = t.root().handoff(MATCH);
                    pool.submit(() -> h.begin().close()).get();
                }
            }
            for (int k = traces.size() - 1; k >= 0; k--) traces.get(k).close();
            for (Trace t : traces) {
                assertEquals(2, t.ctx.lanesById().length); // the root's and worker-1's
                assertEquals(lines(
                        "search 0+0",
                        "  match 0+0 handoff~0 @worker-1",
                        "  match 0+0 handoff~0 @worker-1",
                        "  match 0+0 handoff~0 @worker-1"), tree(t));
                assertEquals(0, t.misuses() + t.dropped());
            }
            assertEquals(4, pool.submit(TempoTest::cachedLanes).get());
        } finally {
            pool.shutdown();
        }
    }

    /** Begin and close in separate callbacks of one thread: lane roots of two traces close out of order. */
    @Test
    void laneRootsClosedOutOfOrderAndReopenedRestoreTheCurrentSpan() throws Exception {
        Handoff[] hs = new Handoff[3];
        Trace[] ts = new Trace[2];
        assertNull(onThread("request-1", () -> {
            ts[0] = Tempo.start(SEARCH, opts());
            hs[0] = ts[0].root().handoff(MATCH);
            hs[1] = ts[0].root().handoff(LOAD);
            ts[0].close();
        }));
        assertNull(onThread("request-2", () -> {
            ts[1] = Tempo.start(RERANK, opts());
            hs[2] = ts[1].root().handoff(MATCH);
            ts[1].close();
        }));
        String here = assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () -> {
            Span a = hs[0].begin();
            clock.add(1);
            Span b = hs[2].begin();
            clock.add(1);
            a.close();
            assertSame(b, Tempo.current());
            Span a2 = hs[1].begin(); // reopens the lane a closed
            assertSame(a2, Tempo.current());
            clock.add(1);
            b.close();
            assertSame(a2, Tempo.current());
            clock.add(1);
            a2.close();
            assertSame(NOOP, Tempo.current());
            return Thread.currentThread().getName();
        });
        assertEquals(lines(
                "search 0+0",
                "  match 0+2 handoff~0 @" + here,
                "  load 0+2 handoff~2 @" + here), tree(ts[0])); // drawn at the submit
        assertEquals(lines("rerank 0+0", "  match 0+2 handoff~1 @" + here), tree(ts[1]));
        assertEquals(2, ts[0].ctx.lanesById().length);
        assertEquals(0, ts[0].misuses() + ts[1].misuses());
    }

    static int cachedLanes() {
        int n = 0;
        for (WeakReference<Tempo.Lane> w : Tempo.SLOT.get().lanes) {
            Tempo.Lane l = w == null ? null : w.get();
            assertFalse(l != null && l.retired(), "a retired lane stayed cached");
            if (l != null) n++;
        }
        return n;
    }

    @Test
    void aPoolThreadMovesOnAfterItsTraceEnds() throws Exception {
        ExecutorService pool = worker("worker-1");
        try {
            Trace a = Tempo.start(SEARCH, opts());
            Handoff early = a.root().handoff(LOAD), late = a.root().handoff(MATCH);
            pool.submit(() -> early.begin().close()).get();
            a.close();
            a.release();

            Trace b = Tempo.start(RERANK, opts());
            Handoff h = b.root().handoff(DECODE);
            pool.submit(() -> {
                h.begin().close();
                assertSame(NOOP, Tempo.current());
            }).get();
            assertEquals(1, pool.submit(TempoTest::cachedLanes).get()); // the released trace's lane is gone

            int[] ran = {0};
            pool.submit(() -> {
                assertSame(NOOP, late.begin()); // a straggler of the released trace records nothing
                late.runnable(() -> ran[0]++).run();
                assertSame(NOOP, Tempo.current());
            }).get();
            assertEquals(1, ran[0]);
            b.close();
            assertEquals(lines("rerank 0+0", "  decode 0+0 handoff~0 @worker-1"), tree(b));
            assertEquals("", tree(a));
            assertEquals(0, a.misuses() + b.misuses());
            b.release();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void anUnreleasedTraceIsCollectedWhileAPoolThreadCachesItsLane() throws Exception {
        ExecutorService pool = worker("worker-1");
        try {
            List<WeakReference<Object>> refs = unreleasedTraceWithATaskOn(pool);
            assertEquals(1, pool.submit(TempoTest::cachedLanes).get());
            for (int i = 0; i < 20 && refs.stream().anyMatch(r -> r.get() != null); i++) System.gc();
            for (WeakReference<Object> r : refs) assertNull(r.get(), "still reachable: " + r.get());
        } finally {
            pool.shutdown();
        }
    }

    /** The trace, its root lane and the worker's lane, held weakly; a separate frame so no local pins them. */
    private List<WeakReference<Object>> unreleasedTraceWithATaskOn(ExecutorService pool) throws Exception {
        Trace t = Tempo.start(SEARCH, opts());
        Handoff h = t.root().handoff(MATCH);
        pool.submit(() -> h.begin().close()).get();
        t.close();
        Tempo.Lane[] lanes = t.ctx.lanesById();
        assertEquals(2, lanes.length);
        return List.of(new WeakReference<>(t), new WeakReference<>(lanes[0]), new WeakReference<>(lanes[1]));
    }

    /**
     * A live render races tasks begun on an old lane whose handoffs sit on lanes registered after the
     * render read the registry. Such a task is left out until its lane is read; the render never throws.
     */
    @Test
    void renderingWhileNewLanesHandOffNeverThrows() throws Exception {
        int n = 1000;
        Trace t = Tempo.start(SEARCH, opts().lanes(n + 2).budget(Integer.MAX_VALUE)); // no handoff folds
        for (int i = 0; i < 8000; i++) t.root().set(BYTES, i); // a slow first shape widens the window
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        SynchronousQueue<Handoff> queue = new SynchronousQueue<>();
        Handoff first = t.root().handoff(LOAD);
        Thread worker = new Thread(() -> { // lane 1, begins the nested handoffs of the newer lanes
            try {
                first.begin().close();
                while (!stop.get()) {
                    Handoff h = queue.poll(1, TimeUnit.MILLISECONDS);
                    if (h != null) h.begin().close();
                }
            } catch (Throwable e) {
                thrown.compareAndSet(null, e);
            }
        }, "worker-1");
        Thread renderer = new Thread(() -> {
            try {
                while (!stop.get()) assertWellFormed(t);
            } catch (Throwable e) {
                thrown.compareAndSet(null, e);
            }
        }, "renderer");
        worker.start();
        renderer.start();
        Handoff[] outer = handoffs(t.root(), n);
        for (int i = 0; i < n && thrown.get() == null; i++) {
            Handoff o = outer[i];
            Throwable e = onThread("fresh-" + i, () -> { // registers a new lane
                try (Span s = o.begin()) {
                    queue.put(s.handoff(MATCH));
                } catch (InterruptedException x) {
                    throw new AssertionError(x);
                }
            });
            if (e != null) thrown.compareAndSet(null, e);
        }
        stop.set(true);
        worker.join();
        renderer.join();
        t.close();
        if (thrown.get() != null) throw new AssertionError(thrown.get());
        assertEquals(2 + 2 * n, assertWellFormed(t));
        t.forEachSpan(v -> assertFalse(v.neverStarted()));
        assertEquals(0, t.misuses() + t.dropped());
        t.release();
    }

    /** Checks the ids, parents, depths and durations of every record; returns the count. */
    static int assertWellFormed(Trace t) {
        List<Integer> depths = new ArrayList<>();
        t.forEachSpan(v -> {
            assertEquals(depths.size(), v.id());
            assertTrue(v.parent() >= -1 && v.parent() < v.id(), "parent " + v.parent() + " of " + v.id());
            assertEquals(v.parent() < 0 ? 0 : depths.get(v.parent()) + 1, v.depth());
            assertNotNull(v.name());
            assertTrue(v.durationNanos() >= 0 && v.waitNanos() >= 0);
            depths.add(v.depth());
        });
        return depths.size();
    }

    @Test
    void aTaskOfAnotherTraceRestoresTheCurrentSpan() throws Exception {
        AtomicReference<Trace> other = new AtomicReference<>();
        AtomicReference<Handoff> handoff = new AtomicReference<>();
        assertNull(onThread("request-2", () -> {
            Trace b = Tempo.start(RERANK, opts());
            handoff.set(b.root().handoff(MATCH));
            other.set(b);
            b.close();
        }));
        Trace a = Tempo.start(SEARCH, opts());
        try (Span d = a.root().span(DECODE)) {
            handoff.get().runnable(() -> {
                assertNotSame(d, Tempo.current());
                Tempo.current().span(LOAD).close();
                clock.add(2);
            }).run();
            assertSame(d, Tempo.current());
        }
        a.close();
        assertSame(NOOP, Tempo.current());
        String here = Thread.currentThread().getName();
        assertEquals(lines("search 0+2", "  decode 0+2"), tree(a));
        assertEquals(lines(
                "rerank 0+0",
                "  match 0+2 handoff~0 @" + here,
                "    load 0+0 @" + here), tree(other.get()));
    }

    @Test
    void aStragglerOnTheRequestThreadLeavesTheRootAlone() {
        Trace t = Tempo.start(SEARCH, opts());
        Handoff h = t.root().handoff(MATCH);
        clock.add(1);
        t.close();
        clock.add(2);
        h.runnable(() -> clock.add(3)).run();
        assertEquals(1, t.elapsedNanos());
        t.root().set(BYTES, 9);
        assertEquals(lines("search 0+1 bytes=9", "  match 0+3 handoff~3"), tree(t));
        assertSame(NOOP, Tempo.current());
    }

    @Test
    void tasksPastTheLaneCapFoldTheirWaitAndCountAsDropped() throws Exception {
        Trace t = Tempo.start(SEARCH, opts().lanes(2));
        Span root = t.root();
        Handoff a = root.handoff(MATCH), b = root.handoff(MATCH), c = root.handoff(MATCH);
        clock.add(4);
        assertNull(onThread("w1", () -> a.begin().close()));
        clock.add(3);
        assertNull(onThread("w2", () -> assertSame(NOOP, b.begin())));
        c.begin().close(); // inline on a lane the trace has
        t.close();
        assertEquals(1, t.dropped());
        assertEquals(0, t.misuses());
        // the two tasks that ran, and the waits of all three
        assertEquals("count=2 sum=0 min=0 max=0 waits=3 waitSum=18 waitMax=7", t.aggregate(MATCH).toString());
        assertEquals(lines(
                "search 0+7",
                "  match 0+0 handoff~4 @w1",
                "  match 0+0 handoff~7"), tree(t));
    }

    @Test
    void tasksPastALanesCapAreLeftOutButNotNeverStarted() throws Exception {
        ExecutorService pool = worker("worker-1");
        try {
            Trace t = Tempo.start(SEARCH, opts().budget(Integer.MAX_VALUE)); // no handoff folds
            int n = Tempo.Lane.MAX_EVENTS / 2 + 1; // a task takes two events (link and queue wait)
            Handoff[] hs = handoffs(t.root(), n);
            clock.add(3);
            pool.submit(() -> {
                Span outer = hs[0].begin();
                for (int i = 1; i < n; i++) hs[i].begin().close(); // inline in outer, until the worker's lane is full
                outer.close();
            }).get();
            t.close();
            int[] handoffs = {0}, unstarted = {0};
            t.forEachSpan(v -> {
                if (v.handoff()) handoffs[0]++;
                if (v.neverStarted()) unstarted[0]++;
            });
            assertEquals(n - 1, handoffs[0]);
            assertEquals(0, unstarted[0]);
            assertEquals(1, t.dropped());
            assertEquals("count=" + (n - 1) + " sum=0 min=0 max=0 waits=" + n + " waitSum=" + 3 * n + " waitMax=3",
                    t.aggregate(MATCH).toString());
            assertEquals(0, t.misuses());
        } finally {
            pool.shutdown();
        }
    }

    /** Pool threads interleave tasks of concurrent traces; every span lands in its own trace (real threads and clock). */
    @Test
    void concurrentTracesKeepTheirTasksApart() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        int traces = 8, tasks = 200;
        List<Trace> done = new ArrayList<>();
        try {
            List<Callable<Trace>> requests = new ArrayList<>();
            for (int k = 0; k < traces; k++) {
                long stamp = k;
                requests.add(() -> {
                    Trace t = Tempo.start(SEARCH);
                    List<Future<?>> fs = new ArrayList<>();
                    for (int i = 0; i < tasks; i++) {
                        Handoff h = t.root().handoff(MATCH).set(QUEUED, stamp);
                        fs.add(pool.submit(h.runnable(() -> {
                            Tempo.current().set(BYTES, stamp);
                            Tempo.current().span(LOAD).set(BYTES, stamp).close();
                        })));
                    }
                    for (Future<?> f : fs) f.get();
                    t.close();
                    return t;
                });
            }
            ExecutorService requestThreads = Executors.newFixedThreadPool(traces);
            try {
                for (Future<Trace> f : requestThreads.invokeAll(requests)) done.add(f.get());
            } finally {
                requestThreads.shutdown();
            }
            for (int k = 0; k < traces; k++) {
                Trace t = done.get(k);
                long stamp = k;
                int[] handoffs = {0}, spans = {0};
                t.forEachSpan(v -> {
                    spans[0]++;
                    if (v.handoff()) {
                        handoffs[0]++;
                        assertFalse(v.neverStarted());
                        assertEquals(2, v.attributes());
                    }
                    for (int i = 0; i < v.attributes(); i++) assertEquals(stamp, v.longAttribute(i));
                    if (v.depth() == 2) assertEquals(LOAD, v.name());
                });
                assertEquals(tasks, handoffs[0]);
                assertEquals(1 + 2 * tasks, spans[0]);
                assertEquals(0, t.misuses() + t.dropped());
                assertTrue(t.ctx.lanesById().length <= 1 + 4, "lanes: " + t.ctx.lanesById().length); // root + pool
                t.release();
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void handoffAllocatesOneSmallObjectAndTheTaskNothing() throws Exception {
        ExecutorService pool = worker("worker-1");
        int n = 2_000;
        try {
            for (int round = 0; round < 3; round++) { // fills the chunk pool and loads every class on the path
                Trace warm = Tempo.start(SEARCH, opts().budget(Integer.MAX_VALUE));
                Handoff[] hs = handoffs(warm.root(), 2 * n);
                pool.submit(() -> run(hs)).get();
                warm.close();
                warm.release();
            }
            Trace t = Tempo.start(SEARCH, opts().budget(Integer.MAX_VALUE));
            try (t) {
                Handoff[] first = handoffs(t.root(), 1);
                pool.submit(() -> run(first)).get(); // creates the worker's lane for the trace
                Handoff[] hs = new Handoff[n];
                long submit = allocatedBy(() -> {
                    for (int i = 0; i < n; i++) hs[i] = t.root().handoff(MATCH);
                });
                assertTrue(submit > 0 && submit % n == 0 && submit / n <= 32, "handoff() allocated " + submit + " B for " + n);
                assertEquals(0, pool.submit(() -> allocatedBy(() -> run(hs))).get(), "bytes the tasks allocated");
            }
            assertEquals(0, t.misuses() + t.dropped());
            t.release();
        } finally {
            pool.shutdown();
        }
    }

    /** Past the budget a handoff still allocates its one object, and its task, folded, nothing. */
    @Test
    void foldedHandoffAllocatesOneSmallObjectAndTheTaskNothing() throws Exception {
        ExecutorService pool = worker("worker-1");
        int n = 2_000;
        try {
            for (int round = 0; round < 3; round++) { // loads every class on the path
                Trace warm = Tempo.start(SEARCH, opts().budget(0));
                Handoff[] hs = handoffs(warm.root(), n);
                pool.submit(() -> run(hs)).get();
                warm.close();
                warm.release();
            }
            Trace t = Tempo.start(SEARCH, opts().budget(0));
            try (t) {
                Handoff[] first = handoffs(t.root(), 1);
                pool.submit(() -> run(first)).get(); // creates the aggregates of both lanes
                Handoff[] hs = new Handoff[n];
                long submit = allocatedBy(() -> {
                    for (int i = 0; i < n; i++) hs[i] = t.root().handoff(MATCH);
                });
                assertTrue(submit > 0 && submit % n == 0 && submit / n <= 32, "handoff() allocated " + submit + " B for " + n);
                assertEquals(0, pool.submit(() -> allocatedBy(() -> run(hs))).get(), "bytes the tasks allocated");
            }
            assertEquals(n + 1, t.folded());
            assertEquals(n + 1, t.aggregate(MATCH).count());
            assertEquals(0, t.misuses() + t.dropped());
            t.release();
        } finally {
            pool.shutdown();
        }
    }

    private static Handoff[] handoffs(Span s, int n) {
        Handoff[] hs = new Handoff[n];
        for (int i = 0; i < n; i++) hs[i] = s.handoff(MATCH);
        return hs;
    }

    private static void run(Handoff[] hs) {
        for (Handoff h : hs) {
            Span s = h.begin();
            s.span(LOAD).close();
            s.close();
        }
    }

    // ---- lane-less aggregates -------------------------------------------------------------------------

    @Test
    void lanelessAggregatesAreExactAtCoarse() throws Exception {
        Trace t = Tempo.start(SEARCH, opts());
        t.add(MATCH, 5);
        t.add(MATCH, 3);
        t.add(MATCH, 9);
        t.add(MATCH, -4); // clamped
        long token = t.submitted();
        clock.add(7);
        assertEquals(7, t.waited(POOL_WAIT, token));
        t.addWait(POOL_WAIT, 2);
        assertNull(onOtherThread(() -> t.addWait(POOL_WAIT, 11)));
        assertEquals("count=4 sum=17 min=0 max=9 waits=0 waitSum=0 waitMax=0", t.aggregate(MATCH).toString());
        assertEquals("count=0 sum=0 min=0 max=0 waits=3 waitSum=20 waitMax=11", t.aggregate(POOL_WAIT).toString());
        assertEquals("count=0 sum=0 min=0 max=0 waits=0 waitSum=0 waitMax=0", t.aggregate(DECODE).toString());
        assertEquals(0, t.aggregate(null).count());
        t.close();
        assertEquals(lines("search 0+7"), tree(t)); // no span, no lane
        assertEquals(1, t.ctx.lanesById().length);
        t.add(MATCH, 1); // after close: still counted
        assertEquals(5, t.aggregate(MATCH).count());
        t.release();
        t.add(MATCH, 1);
        assertEquals(0, t.aggregate(MATCH).count());
    }

    @Test
    void lanelessAggregatesGrowPastTheirFirstTable() {
        Trace t = Tempo.start(SEARCH, opts());
        Name[] names = new Name[40];
        for (int i = 0; i < names.length; i++) names[i] = Tempo.name("aggregate-" + i);
        for (int round = 1; round <= 3; round++)
            for (int i = 0; i < names.length; i++) t.add(names[i], i * round);
        for (int i = 0; i < names.length; i++) {
            Tempo.Aggregate a = t.aggregate(names[i]);
            assertEquals(3, a.count());
            assertEquals(6L * i, a.sumNanos());
            assertEquals(i, a.minNanos());
            assertEquals(3L * i, a.maxNanos());
        }
        t.close();
    }

    @Test
    void lanelessAggregatesAreExactUnderContention() throws Exception {
        Trace t = Tempo.start(SEARCH, opts());
        int threads = 8, adds = 20_000;
        Name[] names = {MATCH, LOAD, DECODE};
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int k = 0; k < threads; k++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < adds; i++) {
                        t.add(names[i % 3], i % 100);
                        t.addWait(names[i % 3], 1 + i % 50);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        long count = 0, sum = 0, waits = 0, waitSum = 0;
        for (Name n : names) {
            Tempo.Aggregate a = t.aggregate(n);
            count += a.count();
            sum += a.sumNanos();
            waits += a.waitCount();
            waitSum += a.waitSumNanos();
            assertEquals(0, a.minNanos());
            assertEquals(99, a.maxNanos());
            assertEquals(50, a.waitMaxNanos());
        }
        long perThreadSum = 0, perThreadWait = 0;
        for (int i = 0; i < adds; i++) {
            perThreadSum += i % 100;
            perThreadWait += 1 + i % 50;
        }
        assertEquals((long) threads * adds, count);
        assertEquals(threads * perThreadSum, sum);
        assertEquals((long) threads * adds, waits);
        assertEquals(threads * perThreadWait, waitSum);
        assertEquals(1, t.ctx.lanesById().length);
        t.close();
    }

    @Test
    void lanelessAggregatesAllocateNothingOnceWarm() {
        Trace t = Tempo.start(SEARCH, opts());
        Runnable calls = () -> {
            for (int i = 0; i < 10_000; i++) {
                t.add(MATCH, i);
                t.waited(POOL_WAIT, t.submitted());
            }
        };
        calls.run();
        assertEquals(0, allocatedBy(calls));
        t.close();
    }

    // ---- always-on names and listeners ----------------------------------------------------------------
    // Marks and global sinks are global: each test marks names of its own.

    @Test
    void listenerFiresAtCoarse() {
        List<Long> global = new ArrayList<>(), local = new ArrayList<>();
        Name fetch = Tempo.name("coarse-fetch").sink(global::add), hash = Tempo.name("coarse-hash");
        Trace t = Tempo.start(SEARCH, opts());
        t.on(hash, local::add);
        Span s = t.root();
        assertEquals(COARSE, t.level());
        Span f = s.detail(fetch);
        assertNotSame(NOOP, f);
        clock.add(7);
        assertEquals(7, f.elapsedNanos());
        f.close();
        clock.add(1);
        assertEquals(7, f.elapsedNanos());
        Span h = s.deep(hash);
        clock.add(3);
        h.close();
        assertSame(NOOP, s.detail(MATCH)); // not always-on
        t.close();
        assertEquals(List.of(7L), global);
        assertEquals(List.of(3L), local);
        assertEquals(lines( // aggregate-only: no rows, an aggregate each
                "search 0+11",
                "  coarse-fetch 0+7 agg*1 min=7 max=7 maxAt=0 extent=7",
                "  coarse-hash 8+3 agg*1 min=3 max=3 maxAt=0 extent=3"), tree(t));
        assertEquals("count=1 sum=7 min=7 max=7 waits=0 waitSum=0 waitMax=0", t.aggregate(fetch).toString());
        assertEquals(3, t.aggregate(hash).sumNanos());
        assertEquals(0, t.misuses());
        t.release();
        assertEquals(0, f.elapsedNanos());
    }

    @Test
    void aggregateOnlySpansTimeThemselvesAndIgnoreTheRest() {
        Name parse = Tempo.name("agg-parse").aggregate();
        Trace t = Tempo.start(SEARCH, opts());
        Span s = t.root();
        for (long d : new long[] {2, 3, 5}) {
            Span p = s.detail(parse);
            clock.add(d);
            p.close();
        }
        Span p = s.detail(parse);
        assertSame(p, Tempo.current());
        assertTrue(p.recording());
        assertSame(NOOP, p.detail(MATCH));
        p.set(BYTES, 1).set(SCORE, 0.5).fail().cancel().waiting().cpu()
                .phase(DECODE).done(DECODE).done(DECODE, "x").legacy().phaseSet(BYTES, 2);
        assertEquals(1, (int) p.lap(DECODE, () -> 1));
        Span inner = p.span(LOAD);         // recorded, under the nearest recorded span
        Span deeper = inner.detail(parse); // aggregate-only above a recorded span
        assertSame(deeper, Tempo.current());
        clock.add(4);
        deeper.close();
        inner.close();
        clock.add(1);
        p.close();
        t.close();
        assertEquals(lines(
                "search 0+15",
                "  agg-parse 0+15 agg*4 min=2 max=5 maxAt=5 extent=15", // no row: one aggregate per anchor
                "  load 10+4",
                "    agg-parse 10+4 agg*1 min=4 max=4 maxAt=0 extent=4"), tree(t));
        assertEquals("count=5 sum=19 min=2 max=5 waits=0 waitSum=0 waitMax=0", t.aggregate(parse).toString());
        assertEquals(0, t.misuses());
    }

    @Test
    void anAlwaysOnNameIsASpanAtItsLevelAndAggregateOnlyBelow() {
        Name sort = Tempo.name("agg-sort").aggregate();
        List<Long> calls = new ArrayList<>();
        Trace t = Tempo.start(SEARCH, opts().level(DETAIL));
        t.on(sort, calls::add);
        Span s = t.root();
        Span a = s.detail(sort);
        clock.add(2);
        a.close();
        Span b = s.deep(sort);
        clock.add(3);
        b.close();
        t.close();
        assertEquals(lines("search 0+5", "  agg-sort 0+2 detail", "  agg-sort 2+3 agg*1 min=3 max=3 maxAt=0 extent=3"), tree(t));
        assertEquals(List.of(2L, 3L), calls);
    }

    @Test
    void aggregateOnlySpansOnNoopForeignThreadsAndCaps() throws Exception {
        Name merge = Tempo.name("agg-merge").aggregate();
        assertSame(NOOP, NOOP.detail(merge));
        assertSame(NOOP, NOOP.deep(merge));

        Trace t = Tempo.start(SEARCH, opts());
        Span s = t.root();
        assertNull(onOtherThread(() -> assertSame(NOOP, s.detail(merge))));
        assertEquals(1, t.misuses());
        s.detail(merge);
        clock.add(4);
        t.close(); // heals the open aggregate-only span, which still counts
        assertEquals(2, t.misuses());
        assertEquals(4, t.aggregate(merge).sumNanos());
        assertEquals(lines("search 0+4", "  agg-merge 0+4 agg*1 min=4 max=4 maxAt=0 extent=4"), tree(t));

        Trace u = Tempo.start(SEARCH, opts());
        Span x = u.root();
        for (int i = 1; i < 35; i++) x = x.span(LOAD);
        assertSame(NOOP, x.detail(merge));
        assertEquals(1, u.dropped());
        u.close();
    }

    @Test
    void listenersFireOncePerEndOnTheEndingThread() throws Exception {
        Name span = Tempo.name("l-span"), phase = Tempo.name("l-phase"), lap = Tempo.name("l-lap"),
                task = Tempo.name("l-task"), sum = Tempo.name("l-sum");
        Thread main = Thread.currentThread();
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        Trace t = Tempo.start(SEARCH, opts());
        for (Name n : new Name[] {SEARCH, span, phase, lap, task, sum}) {
            t.on(n, nanos -> calls.add(n + "=" + nanos
                    + (Thread.currentThread() == main ? "" : "@" + Thread.currentThread().getName())));
        }
        Span s = t.root();
        s.phase(phase);
        clock.add(2);
        s.phase(phase);  // ends the first phase implicitly
        clock.add(3);
        s.done(phase);   // ends the open phase
        Span x = s.span(span);
        clock.add(1);
        x.close();
        clock.add(4);
        s.done(lap);     // a lap from the last boundary
        Span z = s.span(span);
        z.span(span);
        clock.add(2);
        z.close();       // heals its child: both end
        Handoff h = s.handoff(task);
        assertNull(onThread("worker", () -> {
            Span w = h.begin();
            clock.add(6);
            w.close();
        }));
        assertNull(onThread("adder", () -> t.add(sum, 9)));
        clock.add(1);
        t.close();
        assertEquals(List.of("l-phase=2", "l-phase=3", "l-span=1", "l-lap=5", "l-span=2", "l-span=2",
                "l-task=6@worker", "l-sum=9@adder", "search=19"), calls);
        assertEquals(1, t.misuses());
    }

    @Test
    void listenersAreAddedOnTheStartingThreadBeforeTheFirstChildSpan() throws Exception {
        Name late = Tempo.name("late-heard"), merge = Tempo.name("agg-freeze").aggregate();
        List<Long> calls = new ArrayList<>();
        Trace t = Tempo.start(SEARCH, opts());
        t.on(null, calls::add).on(late, null);
        assertNull(onOtherThread(() -> t.on(late, calls::add)));
        assertEquals(3, t.misuses());
        t.root().span(DECODE).close();
        t.on(late, calls::add);
        assertEquals(4, t.misuses());
        t.root().span(late).close();
        assertSame(NOOP, t.root().detail(late)); // not always-on in this trace
        t.close();
        assertEquals(List.of(), calls);

        Trace u = Tempo.start(SEARCH, strict());
        u.root().detail(merge).close(); // an aggregate-only span is a child span too
        assertThrows(AssertionError.class, () -> u.on(late, calls::add));
        u.close();
    }

    @Test
    void aThrowingListenerIsCountedAndTheRecordingStaysConsistent() {
        Name boom = Tempo.name("boom");
        LongConsumer throwing = n -> {
            throw new IllegalStateException("listener");
        };
        Trace t = Tempo.start(SEARCH, opts());
        t.on(boom, throwing).on(SEARCH, throwing);
        Span b = t.root().span(boom);
        clock.add(2);
        b.close();
        t.add(boom, 3);
        assertSame(t.root(), Tempo.current());
        t.close();
        assertSame(NOOP, Tempo.current());
        assertEquals(3, t.misuses());
        assertEquals(lines("search 0+2", "  boom 0+2"), tree(t));

        Trace u = Tempo.start(SEARCH, strict());
        u.on(boom, throwing).on(SEARCH, throwing);
        Span c = u.root().span(boom);
        AssertionError e = assertThrows(AssertionError.class, c::close);
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertSame(u.root(), Tempo.current());
        assertThrows(AssertionError.class, () -> u.add(boom, 1));
        assertThrows(AssertionError.class, u::close); // thrown once the root has closed and the thread let go
        assertSame(NOOP, Tempo.current());
        assertEquals(lines("search 0+0", "  boom 0+0"), tree(u));
    }

    @Test
    void aThrowingGlobalSinkDoesNotSkipTheSinksAfterIt() {
        List<Long> got = new ArrayList<>();
        Name rerank = Tempo.name("sink-isolated").sink(n -> {
            throw new IllegalStateException("first");
        }).sink(got::add).sink(n -> {
            throw new IllegalStateException("third");
        }).sink(n -> got.add(-n));
        Trace t = Tempo.start(SEARCH, opts());
        t.add(rerank, 5);
        t.close();
        assertEquals(List.of(5L, -5L), got);
        assertEquals(2, t.misuses());

        Trace u = Tempo.start(SEARCH, strict());
        AssertionError e = assertThrows(AssertionError.class, () -> u.add(rerank, 7));
        assertEquals("first", e.getCause().getMessage());
        assertEquals(List.of(5L, -5L, 7L, -7L), got);
        u.close();
    }

    @Test
    void secondsAndSerializedAdaptListeners() throws Exception {
        double[] seconds = {0};
        Tempo.seconds(v -> seconds[0] = v).accept(1_500_000_000L);
        assertEquals(1.5, seconds[0]);

        long[] count = {0}, total = {0}; // not thread-safe on its own
        Name n = Tempo.name("serial-sink").sink(Tempo.serialized(v -> {
            count[0]++;
            total[0] += v;
        }));
        Trace t = Tempo.start(SEARCH, opts());
        int threads = 8, adds = 50_000;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int k = 0; k < threads; k++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < adds; i++) t.add(n, i % 7);
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        long perThread = 0;
        for (int i = 0; i < adds; i++) perThread += i % 7;
        assertEquals((long) threads * adds, count[0]);
        assertEquals(threads * perThread, total[0]);
        t.close();
    }

    @Test
    void timeReportsTheWallTimeOfABodyWithoutATrace() {
        List<Long> got = new ArrayList<>();
        assertEquals(42, (int) Tempo.time(got::add, () -> 42));
        Tempo.time(got::add, () -> {
            got.size();
        });
        IOException e = assertThrows(IOException.class, () -> Tempo.time(got::add, () -> {
            throw new IOException("unwrapped");
        }));
        assertEquals("unwrapped", e.getMessage());
        assertEquals(3, got.size());
        for (long v : got) assertTrue(v >= 0);
        assertEquals("x", Tempo.time(null, () -> "x"));
        assertSame(NOOP, Tempo.current());
    }

    @Test
    void timeNeverLetsAThrowingSinkReachTheCaller() throws Exception {
        LongConsumer bad = nanos -> {
            throw new IllegalStateException("sink");
        };
        IOException e = assertThrows(IOException.class, () -> Tempo.time(bad, () -> {
            throw new IOException("body");
        }));
        assertEquals("body", e.getMessage());
        assertEquals(1, e.getSuppressed().length);
        assertEquals("sink", e.getSuppressed()[0].getMessage());

        IOException r = assertThrows(IOException.class, () -> Tempo.time(bad, (Tempo.ThrowingRunnable<IOException>) () -> {
            throw new IOException("body");
        }));
        assertEquals("sink", r.getSuppressed()[0].getMessage());

        assertEquals(7, (int) Tempo.time(bad, () -> 7));
        int[] ran = {0};
        Tempo.time(bad, () -> {
            ran[0]++;
        });
        assertEquals(1, ran[0]);
    }

    // ---- CPU time -------------------------------------------------------------------------------------

    /** {@code name=cpu} per span in visiting order. */
    static String cpus(Trace t) {
        StringBuilder b = new StringBuilder();
        t.forEachSpan(s -> b.append(b.length() == 0 ? "" : " ").append(s.name()).append('=').append(s.cpuNanos()));
        return b.toString();
    }

    @Test
    void cpuTimeIsMeasuredAtLaneRootsWhenDetailedAndOnRequest() throws Exception {
        Clock cpu = new Clock();
        Trace t = Tempo.start(SEARCH, opts().level(DETAIL).cpuClock(cpu));
        Span s = t.root();
        Span a = s.span(LOAD);
        cpu.add(5);
        clock.add(10);
        a.close();
        Span b = s.span(MATCH).cpu();
        cpu.add(3);
        clock.add(4);
        b.close();
        Span c = s.span(JOIN).cpu().cpu(); // asking twice keeps the first read
        c.span(SCORE).cpu();
        cpu.add(2);
        c.close(); // heals SCORE, which is measured too
        Handoff h = s.handoff(DECODE);
        assertNull(onThread("worker", () -> {
            Span w = h.begin();
            cpu.add(7);
            clock.add(9);
            w.close();
        }));
        Span open = s.span(RERANK).cpu();
        cpu.add(1);
        assertEquals("search=-1 load=-1 match=3 join=2 score=2 decode=7 rerank=-1", cpus(t)); // open: not yet
        open.close();
        t.close();
        assertEquals("search=18 load=-1 match=3 join=2 score=2 decode=7 rerank=1", cpus(t));
        assertEquals(1, t.misuses());
    }

    @Test
    void cpuTimeFollowsTheLevelUnlessForced() {
        Clock cpu = new Clock();
        Options[] options = {
                opts().cpuClock(cpu),                           // COARSE: never, not even on request
                opts().cpuClock(cpu).cpu(true),                 // forced on at COARSE
                opts().cpuClock(cpu).level(DETAIL).cpu(false),  // forced off at DETAIL
                opts().cpuClock(cpu),                           // raised to DETAIL after the root opened
                opts().cpuClock(() -> -1).level(DETAIL),        // a clock that reads -1: not measured
        };
        List<String> got = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            Trace t = Tempo.start(SEARCH, options[i]);
            if (i == 3) assertTrue(t.raise(DETAIL));
            Span x = t.root().span(LOAD).cpu();
            cpu.add(4);
            x.close();
            t.close();
            got.add(cpus(t));
        }
        assertEquals(List.of("search=-1 load=-1", "search=4 load=4", "search=-1 load=-1",
                "search=-1 load=4", "search=-1 load=-1"), got);
    }

    @Test
    void realCpuTimeIsReadFromTheThread() {
        assumeTrue(Tempo.Cpu.now() >= 0, "thread CPU time is not supported here");
        Trace t = Tempo.start(SEARCH, opts().level(DETAIL));
        Span s = t.root().span(LOAD).cpu();
        long x = 1;
        for (int i = 0; i < 1_000_000; i++) x = x * 31 + i;
        s.set(BYTES, x).close();
        t.close();
        t.forEachSpan(v -> assertTrue(v.cpuNanos() >= 0, v.name() + " cpu " + v.cpuNanos()));
    }

    @Test
    void tempoLoadsAndRecordsWithoutJavaManagement() throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String cp = location(WithoutManagement.class) + File.pathSeparator + location(Tempo.class);
        Process p = new ProcessBuilder(java, "--limit-modules", "java.base", "-cp", cp, WithoutManagement.class.getName())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), out);
        assertEquals(0, p.exitValue(), out);
        assertEquals("management=false search cpu=-1 decode cpu=-1", out);
    }

    static String location(Class<?> c) throws Exception {
        return Paths.get(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    @Test
    void aggregateOnlySpansAndListenersAllocateNothingOnceWarm() {
        Name agg = Tempo.name("alloc-agg").aggregate(), heard = Tempo.name("alloc-heard");
        long[] sum = {0};
        Trace t = Tempo.start(SEARCH, opts());
        t.on(heard, n -> sum[0] += n);
        Span s = t.root();
        Runnable calls = () -> {
            for (int i = 0; i < 10_000; i++) {
                s.detail(agg).close();
                Span h = s.deep(heard);
                clock.add(1);
                h.close();
                t.add(heard, 1);
            }
        };
        for (int i = 0; i < 3; i++) calls.run();
        assertEquals(0, allocatedBy(calls));
        t.close();
        assertEquals(80_000, sum[0]);
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
            Trace warm = Tempo.start(SEARCH, opts().level(DETAIL).budget(Integer.MAX_VALUE));
            record(warm.root(), spans);
            warm.close();
            warm.release();
        }

        Trace t = Tempo.start(SEARCH, opts().level(DETAIL).budget(Integer.MAX_VALUE));
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
