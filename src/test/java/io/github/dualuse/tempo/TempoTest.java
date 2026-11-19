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
import java.util.Map;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static io.github.dualuse.tempo.Tempo.SPAN;
import static io.github.dualuse.tempo.Tempo.STEP;
import static io.github.dualuse.tempo.Tempo.TICK;
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
        assertNothingLeftOpen();
    }

    /**
     * Fails if a test left a span open on the thread, after closing it: a trace left open would make every
     * later trace on the thread join it, so one failure would fail every test after it.
     */
    static void assertNothingLeftOpen() {
        Span left = Tempo.current();
        for (int i = 0; i < 1000 && Tempo.current() != NOOP; i++) Tempo.current().close();
        assertSame(NOOP, left, "a test left a span open on the test thread");
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
            if (s.level() == STEP) b.append(" step");
            if (s.level() == TICK) b.append(" tick");
            if (s.phase()) b.append(" phase");
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
        assertEquals("?", Tempo.name(null).toString());
        assertSame(Tempo.name("?"), Tempo.name(null));
        assertEquals("", Tempo.name("").toString()); // the name of unnamed spans
        assertNotSame(Tempo.name(null), Tempo.name(""));
    }

    // ---- spans and nesting ----------------------------------------------------------------------------

    @Test
    void rootSpanHasExactDuration() {
        Trace t = Tempo.trace(SEARCH, opts());
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
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span root = t;
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
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span root = t;
            root.span(DECODE).span(LOAD).close();
            Tempo.span(RERANK).close(); // lands under the still-open decode
            List<String> seen = new ArrayList<>();
            t.close();
            t.forEachSpan(s -> seen.add(s.id() + "<" + s.parent() + ":" + s.name() + "@" + s.thread()));
            String th = Thread.currentThread().getName();
            assertEquals(List.of("0<-1:search@" + th, "1<0:decode@" + th, "2<1:load@" + th, "3<1:rerank@" + th), seen);
        }
    }

    @Test
    void staticSpansOpenUnderTheInnermostOpenSpan() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            try (Span a = Tempo.span(DECODE)) {
                clock.add(1);
                Tempo.span(LOAD).close();
                Tempo.step(MATCH).close(); // undrawn, but timed
                Tempo.tick(SCORE).close(); // not recorded
                clock.add(1);
            }
            assertSame(t, Tempo.current());
            t.close();
            assertEquals(lines(
                    "search 0+2",
                    "  decode 0+2",
                    "    load 1+0"), tree(t));
            assertEquals(1, t.total(MATCH).count());
            assertEquals(0, t.misuses());
        }
    }

    @Test
    void aSpanThatIsNotTheInnermostClosesWhatIsAboveItBeforeItOpensAChild() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span d = t.span(DECODE);
            d.span(LOAD);
            clock.add(1);
            t.span(RERANK).close(); // closes load and decode, healed, then opens under the trace
            assertEquals(1, t.misuses());
            d.close(); // already closed
            assertEquals(2, t.misuses());
            t.close();
            assertEquals(lines(
                    "search 0+1",
                    "  decode 0+1 healed",
                    "    load 0+1 healed",
                    "  rerank 1+0"), tree(t));
        }
        try (Trace t = Tempo.trace(SEARCH, strict())) {
            Span d = t.span(DECODE);
            AssertionError e = assertThrows(AssertionError.class, () -> t.span(LOAD));
            assertEquals("Tempo misuse: span opened under a span that is not the innermost", e.getMessage());
            assertFalse(d.recording() && d.elapsedNanos() < 0);
        }
    }

    @Test
    void aSpanWithAnOpenPhaseIsTheInnermost() {
        try (Trace t = Tempo.trace(SEARCH, strict())) {
            t.phase(DECODE);
            t.span(LOAD).close(); // a child of the span, beside the phase
            t.close();
            assertEquals(lines("search 0+0", "  decode 0+0 phase", "  load 0+0"), tree(t));
            assertEquals(0, t.misuses());
        }
    }

    @Test
    void openSpansRenderAsOpenAtOneNow() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            clock.add(10);
            Span d = t.span(DECODE);
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
        Trace t = Tempo.trace(SEARCH, opts());
        t.span(DECODE).close();
        List<Boolean> live = new ArrayList<>();
        t.forEachSpan(s -> live.add(s.live()));
        assertEquals(List.of(true, true), live);
        t.close();
        t.fail(); // a late annotation leaves the lane idle again
        live.clear();
        t.forEachSpan(s -> live.add(s.live()));
        assertEquals(List.of(false, false), live);
    }

    @Test
    void elapsedOfNoopIsZero() {
        assertEquals(0, NOOP.elapsedNanos());
        assertFalse(NOOP.recording());
    }

    @Test
    void stringNamesAreLookedUp() {
        try (Trace t = Tempo.trace("GET /search", opts()).records(TICK)) {
            t.span("decode").set("bytes", 3L).set("score", 0.5).close();
            Tempo.span("load").close();
            t.step("match").close();
            Tempo.step("match").close();
            t.tick("score").close();
            Tempo.tick("score").close();
            Tempo.run("join", () -> clock.add(2));
            assertEquals(4, (int) Tempo.call("join", () -> 4));
            t.run("join", () -> { });
            assertEquals(5, (int) t.call("join", () -> 5));
            t.phase("rerank").done("rerank");
            t.add("rerank", 7);
            t.close();
            assertEquals(lines(
                    "GET /search 0+2",
                    "  decode 0+0 bytes=3 score=0.5",
                    "  load 0+0",
                    "  match 0+0 step",
                    "  match 0+0 step",
                    "  score 0+0 tick",
                    "  score 0+0 tick",
                    "  join 0+2",
                    "  join 2+0",
                    "  join 2+0",
                    "  join 2+0",
                    "  rerank 2+0 phase"), tree(t));
            assertSame(t.total(MATCH).count(), t.total("match").count());
            assertEquals(2, t.total("rerank").count());
            assertEquals(7, t.total("rerank").sum());
            assertEquals(0, t.total("never-interned-anywhere").count());
            assertEquals(0, t.misuses());
        }
    }

    @Test
    void stringNamesPastTheCapMapToOtherAsMisuse() {
        int cap = Tempo.Name.capDynamic(1); // one more name from a string, then the table is full
        try {
            try (Trace t = Tempo.trace(SEARCH, opts())) {
                Tempo.span("cap-first").close();     // the one that still fits
                t.span("cap-overflow").close();
                assertEquals(1, t.misuses());
                Tempo.span("cap-overflow").set("cap-key", 1L).close();
                assertEquals(3, t.misuses());
                Tempo.span("cap-first").close();     // interned before the cap: still itself
                Tempo.span("<other>").close();       // the name itself is no misuse
                assertEquals(3, t.misuses());
                t.close();
                assertEquals(lines(
                        "search 0+0",
                        "  cap-first 0+0",
                        "  <other> 0+0 misuse",
                        "  <other> 0+0 misuse <other>=1",
                        "  cap-first 0+0",
                        "  <other> 0+0"), tree(t));
            }
            Trace t = Tempo.trace(SEARCH, strict());
            AssertionError e = assertThrows(AssertionError.class, () -> t.span("cap-overflow-strict"));
            assertEquals("Tempo misuse: name table full: 4096 names from strings", e.getMessage());
            t.close();
            assertEquals(lines("search 0+0"), tree(t));
        } finally {
            Tempo.Name.restoreCap(cap);
        }
    }

    @Test
    void outsideATraceSpansAreNoopsUnlessTheNameHasAGlobalListener() {
        Name heard = Tempo.name("outside-heard"), unheard = Tempo.name("outside-unheard");
        List<Long> got = Collections.synchronizedList(new ArrayList<>());
        Tempo.on(heard, got::add);
        assertSame(NOOP, Tempo.span(unheard));
        assertSame(NOOP, Tempo.step(unheard));
        assertSame(NOOP, Tempo.tick(heard));            // ticks are timed only in a trace that records them
        assertSame(NOOP, Tempo.span("outside-never-interned"));
        Span s = Tempo.span(heard);
        assertNotSame(NOOP, s);
        assertFalse(s.recording());
        assertSame(s, Tempo.current());
        Span inner = Tempo.step(heard);                   // a step is timed too
        assertSame(NOOP, inner.span(unheard));
        inner.set(BYTES, 1).fail().phase(LOAD).done(LOAD); // nothing to record: no-ops
        assertSame(Tempo.Handoff.NOOP, inner.handoff(LOAD));
        inner.close();
        Tempo.run(heard, () -> { });
        Tempo.span("outside-heard").close();
        s.add(heard, 42);
        s.close();
        assertSame(NOOP, Tempo.current());
        assertEquals(5, got.size());
        assertEquals(42L, (long) got.get(3));
    }

    @Test
    void anUnnamedSpanHasTheEmptyName() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            try (Span s = Tempo.span()) {
                s.phase(DECODE);
                t.close();
            }
            int[] n = {0};
            t.forEachSpan(v -> {
                if (v.depth() == 1) {
                    assertEquals("", v.name().toString());
                    n[0]++;
                }
            });
            assertEquals(1, n[0]);
            assertTrue(t.toHeader().contains(";N=search::decode;"), t.toHeader()); // the empty name
            assertEquals(2, t.misuses()); // the close of the trace healed the unnamed span, which then closed again
        }
    }

    // ---- misuse, non-strict -------------------------------------------------------------------------

    @Test
    void closingAParentHealsOpenChildren() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span d = t.span(DECODE);
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
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span d = t.span(DECODE);
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
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span d = t.span(DECODE);
            d.close();
            assertSame(NOOP, d.span(LOAD));
            assertEquals(1, t.misuses());
            t.close();
            assertSame(NOOP, t.span(LOAD));
            assertEquals(2, t.misuses());
            assertEquals(lines("search 0+0", "  decode 0+0"), tree(t));
        }
    }

    @Test
    void closingTheTraceHealsForgottenSpans() {
        Trace t = Tempo.trace(SEARCH, opts());
        t.span(DECODE);
        clock.add(3);
        t.close();
        assertEquals(1, t.misuses());
        assertEquals(lines("search 0+3", "  decode 0+3 healed"), tree(t));
    }

    @Test
    void foreignThreadCallsAreIgnoredAndCounted() throws Exception {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span d = t.span(DECODE);
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
        Trace t = Tempo.trace(SEARCH, opts());
        assertNull(onOtherThread(t::close));
        assertEquals(1, t.misuses());
        clock.add(2);
        t.close();
        assertEquals(lines("search 0+2"), tree(t));
    }

    @Test
    void nullNamesBecomeQuestionMarkAndEmptyOnesNameUnnamedSpans() {
        Trace t = Tempo.trace((Name) null, opts());
        Span root = t;
        root.span((Name) null).close();
        root.span((String) null).close();
        root.span(Tempo.name("?")).close(); // an interned "?" is a legitimate name
        root.span("").close();              // unnamed, not misuse
        root.span().close();
        root.set((Name) null, 1);
        t.close();
        assertEquals(4, t.misuses());
        assertEquals(lines(
                "? 0+0 misuse ?=1",
                "  ? 0+0 misuse",
                "  ? 0+0 misuse",
                "  ? 0+0",
                "   0+0",
                "   0+0"), tree(t));
    }

    @Test
    void depthIsCappedAt35() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span s = t;
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
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span root = t;
            for (int i = 1; i < Tempo.Lane.MAX_ROWS; i++) root.span(LOAD).close();
            assertFalse(t.span(LOAD).recording());
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
        try (Trace t = Tempo.trace(SEARCH, strict())) {
            Span d = t.span(DECODE);
            d.close();
            AssertionError e = assertThrows(AssertionError.class, d::close);
            assertEquals("Tempo misuse: span used after close", e.getMessage());
            assertEquals(1, t.misuses());
        }
    }

    @Test
    void strictThrowsOnOutOfOrderCloseAfterHealing() {
        Trace t = Tempo.trace(SEARCH, strict());
        Span d = t.span(DECODE);
        d.span(LOAD);
        clock.add(5);
        assertThrows(AssertionError.class, d::close);
        t.close();
        assertEquals(lines("search 0+5", "  decode 0+5", "    load 0+5 healed"), tree(t));
    }

    @Test
    void strictThrowsOnNullNamesBeforeRecording() {
        Trace t = Tempo.trace(SEARCH, strict());
        assertThrows(AssertionError.class, () -> t.span((Name) null));
        assertThrows(AssertionError.class, () -> t.span((String) null));
        assertThrows(AssertionError.class, () -> t.set((Name) null, 1L));
        assertThrows(AssertionError.class, () -> Tempo.trace((Name) null, strict())); // nested: t is strict
        t.close();
        assertEquals(lines("search 0+0"), tree(t));
    }

    @Test
    void strictThrowsOnTheForeignThread() throws Exception {
        try (Trace t = Tempo.trace(SEARCH, strict())) {
            Throwable e = onOtherThread(() -> t.span(DECODE));
            assertTrue(e instanceof AssertionError);
            assertEquals("Tempo misuse: span used on a foreign thread", e.getMessage());
        }
    }

    @Test
    void strictThrowsOnRefusedWidening() {
        try (Trace t = Tempo.trace(SEARCH, strict())) {
            assertSame(t, t.records(STEP));
            AssertionError e = assertThrows(AssertionError.class, () -> t.records(TICK));
            assertEquals("Tempo misuse: records widened twice", e.getMessage());
            assertEquals(STEP, t.records());
        }
        try (Trace t = Tempo.trace(SEARCH, strict())) {
            t.tick(MATCH).close();
            AssertionError e = assertThrows(AssertionError.class, () -> t.records(STEP));
            assertEquals("Tempo misuse: records widened after a step or tick opened", e.getMessage());
            assertEquals(SPAN, t.records());
        }
    }

    @Test
    void strictStaysSilentOnCorrectUse() {
        try (Trace t = Tempo.trace(SEARCH, strict()).records(TICK)) {
            t.run(DECODE, () -> Tempo.step(LOAD).set(BYTES, 3).close());
            t.tick(MATCH).fail().close();
            assertEquals(0, t.misuses());
        }
    }

    // ---- levels ---------------------------------------------------------------------------------------

    @Test
    void aTraceOfSpansTimesStepsWithoutDrawingThemAndSkipsTicks() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span root = t;
            assertEquals(SPAN, t.records());
            Span d = root.step(LOAD);
            assertNotSame(NOOP, d);
            assertTrue(d.recording());
            assertSame(d, d.set(BYTES, 1).set(SCORE, 0.5).fail().cancel().waiting()); // no row to keep them
            clock.add(4);
            try (Span m = d.span(MATCH)) { // under the nearest drawn span
                clock.add(2);
            }
            d.close();
            assertEquals(6, d.elapsedNanos());
            assertSame(NOOP, root.tick(MATCH));
            t.close();
            assertEquals(lines("search 0+6", "  match 4+2"), tree(t));
            Tempo.Total load = t.total(LOAD);
            assertEquals(1, load.count());
            assertEquals(6, load.sum());
            assertEquals(0, t.total(SCORE).count());
            assertEquals(0, t.misuses());
        }
    }

    @Test
    void stepsAndTicksFollowWhatTheTraceRecords() {
        try (Trace t = Tempo.trace(SEARCH, opts()).records(STEP)) {
            Span root = t;
            root.step(LOAD).close();
            assertSame(NOOP, root.tick(MATCH));
            t.close();
            assertEquals(lines("search 0+0", "  load 0+0 step"), tree(t));
        }
        try (Trace t = Tempo.trace(SEARCH, opts()).records(TICK)) {
            try (Span l = t.step(LOAD)) {
                l.tick(MATCH).close();
            }
            t.close();
            assertEquals(lines("search 0+0", "  load 0+0 step", "    match 0+0 tick"), tree(t));
        }
    }

    @Test
    void levelsAreClamped() {
        try (Trace t = Tempo.trace(SEARCH, opts()).records(7)) {
            assertEquals(TICK, t.records());
        }
        try (Trace t = Tempo.trace(SEARCH, opts()).records(-3)) {
            assertEquals(SPAN, t.records());
        }
    }

    @Test
    void recordsWidensOnceBeforeAnyStepOrTick() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            t.span(LOAD).close();  // a span does not fix the level
            t.records(SPAN);       // asking for what is there is not a widening
            assertEquals(0, t.misuses());
            t.records(STEP);
            assertEquals(STEP, t.records());
            t.records(TICK);       // a second widening is refused
            assertEquals(STEP, t.records());
            t.records(STEP);
            assertEquals(1, t.misuses());
        }
    }

    @Test
    void recordsCannotWidenAfterAStepOrTickOpened() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            t.step(LOAD).close(); // undrawn, but it fixed what the trace draws
            t.records(STEP);
            assertEquals(SPAN, t.records());
            assertEquals(1, t.misuses());
        }
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Tempo.tick(LOAD).close(); // not timed, and still too late after it
            t.records(TICK);
            assertEquals(SPAN, t.records());
            assertEquals(1, t.misuses());
        }
        try (Trace t = Tempo.trace(SEARCH, opts()).records(STEP)) {
            t.step(LOAD).close();
            t.records(TICK);
            assertEquals(STEP, t.records());
            assertEquals(1, t.misuses());
        }
    }

    // ---- attributes and flags -------------------------------------------------------------------------

    @Test
    void attributesKeepTypeValueAndOrder() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span root = t;
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
        try (Trace t = Tempo.trace(SEARCH, opts()).records(STEP)) {
            Span root = t;
            root.span(DECODE).fail().close();
            root.span(LOAD).fail(new IOException("gone")).close();
            root.span(MATCH).cancel().close();
            try (Span j = root.step(JOIN).waiting()) {
                clock.add(9);
            }
            t.close();
            assertEquals(lines(
                    "search 0+9",
                    "  decode 0+0 failed",
                    "  load 0+0 failed",
                    "  match 0+0 cancelled",
                    "  join 0+9 step waiting"), tree(t));
        }
    }

    @Test
    void annotationsWorkAfterCloseForCatchBlocks() {
        Trace t = Tempo.trace(SEARCH, opts());
        Span d = t.span(DECODE);
        try (d) {
            throw new IllegalStateException("bad input");
        } catch (IllegalStateException e) {
            d.fail(e).set(BYTES, 7);
        }
        t.close();
        t.fail().set(SCORE, 1.5); // the lane root has closed too
        assertEquals(0, t.misuses());
        assertEquals(lines("search 0+0 failed score=1.5", "  decode 0+0 failed bytes=7"), tree(t));
        t.release();
        t.fail(); // after release: ignored, and counted as misuse
        assertEquals(1, t.misuses());
        assertEquals("", tree(t));
    }

    // ---- handles ------------------------------------------------------------------------------------

    /** Every recorded span has a handle of its own, so a late call through it reaches that span. */
    @Test
    void eachRecordedSpanKeepsItsHandleForTheWholeTrace() {
        Trace t = Tempo.trace(SEARCH, strict());
        Span d = t.span(DECODE);
        d.close();
        Span l = t.span(LOAD);           // the next span at the same depth
        assertNotSame(d, l);
        Span m = l.span(MATCH);
        m.close();
        l.close();
        d.fail().set(BYTES, 7);          // all after close, all on the span they name
        m.set(SCORE, 0.5);
        l.cancel();
        t.close();
        assertEquals(lines("search 0+0", "  decode 0+0 failed bytes=7", "  load 0+0 cancelled", "    match 0+0 score=0.5"), tree(t));
        assertEquals(0, t.misuses());
    }

    /**
     * A handle whose trace was released sits in its thread's pool: every call through it is ignored and
     * counted as misuse in the released trace, also when another thread released it, and also for a call
     * from another thread. An unrecorded tick is a no-op and is not checked outside strict mode.
     */
    @Test
    void aHandleUsedAfterReleaseIsIgnoredAndCountedAsMisuse() throws Exception {
        Trace t = Tempo.trace(SEARCH, opts());
        Span s = t.span(DECODE);
        clock.add(3);
        s.close();
        t.close();
        assertNull(onThread("releaser", t::release));
        assertSame(Tempo.Lane.POOLED, s.lane);
        assertEquals(0, t.misuses());

        s.set(BYTES, 1).set(SCORE, 0.5).fail().cancel().waiting().add(MATCH, 5);
        assertSame(NOOP, s.span(LOAD));
        assertSame(NOOP, s.step(LOAD));
        assertSame(Tempo.Phase.NOOP, s.phase(RERANK));
        assertSame(s, s.done(RERANK));
        assertSame(NOOP, s.tick(LOAD));  // not counted
        s.close();
        assertEquals(11, t.misuses());
        assertNull(onThread("other", () -> s.add(MATCH, 5)));
        assertEquals(12, t.misuses());
        assertEquals(0, s.elapsedNanos());
        assertFalse(s.recording());
        assertEquals(12, t.misuses());   // reads are not misuse
        assertEquals("", tree(t));
    }

    /** Strict mode never pools a handle: a stale call throws, even after the thread has started more traces. */
    @Test
    void strictModeNeverReusesAHandleAndThrowsOnEveryStaleCall() throws Exception {
        assertNull(onThread("strict", () -> {
            Trace t = Tempo.trace(SEARCH, strict());
            Span a = t.span(DECODE), b = a.span(LOAD);
            b.close();
            a.close();
            t.close();
            t.release();
            assertEquals(0, Tempo.SLOT.get().pooled());
            Trace u = Tempo.trace(RERANK, strict());
            Span x = u.span(DECODE), y = x.span(LOAD);
            assertNotSame(a, x);
            assertNotSame(b, y);
            assertNotSame(a, y);
            assertNotSame(b, x);
            y.close();
            x.close();
            u.close();
            for (Runnable stale : new Runnable[] {
                    () -> a.set(BYTES, 1), () -> a.set(SCORE, 0.5), b::fail, b::cancel, b::waiting,
                    () -> a.add(MATCH, 1), () -> a.span(LOAD), () -> a.step(LOAD), () -> a.tick(LOAD),
                    () -> a.phase(RERANK), () -> a.done(RERANK), a::close}) {
                AssertionError e = assertThrows(AssertionError.class, stale::run);
                assertTrue(e.getMessage().contains("released"), e.getMessage());
            }
            assertEquals(12, t.misuses());
            assertEquals(0, u.misuses());
            assertEquals(lines("rerank 0+0", "  decode 0+0", "    load 0+0"), tree(u));
            u.release();
        }));
    }

    /**
     * Released, a trace gives its handles back to the thread, which issues them to the spans of its next
     * trace: no new handle once warm. From then on the old variable is the new span, undetected; this is
     * why strict mode never pools.
     */
    @Test
    void aReleasedTraceHandsItsHandlesToTheThreadsNextTrace() throws Exception {
        assertNull(onThread("pool", () -> {
            Trace t = Tempo.trace(SEARCH, opts());
            Span a = t.span(DECODE), b = a.span(LOAD);
            b.close();
            a.close();
            t.close();
            assertEquals(0, Tempo.SLOT.get().pooled());
            t.release();
            assertEquals(2, Tempo.SLOT.get().pooled());
            a.set(BYTES, 1);             // stale while pooled: ignored, counted
            assertEquals(1, t.misuses());

            Trace u = Tempo.trace(RERANK, opts());
            Span x = u.span(MATCH), y = x.span(JOIN);
            assertEquals(0, Tempo.SLOT.get().pooled());
            assertTrue(x == a && y == b || x == b && y == a, "the released trace's handles, issued again");
            Span z = y.span(DECODE);     // the pool is empty: a new handle
            assertTrue(z != a && z != b);
            b.set(BYTES, 2);             // b is a span of u now: the call lands there, undetected
            z.close();
            y.close();
            x.close();
            u.close();
            String bName = b == x ? "match" : "join";
            String tree = tree(u);
            assertTrue(tree.contains(bName + " 0+0 bytes=2"), tree);
            assertEquals(0, u.misuses());
            assertEquals(1, t.misuses());
            u.release();
            assertEquals(3, Tempo.SLOT.get().pooled());
        }));
    }

    /**
     * Folded and undrawn spans write no row and share one handle per depth: the next one at that depth
     * has the same handle, while a recorded span has its own. Released, the shared ones are pooled too.
     */
    @Test
    void foldedAndUndrawnSpansShareOneHandlePerDepth() throws Exception {
        assertNull(onThread("frames", () -> {
            Trace t = Tempo.trace(SEARCH, opts().budget(0)).records(STEP);
            Span f = t.step(LOAD);
            Span g = f.step(MATCH);      // depth 2: a handle of its own depth
            assertNotSame(f, g);
            g.close();
            f.close();
            Span h = t.step(DECODE);     // depth 1 again: the same handle
            assertSame(f, h);
            assertSame(g, h.step(JOIN));
            Tempo.current().close();
            h.close();
            Span r = t.span(RERANK);     // recorded: its own handle
            assertNotSame(f, r);
            r.close();
            t.close();
            assertEquals(lines(
                    "search 0+0",
                    "  rerank 0+0",
                    "  load 0+0 agg*1 min=0 max=0 maxAt=0 extent=0",
                    "    match 0+0 agg*1 min=0 max=0 maxAt=0 extent=0",
                    "  decode 0+0 agg*1 min=0 max=0 maxAt=0 extent=0",
                    "    join 0+0 agg*1 min=0 max=0 maxAt=0 extent=0"), tree(t));

            Trace u = Tempo.trace(SEARCH, opts());   // records spans only: steps are undrawn frames
            Span v = u.step(LOAD);
            v.close();
            assertSame(v, u.step(DECODE));
            Tempo.current().close();
            u.close();
            assertEquals(1, u.total(LOAD).count());
            assertEquals(1, u.total(DECODE).count());

            t.release();
            assertEquals(3, Tempo.SLOT.get().pooled()); // f, g and r
            f.fail();                    // stale: counted
            assertEquals(1, t.misuses());
            u.release();
        }));
    }

    /**
     * As the Span Javadoc says: a span folded past the budget has no row, so it keeps no attributes, and
     * flags only while it is open; a flag after close (as in a catch block) is dropped, without misuse.
     */
    @Test
    void aFoldedSpanTakesFlagsOnlyWhileOpenAndNoAttributes() {
        Trace t = Tempo.trace(SEARCH, strict().budget(0)).records(STEP);
        Span open = t.step(LOAD);
        open.fail().set(BYTES, 7);       // open: the flag shows on the aggregate, the attribute is dropped
        open.close();
        Span late = t.step(DECODE);
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

    // ---- phases -----------------------------------------------------------------------------------------

    @Test
    void phasesSectionTheSpanAndParentNothing() {
        Trace t = Tempo.trace(SEARCH, opts());
        Span s = t;
        clock.add(5);
        Tempo.Phase decode = s.phase(DECODE);
        assertNotSame(Tempo.Phase.NOOP, decode);
        clock.add(10);
        try (Span l = s.span(LOAD)) {
            clock.add(4);
        }
        Span j = s.span(JOIN);       // crosses the next boundary
        clock.add(1);
        Tempo.Phase rerank = s.phase(RERANK); // one clock read ends decode and starts rerank
        assertNotSame(decode, rerank);
        clock.add(5);
        j.close();
        clock.add(25);
        s.span(MATCH).close();
        clock.add(10);
        t.close(); // ends the open phase normally: neither healed nor misuse
        assertEquals(lines(
                "search 0+60",
                "  decode 5+15 phase",
                "  load 15+4",
                "  join 19+6",
                "  rerank 20+40 phase",
                "  match 50+0"), tree(t));
        assertEquals(15, decode.elapsedNanos());
        assertEquals(40, rerank.elapsedNanos());
        assertEquals(0, t.misuses());
    }

    @Test
    void currentIsTheSpanNotItsPhase() {
        try (Trace t = Tempo.trace(SEARCH, strict())) {
            Span s = t;
            s.phase(DECODE);
            assertSame(s, Tempo.current());
            Tempo.current().span(LOAD).close();  // a child of the span, beside the phase
            Tempo.current().phase(RERANK);
            clock.add(3);
            Tempo.current().done(RERANK);
            t.close();
            assertEquals(lines(
                    "search 0+3",
                    "  decode 0+0 phase",
                    "  load 0+0",
                    "  rerank 0+3 phase"), tree(t));
            assertEquals(0, t.misuses());
        }
    }

    @Test
    void doneNamesTheTimeSinceTheLastBoundary() {
        Trace t = Tempo.trace(SEARCH, opts());
        Span s = t;
        clock.add(2);
        try (Span l = s.span(LOAD)) {
            clock.add(3);
            l.span(MATCH).close();
            clock.add(5);
            l.span(MATCH).close();
        }
        clock.add(5);
        assertSame(s, s.done(DECODE)); // from the span's start: nothing open, nothing before
        clock.add(5);
        try (Span m = s.span(MATCH)) {
            clock.add(7);
        }
        clock.add(3);
        s.lap(RERANK);                 // a synonym
        clock.add(7);
        s.span(LOAD).close();          // unphased time of the root
        clock.add(3);
        t.close();
        assertEquals(lines(
                "search 0+40",
                "  decode 0+15 phase",
                "  load 2+8",
                "    match 5+0",
                "    match 10+0",
                "  rerank 15+15 phase",
                "  match 20+7",
                "  load 37+0"), tree(t));
        List<String> ids = new ArrayList<>();
        t.forEachSpan(v -> ids.add(v.id() + "<" + v.parent() + "@" + v.depth()));
        assertEquals(List.of("0<-1@0", "1<0@1", "2<0@1", "3<2@2", "4<2@2", "5<0@1", "6<0@1", "7<0@1"), ids);
        assertEquals(0, t.misuses());
    }

    @Test
    void timeAfterTheLastPhaseStaysUnphased() {
        Trace t = Tempo.trace(SEARCH, opts());
        clock.add(1);
        try (Span r = t.span(RERANK)) {
            clock.add(4);
            r.done(LOAD);       // the first named stretch starts at the span's start
            clock.add(2);
            r.done(MATCH);
            clock.add(9);       // rerank's own, in no phase
        }
        t.close();
        assertEquals(lines(
                "search 0+16",
                "  rerank 1+15",
                "    load 1+4 phase",
                "    match 5+2 phase"), tree(t));
    }

    @Test
    void doneEndsTheOpenPhaseAndTheNextNameStartsThere() {
        Trace t = Tempo.trace(SEARCH, opts());
        Span s = t;
        clock.add(1);
        s.phase(DECODE);
        clock.add(8);
        assertSame(s, s.done(DECODE));
        clock.add(2);
        s.span(LOAD).close();
        s.done(RERANK);
        assertSame(s, s.done());        // nothing open: nothing to do
        clock.add(4);
        t.close();
        assertEquals(lines(
                "search 0+15",
                "  decode 1+8 phase",
                "  rerank 9+2 phase",
                "  load 11+0"), tree(t));
        assertEquals(0, t.misuses());
    }

    @Test
    void aPhaseEndsAndContinuesTheSequenceFromItsHandle() {
        Trace t = Tempo.trace(SEARCH, strict());
        clock.add(1);
        Span s = t.phase(DECODE).set(BYTES, 3).phase(RERANK).label("Rerank").done();
        assertSame(t, s);
        clock.add(2);
        assertSame(t, t.phase(MATCH).lap());
        clock.add(3);
        t.close();
        assertEquals(lines(
                "search 0+6",
                "  decode 1+0 phase bytes=3",
                "  rerank 1+0 phase 'Rerank'",
                "  match 3+0 phase"), tree(t));
        assertEquals(0, t.misuses());
    }

    /**
     * A phase's handle is pooled like a span's: once the trace is released, a call through it is ignored and
     * counted in that trace, and the thread issues it to a phase of its next trace. Strict mode never pools
     * one, so there a stale call always throws.
     */
    @Test
    void aPhaseHandleIsPooledLikeASpans() throws Exception {
        assertNull(onThread("phases", () -> {
            Trace t = Tempo.trace(SEARCH, opts());
            Tempo.Phase p = t.phase(DECODE);
            t.close();
            t.release();
            assertEquals(1, Tempo.SLOT.get().pooled());
            p.set(BYTES, 1).fail().label("x");
            assertSame(Tempo.Phase.NOOP, p.phase(LOAD));
            assertSame(NOOP, p.done());
            assertEquals(0, p.elapsedNanos());
            assertEquals(5, t.misuses());
            Trace u = Tempo.trace(RERANK, opts());
            assertSame(p, u.phase(MATCH)); // issued again
            u.close();
            u.release();
        }));
        assertNull(onThread("strict-phases", () -> {
            Trace t = Tempo.trace(SEARCH, strict());
            Tempo.Phase p = t.phase(DECODE);
            t.close();
            t.release();
            assertEquals(0, Tempo.SLOT.get().pooled());
            AssertionError e = assertThrows(AssertionError.class, () -> p.set(BYTES, 1));
            assertTrue(e.getMessage().contains("released"), e.getMessage());
            Trace u = Tempo.trace(RERANK, strict());
            assertNotSame(p, u.phase(MATCH));
            u.close();
            u.release();
        }));
    }

    @Test
    void mismatchedDoneEndsThePhaseUnderItsOwnName() {
        Trace t = Tempo.trace(SEARCH, opts());
        Span s = t;
        s.phase(DECODE);
        clock.add(5);
        s.done(RERANK);
        assertEquals(1, t.misuses());
        clock.add(3);
        s.span(MATCH).close();
        s.done(LOAD); // names the time since decode ended
        t.close();
        assertEquals(lines(
                "search 0+8",
                "  decode 0+5 phase misuse",
                "  load 5+3 phase",
                "  match 8+0"), tree(t));
        assertEquals(1, t.misuses());
        assertEquals(1, t.total(DECODE).count());
        assertEquals(0, t.total(RERANK).count());
    }

    @Test
    void strictThrowsOnMismatchedDoneAndKeepsThePhaseOpen() {
        Trace t = Tempo.trace(SEARCH, strict());
        Span s = t;
        s.phase(DECODE);
        clock.add(5);
        AssertionError e = assertThrows(AssertionError.class, () -> s.done(RERANK));
        assertEquals("Tempo misuse: done(rerank) while phase decode is open", e.getMessage());
        clock.add(1);
        t.close();
        assertEquals(lines(
                "search 0+6",
                "  decode 0+6 phase"), tree(t));
    }

    @Test
    void boundariesLeaveTheSpansOpenInThem() {
        for (Options o : new Options[] {opts(), strict()}) {
            Trace t = Tempo.trace(SEARCH, o);
            Span s = t;
            Span load = s.span(LOAD);
            clock.add(4);
            s.phase(DECODE);       // load stays open: a child may cross a boundary
            clock.add(2);
            load.close();
            clock.add(3);
            s.done(DECODE);
            load = s.span(LOAD);
            s.done(MATCH);         // a named stretch, load still open
            clock.add(1);
            load.close();
            t.close();
            assertEquals(lines(
                    "search 0+10",
                    "  load 0+6",
                    "  decode 4+5 phase",
                    "  match 9+0 phase",
                    "  load 9+1"), tree(t));
            assertEquals(0, t.misuses());
        }
    }

    @Test
    void labelsAndAttributesOnPhases() {
        Trace t = Tempo.trace(SEARCH, opts());
        Span s = t;
        Tempo.Phase d = s.phase(DECODE).label("Decode Input #3 = v2(x)").set(BYTES, 7);
        clock.add(4);
        Tempo.Phase r = s.phase(RERANK);
        r.set(SCORE, 0.5).fail();
        clock.add(6);
        s.done();
        r.label("Rerank & Match");       // after it ended
        d.set(BYTES, 9);                 // after it ended: a repeated key keeps the last value on the wire
        clock.add(2);
        s.done(MATCH);
        t.close();
        assertEquals(lines(
                "search 0+12",
                "  decode 0+4 phase 'Decode Input #3 = v2(x)' bytes=7 bytes=9",
                "  rerank 4+6 phase 'Rerank & Match' failed score=0.5",
                "  match 10+2 phase"), tree(t));
        assertEquals("0.000000012s = 0.000000004s (Decode Input #3 = v2(x)) + 0.000000006s (Rerank & Match)", t.toRuntime(9));
        assertEquals(0, t.misuses());
    }

    @Test
    void manyLabelsKeepTheirPhases() {
        Trace t = Tempo.trace(SEARCH, opts());
        for (int i = 0; i < 20; i++) {
            t.phase(LOAD).label("load #" + i);
            clock.add(1);
        }
        t.close();
        List<String> labels = new ArrayList<>();
        t.forEachSpan(v -> labels.add(v.startNanos() + ":" + v.label()));
        assertEquals("0:null", labels.get(0));
        for (int i = 0; i < 20; i++) assertEquals(i + ":load #" + i, labels.get(i + 1));
    }

    /** A render racing the label table's growth reads every label it sees whole (stress, weak memory). */
    @Test
    void liveRenderSeesEveryCommittedLabel() throws Exception {
        String[] text = new String[64];
        for (int i = 0; i < text.length; i++) text[i] = "load #" + i;
        AtomicReference<Trace> live = new AtomicReference<>();
        AtomicReference<String> bad = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            for (int round = 0; round < 20000 && bad.get() == null; round++) {
                Trace t = Tempo.trace(SEARCH, opts());
                live.set(t);
                for (String s : text) t.phase(LOAD).label(s);
                t.close();
            }
            live.set(null);
        });
        owner.start();
        while (owner.isAlive()) {
            Trace t = live.get();
            if (t != null) t.forEachSpan(v -> {
                if (v.phase() && v.label() != null && !v.label().equals(text[v.id() - 1])) {
                    bad.set("phase " + v.id() + " rendered with label " + v.label());
                }
            });
        }
        owner.join();
        assertNull(bad.get());
    }

    @Test
    void phaseCallsOnAClosedSpanOrWithoutALabelAreMisuse() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span j = t.span(JOIN);
            Tempo.Phase p = j.phase(LOAD);
            j.close();                     // ends its phase too
            assertSame(Tempo.Phase.NOOP, p.phase(MATCH));
            assertSame(j, j.done(LOAD));
            p.label(null);
            p.set(BYTES, 1);               // allowed after it ended
            assertEquals(3, t.misuses());
            t.close();
            assertEquals(lines("search 0+0", "  join 0+0", "    load 0+0 phase bytes=1"), tree(t));
        }
        try (Trace t = Tempo.trace(SEARCH, strict())) {
            AssertionError e = assertThrows(AssertionError.class, () -> t.phase(LOAD).label(null));
            assertEquals("Tempo misuse: null label", e.getMessage());
        }
    }

    @Test
    void nullNamesInPhases() {
        Trace t = Tempo.trace(SEARCH, opts());
        Span s = t;
        s.phase((Name) null);
        clock.add(2);
        s.done((Name) null);     // ends the open phase; no second misuse for the mismatch
        clock.add(3);
        s.done((Name) null);
        t.close();
        assertEquals(3, t.misuses());
        assertEquals(lines(
                "search 0+5",
                "  ? 0+2 phase misuse",
                "  ? 2+3 phase misuse"), tree(t));
        Trace u = Tempo.trace(SEARCH, strict());
        assertThrows(AssertionError.class, () -> u.phase((Name) null));
        assertThrows(AssertionError.class, () -> u.done((Name) null));
        u.close();
        assertEquals(lines("search 0+0"), tree(u));
    }

    @Test
    void phasesOnAForeignThreadOrNoopAreIgnored() throws Exception {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span s = t;
            Tempo.Phase p = s.phase(DECODE);
            assertNull(onOtherThread(() -> {
                assertSame(Tempo.Phase.NOOP, s.phase(LOAD));
                s.done();
                s.done(LOAD);
                s.lap(5L);
                s.start(1L);
                s.stop(2L);
                p.set(BYTES, 1).fail().label("x");
            }));
            assertEquals(9, t.misuses());
            assertSame(Tempo.Phase.NOOP, NOOP.phase(DECODE));
            assertSame(NOOP, NOOP.phase(DECODE).set(BYTES, 1).fail().label("x").phase(LOAD).done());
            assertSame(NOOP, NOOP.done(LOAD).lap(3L).start(1L).stop(2L).done().lap());
            assertEquals(0, Tempo.Phase.NOOP.elapsedNanos());
            t.close();
            assertEquals(lines("search 0+0", "  decode 0+0 phase"), tree(t));
            assertEquals(9, t.misuses());
        }
    }

    @Test
    void aPhaseIsARowNotADepth() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span s = t;
            for (int d = 1; d < 35; d++) s = s.span(LOAD);
            clock.add(1);
            s.phase(DECODE); // a row under the deepest span: it needs no room on the stack
            clock.add(2);
            s.done();
            s.done(MATCH);
            assertEquals(0, t.dropped());
            assertEquals(0, t.misuses());
            List<String> deepest = new ArrayList<>();
            t.forEachSpan(v -> {
                if (v.depth() == 35) deepest.add(v.name() + " " + v.startNanos() + "+" + v.durationNanos());
            });
            assertEquals(List.of("decode 1+2", "match 3+0"), deepest);
        }
    }

    @Test
    void aPhasePastTheRowCapIsDroppedButStillABoundary() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            for (int i = 1; i < Tempo.Lane.MAX_ROWS; i++) t.span(LOAD).close();
            clock.add(1);
            Tempo.Phase p = t.phase(DECODE); // no row left: dropped
            p.set(BYTES, 1).fail().label("Decode");
            assertEquals(0, p.elapsedNanos());
            clock.add(2);
            t.done(DECODE);                  // still the open phase: no mismatch
            assertEquals(1, t.dropped());
            assertEquals(0, t.misuses());
            assertEquals(0, t.total(DECODE).count()); // like a span past the cap
            assertEquals("0.000s", t.toRuntime(3));
        }
    }

    @Test
    void phasesOfFoldedAndUndrawnSpansCountTowardsTheTotals() {
        for (int mode = 0; mode < 2; mode++) {
            List<Long> heard = new ArrayList<>();
            Trace t = Tempo.trace(SEARCH, opts().budget(0)).records(mode == 0 ? SPAN : STEP); // undrawn, folded
            t.on(DECODE, heard::add);
            try (Span step = t.step(MATCH)) {
                Tempo.Phase p = step.phase(DECODE);
                assertNotSame(Tempo.Phase.NOOP, p);
                p.set(BYTES, 1).label("ignored");   // no row to keep it
                clock.add(3);
                step.phase(RERANK);
                clock.add(2);
                step.done(RERANK);
                step.done(LOAD);                   // a named stretch of no time
            }
            t.close();
            assertEquals(List.of(3L), heard);
            assertEquals("count=1 sum=3 mean=3 min=3 max=3 waitSum=0 waitMax=0", t.total(DECODE).toString());
            assertEquals(2, t.total(RERANK).sum());
            assertEquals(1, t.total(LOAD).count());
            assertFalse(tree(t).contains("phase"), tree(t));
            assertEquals(0, t.misuses());
        }
    }

    @Test
    void strictStaysSilentOnCorrectPhases() throws Exception {
        try (Trace t = Tempo.trace(SEARCH, strict())) {
            Span s = t;
            s.done(DECODE);
            s.phase(RERANK).set(BYTES, 1).label("Rerank");
            s.span(LOAD).close();
            s.done(RERANK).phase(MATCH).fail();
            s.lap(clock.t);
            s.done(JOIN);
            assertEquals(0, t.misuses());
        }
    }

    @Test
    void phasesAllocateNothingOnceWarm() {
        int rounds = 2_000; // three rows per round
        for (int i = 0; i < 3; i++) { // as large as the trace measured: the thread pools as many handles
            Trace warm = Tempo.trace(SEARCH, opts());
            phases(warm, rounds + 1);
            warm.close();
            warm.release();
        }
        Trace t = Tempo.trace(SEARCH, opts());
        phases(t, 1); // creates the phase state, the handles and the event table
        assertEquals(0, allocatedBy(() -> phases(t, rounds)), "bytes allocated by " + rounds + " rounds");
        t.close();
        assertEquals(0, t.misuses());
        t.release();
    }

    private void phases(Span root, int rounds) {
        for (int i = 0; i < rounds; i++) {
            root.phase(DECODE).set(BYTES, i).fail();
            root.span(LOAD).close();
            clock.add(1);
            root.done(DECODE).done(RERANK);
            root.lap(clock.t);
        }
    }

    // ---- timing by hand --------------------------------------------------------------------------------

    @Test
    void aSpanTimedByHand() {
        List<Long> heard = new ArrayList<>();
        Trace t = Tempo.trace(SEARCH, strict()).on(LOAD, heard::add);
        long zero = clock.t;
        clock.add(100);
        t.span(LOAD).phase(DECODE).start(zero + 20).lap(zero + 50).phase(RERANK).stop(zero + 90).close();
        clock.add(10);
        t.close();
        assertEquals(lines(
                "search 0+110",
                "  load 20+70",
                "    decode 20+30 phase",
                "    rerank 50+40 phase"), tree(t));
        assertEquals(List.of(70L), heard);
        assertEquals(30, t.total(DECODE).sum());
        assertEquals(40, t.total(RERANK).sum());
        assertEquals(0, t.misuses());
    }

    @Test
    void aSpanStartedByHandBeforeItsSiblingsComesFirst() {
        Trace t = Tempo.trace(SEARCH, strict());
        long zero = clock.t;
        clock.add(10);
        t.span(MATCH).close();
        clock.add(10);
        Span l = t.span(LOAD).start(zero + 5); // within the root's window, before match
        l.lap(zero + 8);                       // a boundary without a phase
        l.phase(DECODE);                       // timed by hand: starts at that boundary
        l.done();
        l.close();
        t.close();
        assertEquals(lines(
                "search 0+20",
                "  load 5+15",
                "    decode 8+12 phase",
                "  match 10+0"), tree(t));
        assertEquals(0, t.misuses());
    }

    @Test
    void timesOutsideTheWindowAreClampedFlaggedAndCounted() {
        Trace t = Tempo.trace(SEARCH, opts());
        long zero = clock.t;
        clock.add(100);
        Span p = t.span(LOAD);
        clock.add(50);
        Span x = p.span(MATCH);
        x.start(zero + 10);              // before its parent: at the parent's start
        x.lap(zero + 90);                // before the last boundary: there
        x.phase(DECODE);                 // timed by hand: starts at that boundary
        x.stop(zero + 500);              // after now: now
        assertEquals(3, t.misuses());
        assertSame(Tempo.Phase.NOOP, x.phase(RERANK)); // after stop
        x.lap(zero + 150);
        x.stop(zero + 150);
        x.start(zero + 100);
        assertEquals(7, t.misuses());
        clock.add(5);
        x.close();                       // keeps the stopped end
        p.close();
        t.start(zero + 1);               // the root starts at the trace's zero point
        assertEquals(8, t.misuses());
        clock.add(5);
        t.close();
        assertEquals(lines(
                "search 0+160 misuse",
                "  load 100+55",
                "    match 100+50 misuse",
                "      decode 100+50 phase misuse"), tree(t));
        assertEquals(50, t.total(MATCH).sum());
    }

    @Test
    void aStartByHandStaysBeforeTheChildrenAndTheFirstBoundary() {
        Trace t = Tempo.trace(SEARCH, opts());
        long zero = clock.t;
        Span x = t.span(LOAD);
        clock.add(10);
        x.span(MATCH).close();
        clock.add(10);
        x.start(zero + 15);              // after its child: at the child's start
        assertEquals(1, t.misuses());
        Span y = x.span(JOIN);
        clock.add(5);
        y.phase(DECODE);                 // its first boundary, at 25
        clock.add(5);
        y.done();
        y.start(zero + 28);              // after the first boundary: there
        assertEquals(2, t.misuses());
        y.close();
        x.close();
        t.close();
        assertEquals(lines(
                "search 0+30",
                "  load 10+20 misuse",
                "    match 10+0",
                "    join 25+5 misuse",
                "      decode 25+5 phase"), tree(t));
    }

    @Test
    void strictThrowsOnATimeOutsideTheWindow() {
        Trace t = Tempo.trace(SEARCH, strict());
        long zero = clock.t;
        clock.add(10);
        Span x = t.span(LOAD);
        AssertionError e = assertThrows(AssertionError.class, () -> x.start(zero - 1));
        assertEquals("Tempo misuse: start(nanos) outside the parent's time window", e.getMessage());
        assertThrows(AssertionError.class, () -> x.lap(zero + 50));
        assertThrows(AssertionError.class, () -> x.stop(zero + 5));
        assertThrows(AssertionError.class, () -> x.stop(zero + 10));
        x.close();
        t.close();
        assertEquals(4, t.misuses());
    }

    // ---- run / call -----------------------------------------------------------------------------------

    @Test
    void runAndCallTimeTheirBody() {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span root = t;
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
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Span root = t;
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
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            assertSame(t, Tempo.current());
            try (Span d = t.span(DECODE)) {
                assertSame(d, Tempo.current());
                Tempo.current().span(LOAD).close();
            }
            assertSame(t, Tempo.current());
            t.close();
            assertSame(NOOP, Tempo.current());
            assertEquals(lines("search 0+0", "  decode 0+0", "    load 0+0"), tree(t));
        }
    }

    @Test
    void nestedTracesRestoreTheOuterOne() {
        try (Trace outer = Tempo.trace(SEARCH, opts())) {
            try (Trace inner = Tempo.trace(RERANK, opts())) {
                assertSame(inner, Tempo.current());
            }
            assertSame(outer, Tempo.current());
        }
        assertSame(NOOP, Tempo.current());
    }

    @Test
    void nestedTracesClosedOutOfOrderHealTheInnerOnes() {
        Trace a = Tempo.trace(SEARCH, opts());
        Trace b = Tempo.trace(DECODE, opts());
        Trace c = Tempo.trace(RERANK, opts());
        b.close(); // heals c
        assertSame(a, Tempo.current());
        c.close(); // closed already: a trace closes idempotently
        assertSame(a, Tempo.current());
        a.close();
        assertSame(NOOP, Tempo.current());
        assertEquals(1, a.misuses());
        assertEquals(lines("search 0+0", "  decode 0+0", "    rerank 0+0 healed"), tree(a));
    }

    @Test
    void aNestedTraceJoinsTheOuterOneAndRendersItsSubtree() {
        List<Long> heard = new ArrayList<>();
        Trace outer = Tempo.trace(SEARCH, opts().wallClock(() -> 1794567890123L));
        clock.add(10_000);
        Trace inner;
        try (Span d = Tempo.span(DECODE)) {
            clock.add(5_000);
            inner = Tempo.trace(RERANK, strict()); // its options are ignored: it is part of the outer trace
            assertSame(inner, Tempo.current());
            inner.on(MATCH, heard::add);
            inner.phase(LOAD).label("Load");
            clock.add(3_000);
            Tempo.span(LOAD).close();
            Tempo.step(MATCH).close();
            inner.done();
            clock.add(2_000);
            inner.close();
            inner.close(); // idempotent
            inner.release(); // the buffers are the outer trace's: nothing happens
        }
        clock.add(1_000);
        outer.close();
        assertEquals(lines(
                "search 0+21000",
                "  decode 10000+10000",
                "    rerank 15000+5000",
                "      load 15000+3000 phase 'Load'",
                "      load 18000+0"), tree(outer));
        assertEquals(lines(
                "rerank 0+5000",
                "  load 0+3000 phase 'Load'",
                "  load 3000+0"), tree(inner));
        String main = Tempo.Header.text(Thread.currentThread().getName());
        assertEquals(Map.of("name", "rerank", "start", 0L, "dur", 5L, "thread", 0L, "id", 0L, "parent", -1L),
                ((List<?>) WireDecoder.decode(inner.toHeader()).get("spans")).get(0));
        assertEquals(1794567890123L, WireDecoder.decode(inner.toHeader()).get("wall")); // 15 µs later: the same millisecond
        assertEquals(main, ((List<?>) WireDecoder.decode(inner.toHeader()).get("threads")).get(0));
        assertEquals("load;dur=0.003, total;dur=0.005", inner.toServerTiming());
        assertEquals(5_000, inner.elapsedNanos());
        assertEquals(5_000, inner.snapshot().elapsedNanos());
        assertEquals(List.of(0L), heard);
        assertEquals(1, inner.total(MATCH).count()); // the totals of the whole trace
        assertEquals(1, outer.total(MATCH).count());
        assertEquals(0, outer.misuses());
        outer.release();
        assertEquals("", inner.toHeader());
    }

    @Test
    void aTraceStartedInATaskJoinsAsASubtreeOnTheWorkersThread() throws Exception {
        ExecutorService pool = worker("worker-1");
        try (Trace t = Tempo.trace(SEARCH, opts())) {
            Handoff h = t.handoff(MATCH);
            String header = pool.submit(() -> {
                try (Span w = h.begin()) {
                    clock.add(2_000);
                    Trace sub = Tempo.trace(LOAD);
                    Tempo.span(DECODE).close();
                    clock.add(1_000);
                    sub.close();
                    return sub.toHeader();
                }
            }).get();
            assertTrue(header.contains(";T=worker-1;N=load:decode;S=00.0.1/11.0.0;"), header);
            t.close();
            assertEquals(lines(
                    "search 0+3000",
                    "  match 0+3000 handoff~0 @worker-1",
                    "    load 2000+1000 @worker-1",
                    "      decode 2000+0 @worker-1"), tree(t));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void aTraceLeftOpenAndReleasedIsNotJoined() {
        Trace leaked = Tempo.trace(SEARCH, opts());
        leaked.release();
        try (Trace t = Tempo.trace(RERANK, opts())) {
            assertSame(t, Tempo.current());
            t.close();
            assertEquals(lines("rerank 0+0"), tree(t));
        }
        assertSame(leaked, Tempo.current());
        leaked.close();
    }

    @Test
    void otherThreadsSeeNoCurrentSpan() throws Exception {
        try (Trace t = Tempo.trace(SEARCH, opts())) {
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
            Trace t = Tempo.trace(SEARCH, opts());
            Span root = t;
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
            Trace t = Tempo.trace(SEARCH, opts());
            Span root = t;
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
        Trace t = Tempo.trace(SEARCH, opts());
        try (Span r = t.span(RERANK)) {
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
        Trace t = Tempo.trace(SEARCH, opts());
        Span root = t;
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
        Trace t = Tempo.trace(SEARCH, opts());
        Handoff h = t.handoff(MATCH);
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
        Trace t = Tempo.trace(SEARCH, opts());
        Handoff h = t.handoff(MATCH);
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

        Trace u = Tempo.trace(SEARCH, strict());
        Handoff g = u.handoff(MATCH);
        g.begin().close();
        AssertionError e = assertThrows(AssertionError.class, g::begin);
        assertEquals("Tempo misuse: handoff begun twice", e.getMessage());
        Handoff k = u.handoff(LOAD);
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

        Trace t = Tempo.trace(SEARCH, opts());
        AtomicReference<Handoff> h = new AtomicReference<>();
        assertNull(onOtherThread(() -> h.set(t.handoff(MATCH))));
        assertSame(Handoff.NOOP, h.get());
        assertEquals(1, t.misuses());
        t.handoff((Name) null).begin().close();
        assertEquals(2, t.misuses());
        t.close();
        assertEquals(lines("search 0+0", "  ? 0+0 misuse handoff~0"), tree(t));
    }

    @Test
    void handoffsChainAcrossWorkers() throws Exception {
        ExecutorService one = worker("worker-1"), two = worker("worker-2");
        try {
            Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
            Handoff h = t.handoff(MATCH);
            one.submit(h.runnable(() -> {
                clock.add(2);
                Handoff g = Tempo.current().handoff(LOAD);
                clock.add(1);
                try {
                    two.submit(g.runnable(() -> {
                        try (Span d = Tempo.current().step(DECODE)) {
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
                    "      decode 3+5 step @worker-2"), tree(t));
        } finally {
            one.shutdown();
            two.shutdown();
        }
    }

    @Test
    void aTaskOfTheSameTraceInsideAnotherRunsInlineButIsDrawnAtItsHandoff() throws Exception {
        ExecutorService pool = worker("worker-1");
        try {
            Trace t = Tempo.trace(SEARCH, opts());
            Handoff first = t.handoff(MATCH), second = t.handoff(LOAD);
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
        ExecutorService pool = worker("worker-1"), ra = worker("request-a"), rb = worker("request-b");
        try {
            Trace a = ra.submit(() -> Tempo.trace(SEARCH, opts())).get();
            Trace b = rb.submit(() -> Tempo.trace(RERANK, opts())).get(); // two requests alive at once
            for (int i = 0; i < 2; i++) {
                int queued = i;
                for (Trace t : List.of(a, b)) {
                    Handoff h = (t == a ? ra : rb).submit(() -> t.handoff(MATCH).set(QUEUED, queued)).get();
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
            rb.submit(b::close).get();
            ra.submit(a::close).get();
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
            ra.shutdown();
            rb.shutdown();
        }
    }

    /** More traces than the slot caches interleave on one pool thread; each keeps one lane there. */
    @Test
    void moreInterleavedTracesThanCachedLanesReuseTheirLane() throws Exception {
        ExecutorService pool = worker("worker-1");
        List<ExecutorService> requests = new ArrayList<>();
        try {
            List<Trace> traces = new ArrayList<>();
            for (int k = 0; k < 6; k++) {
                requests.add(worker("request-" + k)); // a request thread each: a trace started inside another joins it
                traces.add(requests.get(k).submit(() -> Tempo.trace(SEARCH, opts())).get());
            }
            for (int i = 0; i < 3; i++) {
                for (int k = 0; k < traces.size(); k++) {
                    Trace t = traces.get(k);
                    Handoff h = requests.get(k).submit(() -> t.handoff(MATCH)).get();
                    pool.submit(() -> h.begin().close()).get();
                }
            }
            for (int k = traces.size() - 1; k >= 0; k--) requests.get(k).submit(traces.get(k)::close).get();
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
            for (ExecutorService r : requests) r.shutdown();
        }
    }

    /** Begin and close in separate callbacks of one thread: lane roots of two traces close out of order. */
    @Test
    void laneRootsClosedOutOfOrderAndReopenedRestoreTheCurrentSpan() throws Exception {
        Handoff[] hs = new Handoff[3];
        Trace[] ts = new Trace[2];
        assertNull(onThread("request-1", () -> {
            ts[0] = Tempo.trace(SEARCH, opts());
            hs[0] = ts[0].handoff(MATCH);
            hs[1] = ts[0].handoff(LOAD);
            ts[0].close();
        }));
        assertNull(onThread("request-2", () -> {
            ts[1] = Tempo.trace(RERANK, opts());
            hs[2] = ts[1].handoff(MATCH);
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
            Trace a = Tempo.trace(SEARCH, opts());
            Handoff early = a.handoff(LOAD), late = a.handoff(MATCH);
            pool.submit(() -> early.begin().close()).get();
            a.close();
            a.release();

            Trace b = Tempo.trace(RERANK, opts());
            Handoff h = b.handoff(DECODE);
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
        Trace t = Tempo.trace(SEARCH, opts());
        Handoff h = t.handoff(MATCH);
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
        Trace t = Tempo.trace(SEARCH, opts().lanes(n + 2).budget(Integer.MAX_VALUE)); // no handoff folds
        for (int i = 0; i < 8000; i++) t.set(BYTES, i); // a slow first shape widens the window
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        SynchronousQueue<Handoff> queue = new SynchronousQueue<>();
        Handoff first = t.handoff(LOAD);
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
        Handoff[] outer = handoffs(t, n);
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
            Trace b = Tempo.trace(RERANK, opts());
            handoff.set(b.handoff(MATCH));
            other.set(b);
            b.close();
        }));
        Trace a = Tempo.trace(SEARCH, opts());
        try (Span d = a.span(DECODE)) {
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
        Trace t = Tempo.trace(SEARCH, opts());
        Handoff h = t.handoff(MATCH);
        clock.add(1);
        t.close();
        clock.add(2);
        h.runnable(() -> clock.add(3)).run();
        assertEquals(1, t.elapsedNanos());
        t.set(BYTES, 9);
        assertEquals(lines("search 0+1 bytes=9", "  match 0+3 handoff~3"), tree(t));
        assertSame(NOOP, Tempo.current());
    }

    @Test
    void tasksPastTheLaneCapFoldTheirWaitAndCountAsDropped() throws Exception {
        Trace t = Tempo.trace(SEARCH, opts().lanes(2));
        Span root = t;
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
        assertEquals("count=2 sum=0 mean=0 min=0 max=0 waitSum=18 waitMax=7", t.total(MATCH).toString());
        assertEquals(lines(
                "search 0+7",
                "  match 0+0 handoff~4 @w1",
                "  match 0+0 handoff~7"), tree(t));
    }

    @Test
    void tasksPastALanesCapAreLeftOutButNotNeverStarted() throws Exception {
        ExecutorService pool = worker("worker-1");
        try {
            Trace t = Tempo.trace(SEARCH, opts().budget(Integer.MAX_VALUE)); // no handoff folds
            int n = Tempo.Lane.MAX_EVENTS / 2 + 1; // a task takes two events (link and queue wait)
            Handoff[] hs = handoffs(t, n);
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
            assertEquals("count=" + (n - 1) + " sum=0 mean=0 min=0 max=0 waitSum=" + 3 * n + " waitMax=3",
                    t.total(MATCH).toString());
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
                    Trace t = Tempo.trace(SEARCH);
                    List<Future<?>> fs = new ArrayList<>();
                    for (int i = 0; i < tasks; i++) {
                        Handoff h = t.handoff(MATCH).set(QUEUED, stamp);
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
                Trace warm = Tempo.trace(SEARCH, opts().budget(Integer.MAX_VALUE));
                Handoff[] hs = handoffs(warm, 2 * n);
                pool.submit(() -> run(hs)).get();
                warm.close();
                warm.release();
            }
            Trace t = Tempo.trace(SEARCH, opts().budget(Integer.MAX_VALUE));
            try (t) {
                Handoff[] first = handoffs(t, 1);
                pool.submit(() -> run(first)).get(); // creates the worker's lane for the trace
                Handoff[] hs = new Handoff[n];
                long submit = allocatedBy(() -> {
                    for (int i = 0; i < n; i++) hs[i] = t.handoff(MATCH);
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
                Trace warm = Tempo.trace(SEARCH, opts().budget(0));
                Handoff[] hs = handoffs(warm, n);
                pool.submit(() -> run(hs)).get();
                warm.close();
                warm.release();
            }
            Trace t = Tempo.trace(SEARCH, opts().budget(0));
            try (t) {
                Handoff[] first = handoffs(t, 1);
                pool.submit(() -> run(first)).get(); // creates the aggregates of both lanes
                Handoff[] hs = new Handoff[n];
                long submit = allocatedBy(() -> {
                    for (int i = 0; i < n; i++) hs[i] = t.handoff(MATCH);
                });
                assertTrue(submit > 0 && submit % n == 0 && submit / n <= 32, "handoff() allocated " + submit + " B for " + n);
                assertEquals(0, pool.submit(() -> allocatedBy(() -> run(hs))).get(), "bytes the tasks allocated");
            }
            assertEquals(n + 1, t.folded());
            assertEquals(n + 1, t.total(MATCH).count());
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

    // ---- totals ---------------------------------------------------------------------------------------

    @Test
    void totalsCountWhatIsAddedWithoutASpan() throws Exception {
        Trace t = Tempo.trace(SEARCH, opts());
        t.add(MATCH, 5);
        t.add(MATCH, 3);
        t.add(MATCH, 9);
        t.add(MATCH, -4); // clamped
        assertNull(onOtherThread(() -> t.add(MATCH, 11)));
        assertEquals("count=5 sum=28 mean=5 min=0 max=11 waitSum=0 waitMax=0", t.total(MATCH).toString());
        assertEquals("count=0 sum=0 mean=0 min=0 max=0 waitSum=0 waitMax=0", t.total(DECODE).toString());
        assertEquals(0, t.total((Name) null).count());
        assertEquals(0, t.total(MATCH).waitSum());
        clock.add(7);
        t.close();
        assertEquals(lines("search 0+7"), tree(t)); // no span, no lane
        assertEquals(1, t.ctx.lanesById().length);
        t.add(MATCH, 1); // after close: still counted
        assertEquals(6, t.total(MATCH).count());
        t.release();
        t.add(MATCH, 1);
        assertEquals(0, t.total(MATCH).count());
    }

    @Test
    void totalsAreTheSameWhateverTheTraceDraws() {
        List<String> got = new ArrayList<>();
        for (int mode = 0; mode < 3; mode++) {
            Options o = opts().budget(mode == 2 ? 0 : 512);
            Trace t = Tempo.trace(SEARCH, o).records(mode == 0 ? SPAN : STEP);
            for (int i = 1; i <= 3; i++) {
                try (Span s = Tempo.step(MATCH)) {
                    clock.add(i);
                    Tempo.span(LOAD).close();
                }
            }
            t.phase(MATCH); // a phase counts towards its name
            clock.add(4);
            t.done(MATCH);
            t.add(MATCH, 10);
            Tempo.tick(MATCH).close(); // never timed at STEP
            t.close();
            got.add(t.total(MATCH).toString() + " / " + t.total(LOAD).count());
        }
        assertEquals(Collections.nCopies(3, "count=5 sum=20 mean=4 min=1 max=10 waitSum=0 waitMax=0 / 3"), got);
    }

    @Test
    void totalsGrowPastTheirFirstTable() {
        Trace t = Tempo.trace(SEARCH, opts());
        Name[] names = new Name[40];
        for (int i = 0; i < names.length; i++) names[i] = Tempo.name("aggregate-" + i);
        for (int round = 1; round <= 3; round++)
            for (int i = 0; i < names.length; i++) t.add(names[i], i * round);
        for (int i = 0; i < names.length; i++) {
            Tempo.Total a = t.total(names[i]);
            assertEquals(3, a.count());
            assertEquals(6L * i, a.sum());
            assertEquals(2L * i, a.mean());
            assertEquals(i, a.min());
            assertEquals(3L * i, a.max());
        }
        t.close();
    }

    @Test
    void totalsAreExactUnderContention() throws Exception {
        Trace t = Tempo.trace(SEARCH, opts());
        int threads = 8, adds = 20_000;
        Name[] names = {MATCH, LOAD, DECODE};
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int k = 0; k < threads; k++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < adds; i++) t.add(names[i % 3], i % 100);
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        long count = 0, sum = 0;
        for (Name n : names) {
            Tempo.Total a = t.total(n);
            count += a.count();
            sum += a.sum();
            assertEquals(0, a.min());
            assertEquals(99, a.max());
        }
        long perThreadSum = 0;
        for (int i = 0; i < adds; i++) perThreadSum += i % 100;
        assertEquals((long) threads * adds, count);
        assertEquals(threads * perThreadSum, sum);
        assertEquals(1, t.ctx.lanesById().length);
        t.close();
    }

    @Test
    void totalsAllocateNothingOnceWarm() {
        Trace t = Tempo.trace(SEARCH, opts());
        Runnable calls = () -> {
            for (int i = 0; i < 10_000; i++) t.add(MATCH, i);
        };
        calls.run();
        assertEquals(0, allocatedBy(calls));
        t.close();
    }

    // ---- listeners ------------------------------------------------------------------------------------
    // Global listeners are global: each test listens to names of its own.

    @Test
    void globalAndTraceListenersHearUndrawnSteps() {
        List<Long> global = new ArrayList<>(), local = new ArrayList<>();
        Name fetch = Tempo.name("coarse-fetch"), hash = Tempo.name("coarse-hash");
        Tempo.on(fetch, global::add);
        Trace t = Tempo.trace(SEARCH, opts());
        t.on(hash, local::add);
        Span s = t;
        assertEquals(SPAN, t.records());
        Span f = s.step(fetch);
        assertNotSame(NOOP, f);
        clock.add(7);
        assertEquals(7, f.elapsedNanos());
        f.close();
        clock.add(1);
        assertEquals(7, f.elapsedNanos());
        Span h = s.step(hash);
        clock.add(3);
        h.close();
        assertSame(NOOP, s.tick(hash)); // a tick is not timed while the trace records spans only
        t.close();
        assertEquals(List.of(7L), global);
        assertEquals(List.of(3L), local);
        assertEquals(lines("search 0+11"), tree(t)); // undrawn: no rows, no aggregates
        assertEquals("count=1 sum=7 mean=7 min=7 max=7 waitSum=0 waitMax=0", t.total(fetch).toString());
        assertEquals(3, t.total(hash).sum());
        assertEquals(0, t.misuses());
        t.release();
        assertEquals(0, f.elapsedNanos());
    }

    @Test
    void undrawnStepsTimeThemselvesAndIgnoreTheRest() {
        Name parse = Tempo.name("undrawn-parse");
        Trace t = Tempo.trace(SEARCH, opts());
        Span s = t;
        for (long d : new long[] {2, 3, 5}) {
            Span p = s.step(parse);
            clock.add(d);
            p.close();
        }
        Span p = s.step(parse);
        assertSame(p, Tempo.current());
        assertTrue(p.recording());
        p.step(MATCH).close();             // undrawn too, timed
        p.set(BYTES, 1).set(SCORE, 0.5).fail().cancel().waiting().cpu()
                .phase(DECODE).set(BYTES, 2).label("x").done().done(DECODE); // phases: timed, not drawn
        Span inner = p.span(LOAD);         // recorded, under the nearest recorded span
        Span deeper = inner.step(parse);   // undrawn above a recorded span
        assertSame(deeper, Tempo.current());
        clock.add(4);
        deeper.close();
        inner.close();
        clock.add(1);
        p.close();
        t.close();
        assertEquals(lines(
                "search 0+15",
                "  load 10+4"), tree(t));
        assertEquals("count=5 sum=19 mean=3 min=2 max=5 waitSum=0 waitMax=0", t.total(parse).toString());
        assertEquals(1, t.total(MATCH).count());
        assertEquals(2, t.total(DECODE).count());
        assertEquals(0, t.misuses());
    }

    @Test
    void aStepIsDrawnWhenTheTraceRecordsStepsAndAnUnrecordedTickIsNotTimed() {
        Name sort = Tempo.name("drawn-sort");
        List<Long> calls = new ArrayList<>();
        Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
        t.on(sort, calls::add);
        Span s = t;
        Span a = s.step(sort);
        clock.add(2);
        a.close();
        Span b = s.tick(sort);
        clock.add(3);
        b.close();
        t.close();
        assertEquals(lines("search 0+5", "  drawn-sort 0+2 step"), tree(t));
        assertEquals(List.of(2L), calls);
    }

    @Test
    void undrawnStepsOnNoopForeignThreadsAndCaps() throws Exception {
        Name merge = Tempo.name("undrawn-merge");
        assertSame(NOOP, NOOP.step(merge));
        assertSame(NOOP, NOOP.tick(merge));

        Trace t = Tempo.trace(SEARCH, opts());
        Span s = t;
        assertNull(onOtherThread(() -> assertSame(NOOP, s.step(merge))));
        assertEquals(1, t.misuses());
        s.step(merge);
        clock.add(4);
        t.close(); // heals the open undrawn step, which still counts
        assertEquals(2, t.misuses());
        assertEquals(4, t.total(merge).sum());
        assertEquals(lines("search 0+4"), tree(t));

        Trace u = Tempo.trace(SEARCH, opts());
        Span x = u;
        for (int i = 1; i < 35; i++) x = x.span(LOAD);
        assertSame(NOOP, x.step(merge));
        assertEquals(1, u.dropped());
        u.close();
    }

    @Test
    void listenersFireOncePerEndOnTheEndingThread() throws Exception {
        Name span = Tempo.name("l-span"), phase = Tempo.name("l-phase"), lap = Tempo.name("l-lap"),
                task = Tempo.name("l-task"), sum = Tempo.name("l-sum");
        Thread main = Thread.currentThread();
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        Trace t = Tempo.trace(SEARCH, opts());
        for (Name n : new Name[] {SEARCH, span, phase, lap, task, sum}) {
            t.on(n, nanos -> calls.add(n + "=" + nanos
                    + (Thread.currentThread() == main ? "" : "@" + Thread.currentThread().getName())));
        }
        Span s = t;
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
    void listenersCanBeAddedAnyTimeOnAnyThreadAndHearWhatEndsAfter() throws Exception {
        Name late = Tempo.name("late-heard");
        List<Long> calls = Collections.synchronizedList(new ArrayList<>());
        Trace t = Tempo.trace(SEARCH, opts());
        t.on((Name) null, calls::add).on(late, null);
        assertEquals(2, t.misuses());
        t.span(late).close();      // before any listener: unheard
        Span a = t.span(late);
        clock.add(1);
        t.on(late, calls::add);    // while one is open: it hears that one end
        a.close();
        assertNull(onOtherThread(() -> t.on("late-heard", n -> calls.add(-n))));
        Span b = t.step(late);
        clock.add(2);
        b.close();
        t.close();
        assertEquals(List.of(1L, 2L, -2L), calls);
        assertEquals(2, t.misuses());
    }

    @Test
    void aThrowingListenerIsCountedAndTheRecordingStaysConsistent() {
        Name boom = Tempo.name("boom");
        LongConsumer throwing = n -> {
            throw new IllegalStateException("listener");
        };
        Trace t = Tempo.trace(SEARCH, opts());
        t.on(boom, throwing).on(SEARCH, throwing);
        Span b = t.span(boom);
        clock.add(2);
        b.close();
        t.add(boom, 3);
        assertSame(t, Tempo.current());
        t.close();
        assertSame(NOOP, Tempo.current());
        assertEquals(3, t.misuses());
        assertEquals(lines("search 0+2", "  boom 0+2"), tree(t));

        Trace u = Tempo.trace(SEARCH, strict());
        u.on(boom, throwing).on(SEARCH, throwing);
        Span c = u.span(boom);
        AssertionError e = assertThrows(AssertionError.class, c::close);
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertSame(u, Tempo.current());
        assertThrows(AssertionError.class, () -> u.add(boom, 1));
        assertThrows(AssertionError.class, u::close); // thrown once the root has closed and the thread let go
        assertSame(NOOP, Tempo.current());
        assertEquals(lines("search 0+0", "  boom 0+0"), tree(u));
    }

    @Test
    void aThrowingGlobalListenerDoesNotSkipTheListenersAfterIt() {
        List<Long> got = new ArrayList<>();
        Name rerank = Tempo.name("sink-isolated");
        Tempo.on(rerank, n -> {
            throw new IllegalStateException("first");
        });
        Tempo.on(rerank, got::add);
        Tempo.on("sink-isolated", n -> {
            throw new IllegalStateException("third");
        });
        Tempo.on(rerank, n -> got.add(-n));
        Tempo.on(rerank, null); // ignored
        Trace t = Tempo.trace(SEARCH, opts());
        t.add(rerank, 5);
        t.close();
        assertEquals(List.of(5L, -5L), got);
        assertEquals(2, t.misuses());

        Trace u = Tempo.trace(SEARCH, strict());
        AssertionError e = assertThrows(AssertionError.class, () -> u.add(rerank, 7));
        assertEquals("first", e.getCause().getMessage());
        assertEquals(List.of(5L, -5L, 7L, -7L), got);
        u.close();
    }

    @Test
    void aGlobalListenerHearsEveryThreadWithoutALock() throws Exception {
        AtomicLong count = new AtomicLong(), total = new AtomicLong();
        Name n = Tempo.name("threads-heard");
        Tempo.on(n, v -> {
            count.incrementAndGet();
            total.addAndGet(v);
        });
        Trace t = Tempo.trace(SEARCH, opts());
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
        assertEquals((long) threads * adds, count.get());
        assertEquals(threads * perThread, total.get());
        t.close();
    }

    @Test
    void runAndCallTimeABodyOutsideATraceForAGlobalListener() {
        List<Long> got = new ArrayList<>();
        Name timed = Tempo.name("timed-body");
        Tempo.on(timed, got::add);
        assertEquals(42, (int) Tempo.call(timed, () -> 42));
        Tempo.run(timed, () -> {
            got.size();
        });
        IOException e = assertThrows(IOException.class, () -> Tempo.run(timed, () -> {
            throw new IOException("unwrapped");
        }));
        assertEquals("unwrapped", e.getMessage());
        assertEquals(3, got.size());
        for (long v : got) assertTrue(v >= 0);
        assertEquals("x", Tempo.call(Tempo.name("timed-unheard"), () -> "x"));
        assertSame(NOOP, Tempo.current());
    }

    @Test
    void aThrowingGlobalListenerOutsideATraceNeverReachesTheCaller() {
        Name bad = Tempo.name("timed-throwing");
        Tempo.on(bad, nanos -> {
            throw new IllegalStateException("listener");
        });
        IOException e = assertThrows(IOException.class, () -> Tempo.run(bad, () -> {
            throw new IOException("body");
        }));
        assertEquals("body", e.getMessage());
        assertEquals(7, (int) Tempo.call(bad, () -> 7));
        int[] ran = {0};
        Tempo.run(bad, () -> {
            ran[0]++;
        });
        assertEquals(1, ran[0]);
        assertSame(NOOP, Tempo.current());
    }

    @Test
    void spansOutsideATraceAllocateNothingOnceWarm() {
        Name heard = Tempo.name("outside-alloc");
        long[] sum = {0};
        Tempo.on(heard, n -> sum[0]++);
        Runnable calls = () -> {
            for (int i = 0; i < 10_000; i++) {
                Tempo.span(heard).close();
                Tempo.step(heard).close();
                Tempo.span(LOAD).close();
                Tempo.tick(heard).close();
            }
        };
        for (int i = 0; i < 3; i++) calls.run();
        assertEquals(0, allocatedBy(calls));
        assertEquals(80_000, sum[0]);
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
        Trace t = Tempo.trace(SEARCH, opts().cpuClock(cpu)).records(STEP);
        Span s = t;
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
                opts().cpuClock(cpu),                // SPAN: never, not even on request
                opts().cpuClock(cpu).cpu(true),      // forced on at SPAN
                opts().cpuClock(cpu).cpu(false),     // forced off at STEP
                opts().cpuClock(cpu),                // widened to STEP: the trace's own from then on
                opts().cpuClock(() -> -1),           // a clock that reads -1: not measured
        };
        int[] records = {SPAN, SPAN, STEP, STEP, STEP};
        List<String> got = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            Trace t = Tempo.trace(SEARCH, options[i]);
            cpu.add(1); // before the widening: not the trace's own
            t.records(records[i]);
            Span x = t.span(LOAD).cpu();
            cpu.add(4);
            x.close();
            t.close();
            got.add(cpus(t));
        }
        assertEquals(List.of("search=-1 load=-1", "search=5 load=4", "search=-1 load=-1",
                "search=4 load=4", "search=-1 load=-1"), got);
    }

    @Test
    void realCpuTimeIsReadFromTheThread() {
        assumeTrue(Tempo.Cpu.now() >= 0, "thread CPU time is not supported here");
        Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
        Span s = t.span(LOAD).cpu();
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
    void undrawnStepsAndListenersAllocateNothingOnceWarm() {
        Name agg = Tempo.name("alloc-agg"), heard = Tempo.name("alloc-heard");
        long[] sum = {0};
        Trace t = Tempo.trace(SEARCH, opts());
        t.on(heard, n -> sum[0] += n);
        Span s = t;
        Runnable calls = () -> {
            for (int i = 0; i < 10_000; i++) {
                s.step(agg).close();
                Span h = s.step(heard);
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
        Trace t = Tempo.trace(SEARCH, opts());
        t.span(DECODE).close();
        clock.add(5);
        t.close();
        assertEquals(5, t.elapsedNanos());
        t.release();
        t.release();
        assertEquals(0, t.elapsedNanos());
        assertEquals(0, t.elapsedNanos());
        assertEquals("", tree(t));
        assertSame(NOOP, t.span(LOAD)); // use after close
    }

    @Test
    void releaseBeforeCloseRetiresTheLaneAtClose() {
        Trace t = Tempo.trace(SEARCH, opts());
        Span load = t.span(LOAD);
        clock.add(50);
        t.release();
        assertSame(load, Tempo.current());
        assertEquals(0, load.elapsedNanos()); // reads are empty from release on, not only once retired
        assertEquals(0, t.elapsedNanos());
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
        for (int round = 0; round < 3; round++) { // fills the chunk and handle pools, loads every class on the path
            Trace warm = Tempo.trace(SEARCH, opts().budget(Integer.MAX_VALUE)).records(STEP);
            record(warm, spans + 1);
            warm.close();
            warm.release();
        }

        Trace t = Tempo.trace(SEARCH, opts().budget(Integer.MAX_VALUE)).records(STEP);
        record(t, 1); // creates the event table
        assertEquals(0, allocatedBy(() -> record(t, spans)), "bytes allocated by " + spans + " spans");
        t.close();
        t.release();
    }

    @Test
    void unrecordedTicksAndUndrawnStepsAllocateNothing() {
        for (int records : new int[] {SPAN, STEP}) {
            Trace t = Tempo.trace(SEARCH, opts()).records(records);
            Span c = t;
            Runnable calls = () -> {
                for (int i = 0; i < 8_000; i++) {
                    if (records == SPAN) c.step(MATCH).close();
                    c.tick(MATCH).close();
                    Tempo.tick(MATCH).close();
                    NOOP.span(LOAD).set(BYTES, i).fail().close();
                }
            };
            calls.run();
            assertEquals(0, allocatedBy(calls));
            t.close();
            assertEquals(0, t.misuses());
            assertEquals(lines("search 0+0"), tree(t));
        }
    }

    @Test
    void traceSetupStaysUnderItsCeiling() {
        Options o = opts();
        Runnable lifecycle = () -> {
            Trace t = Tempo.trace(SEARCH, o);
            t.span(LOAD).close();
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
            root.step(MATCH).close();
        }
    }
}
