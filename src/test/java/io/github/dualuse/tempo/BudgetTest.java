package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.Handoff;
import io.github.dualuse.tempo.Tempo.Name;
import io.github.dualuse.tempo.Tempo.Options;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.github.dualuse.tempo.Tempo.DETAIL;
import static io.github.dualuse.tempo.Tempo.NOOP;
import static io.github.dualuse.tempo.TempoTest.allocatedBy;
import static io.github.dualuse.tempo.TempoTest.lines;
import static io.github.dualuse.tempo.TempoTest.onThread;
import static io.github.dualuse.tempo.TempoTest.tree;
import static org.junit.jupiter.api.Assertions.*;

/** The detail budget and the lane aggregates that take what it leaves out. */
class BudgetTest {

    static final Name SEARCH = TempoTest.SEARCH, DECODE = TempoTest.DECODE, RERANK = TempoTest.RERANK,
            LOAD = TempoTest.LOAD, MATCH = TempoTest.MATCH;

    final TempoTest.Clock clock = new TempoTest.Clock();

    Options opts(int budget) {
        return new Options().clock(clock).level(DETAIL).budget(budget);
    }

    @AfterEach
    void noTraceLeftOnThread() {
        assertSame(NOOP, Tempo.current(), "a test left a span open on the test thread");
    }

    /** Opens and closes a detail span {@code name} under {@code s} for each duration, one tick apart. */
    private void spans(Span s, Name name, long... durations) {
        for (long d : durations) {
            Span x = s.detail(name);
            clock.add(d);
            x.close();
            clock.add(1);
        }
    }

    @Test
    void detailSpansPastTheBudgetFoldIntoOneAggregatePerAnchor() {
        Trace t = Tempo.start(SEARCH, opts(3));
        spans(t.root(), LOAD, 4, 1, 6, 2, 9, 3, 5);
        t.root().span(DECODE).close(); // coarse spans are not budgeted
        t.close();
        assertEquals(lines(
                "search 0+37",
                "  load 0+4 detail",
                "  load 5+1 detail",
                "  load 7+6 detail",
                "  load 14+19 agg*4 min=2 max=9 maxAt=3 extent=22", // count, min and max leave the rows out
                "  decode 37+0"), tree(t));
        assertEquals(4, t.folded());
        assertEquals(0, t.ctx.unspent());
        assertEquals("count=7 sum=30 min=1 max=9 waits=0 waitSum=0 waitMax=0", t.aggregate(LOAD).toString());
        assertEquals(0, t.misuses() + t.dropped());
    }

    @Test
    void foldedSpansNestAndTheirCoarseChildrenAreRecorded() {
        Trace t = Tempo.start(SEARCH, opts(0));
        Span s = t.root();
        for (int i = 0; i < 2; i++) {
            Span m = s.detail(MATCH);
            spans(m, LOAD, 2 + i);
            Span d = m.span(DECODE); // recorded, under the nearest recorded span
            clock.add(3);
            d.close();
            m.close();
        }
        t.close();
        assertEquals(lines(
                "search 0+13",
                "  match 0+13 agg*2 min=6 max=7 maxAt=6 extent=13",
                "    load 0+5 agg*2 min=2 max=3 maxAt=6 extent=9",
                "  decode 3+3",
                "  decode 10+3"), tree(t));
        assertEquals(4, t.folded());
        assertEquals(0, t.misuses() + t.dropped());
    }

    @Test
    void foldedSpansOfConsecutiveLapsLandUnderTheirOwnLap() {
        Trace t = Tempo.start(SEARCH, opts(1));
        Span s = t.root();
        spans(s, LOAD, 0, 1);       // the first is recorded, the second folds
        s.done(DECODE);             // a label-after lap: both move under it
        spans(s, LOAD, 2, 0, 1);
        s.done(RERANK);
        spans(s, LOAD, 3);          // after the last lap: the owner's
        s.phase(MATCH);             // label first: folded spans nest under the open phase
        spans(s, LOAD, 4);
        s.done(MATCH);
        t.close();
        assertEquals(lines(
                "search 0+18",
                "  decode 0+3 lap",
                "    load 0+0 detail",
                "    load 1+1 agg*1 min=1 max=1 maxAt=0 extent=1",
                "  rerank 3+6 lap",
                "    load 3+3 agg*3 min=0 max=2 maxAt=0 extent=5",
                "  load 9+3 agg*1 min=3 max=3 maxAt=0 extent=3",
                "  match 13+5 phase",
                "    load 13+4 agg*1 min=4 max=4 maxAt=0 extent=4"), tree(t));
        assertEquals(6, t.folded());
        assertEquals("count=7 sum=11 min=0 max=4 waits=0 waitSum=0 waitMax=0", t.aggregate(LOAD).toString());
        assertEquals(0, t.misuses() + t.dropped());
    }

    @Test
    void idleLanesGiveTheirUnspentCreditsBack() throws Exception {
        Trace t = Tempo.start(SEARCH, opts(128)); // blocks of 2
        Span s = t.root();
        int lanes = 10;
        Handoff[] hs = new Handoff[lanes];
        for (int i = 0; i < lanes; i++) hs[i] = s.handoff(MATCH);
        assertEquals(128 - lanes, t.ctx.unspent()); // a handoff counts against the budget too
        for (int i = 0; i < lanes; i++) {
            int before = 128 - lanes - i;
            Handoff h = hs[i];
            assertNull(onThread("worker-" + i, () -> {
                try (Span w = h.begin()) {
                    w.detail(LOAD).close();
                    assertEquals(before - 2, t.ctx.unspent()); // the lane holds the rest of its block while open
                }
            }));
        }
        assertEquals(128 - 2 * lanes, t.ctx.unspent()); // one credit used per lane, the rest returned when idle
        for (int i = 0; i < 200; i++) s.detail(LOAD).close();
        t.close();
        int[] rows = {0}, folded = {0};
        t.forEachSpan(v -> {
            if (v.aggregate()) folded[0] += v.count();
            else if (v.level() == DETAIL) rows[0]++;
        });
        assertEquals(128 - lanes, rows[0]);
        assertEquals(200 + 2 * lanes - 128, folded[0]);
        assertEquals(folded[0], t.folded());
        assertEquals(0, t.ctx.unspent());
    }

    /** Lanes refill their blocks concurrently; every span is recorded or folded, and the budget is conserved. */
    @Test
    void contendedBudgetIsSpentExactlyOnce() throws Exception {
        int threads = 8, tasks = 200, perTask = 5, budget = 1000, block = budget / 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Trace t = Tempo.start(SEARCH, new Options().level(DETAIL).budget(budget));
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                Runnable task = t.root().handoff(MATCH).runnable(() -> {
                    for (int k = 0; k < perTask; k++) Tempo.current().detail(LOAD).close();
                });
                fs.add(pool.submit(() -> {
                    go.await();
                    task.run();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get();
            t.close();
            int[] rows = {0};
            long[] folded = {0};
            t.forEachSpan(v -> {
                if (v.aggregate()) folded[0] += v.count();
                else if (v.name() == LOAD) rows[0]++;
            });
            assertEquals(tasks * perTask, rows[0] + folded[0]);
            assertEquals(t.folded(), folded[0]);
            assertEquals(budget, tasks + rows[0] + t.ctx.unspent()); // every lane is idle: nothing is held
            // the handoffs took theirs first; the workers and the request thread each hold less than a block
            assertTrue(rows[0] >= budget - tasks - (threads + 1) * (block - 1), "recorded " + rows[0]);
            assertEquals(tasks * perTask, t.aggregate(LOAD).count());
            assertEquals(0, t.misuses() + t.dropped());
            t.release();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void foldedSpansFireListenersAndSumTheirCpuTime() {
        TempoTest.Clock cpu = new TempoTest.Clock();
        List<Long> heard = new ArrayList<>();
        Trace t = Tempo.start(SEARCH, opts(0).cpuClock(cpu));
        t.on(LOAD, heard::add);
        Span s = t.root();
        for (long d : new long[] {3, 4}) {
            Span x = s.detail(LOAD).cpu();
            cpu.add(d - 1);
            clock.add(d);
            x.close();
        }
        s.detail(LOAD).close(); // not measured: adds nothing to the CPU time
        t.add(LOAD, 10);         // lane-less, merged into the aggregate
        t.close();
        assertEquals(List.of(3L, 4L, 0L, 10L), heard);
        long[] got = {-2};
        t.forEachSpan(v -> {
            if (v.aggregate()) got[0] = v.cpuNanos();
        });
        assertEquals(5, got[0]);
        assertEquals("count=4 sum=17 min=0 max=10 waits=0 waitSum=0 waitMax=0", t.aggregate(LOAD).toString());
    }

    /** Each coarse span is a new anchor, so its folded child needs a new aggregate until the lane has its maximum. */
    @Test
    void detailSpansPastTheAggregateCapAreDroppedNotFolded() {
        Trace t = Tempo.start(SEARCH, opts(0));
        Span s = t.root();
        int opened = Tempo.Aggs.MAX + 904;
        for (int i = 0; i < opened; i++) {
            Span m = s.span(MATCH);
            Span x = m.detail(LOAD);
            clock.add(1);
            x.close();
            m.close();
        }
        t.close();
        int[] rows = {0};
        long[] folded = {0};
        t.forEachSpan(v -> {
            if (v.name() != LOAD) return;
            if (v.aggregate()) folded[0] += v.count();
            else rows[0]++;
        });
        assertEquals(0, rows[0]);
        assertEquals(Tempo.Aggs.MAX, folded[0]);
        assertEquals(Tempo.Aggs.MAX, t.folded());
        assertEquals(904, t.dropped());
        assertEquals(opened, rows[0] + t.folded() + t.dropped());
        assertEquals("count=4096 sum=4096 min=1 max=1 waits=0 waitSum=0 waitMax=0", t.aggregate(LOAD).toString());
        assertEquals(0, t.misuses());
    }

    @Test
    void traceAggregateSumsInPlaceWhatTheSnapshotSums() {
        Trace t = Tempo.start(SEARCH, opts(3)); // the two handoffs and one span
        Span s = t.root();
        Span a = s.span(MATCH);
        clock.add(3);
        a.close();
        Span c = s.span(MATCH).cancel(); // cancelled, but not a task: it counts
        clock.add(2);
        c.close();
        Handoff h = s.handoff(MATCH);
        clock.add(4);
        Span b = h.begin();               // inline task: waited 4, ran 5
        clock.add(5);
        b.close();
        Handoff g = s.handoff(MATCH);
        clock.add(6);
        g.cancel();                       // a cancelled task: its wait counts, its row does not
        spans(s, MATCH, 1, 7);            // recorded, then folded
        t.add(MATCH, 9);
        t.addWait(MATCH, 8);
        Span open = s.span(MATCH);        // open: left out
        clock.add(20);
        String expected = "count=6 sum=27 min=1 max=9 waits=3 waitSum=18 waitMax=8";
        assertEquals(expected, t.aggregate(MATCH).toString());
        assertEquals(expected, t.snapshot().aggregate(MATCH).toString());
        open.close();
        t.close();
        assertEquals("count=7 sum=47 min=1 max=20 waits=3 waitSum=18 waitMax=8", t.aggregate(MATCH).toString());
        assertEquals(0, t.misuses() + t.dropped());
        t.release();
        assertEquals("count=0 sum=0 min=0 max=0 waits=0 waitSum=0 waitMax=0", t.aggregate(MATCH).toString());
    }

    /** Trace.aggregate reads the lanes in place: one result object per call, however large the trace. */
    @Test
    void traceAggregateCopiesNothing() {
        Trace t = Tempo.start(SEARCH, opts(100));
        Span s = t.root();
        for (int i = 0; i < 5_000; i++) {
            s.span(MATCH).set(DECODE, i).close();
            spans(s, LOAD, 1);
        }
        t.add(LOAD, 1);
        t.close();
        int calls = 1_000;
        Runnable reads = () -> {
            for (int i = 0; i < calls; i++) t.aggregate(LOAD);
        };
        for (int i = 0; i < 3; i++) reads.run();
        long bytes = allocatedBy(reads);
        assertTrue(bytes <= 72L * calls, bytes + " B for " + calls + " calls");
        assertEquals("count=5001 sum=5001 min=1 max=1 waits=0 waitSum=0 waitMax=0", t.aggregate(LOAD).toString());
        t.release();
    }

    @Test
    void foldedSpansAllocateNothingOnceWarm() {
        Trace t = Tempo.start(SEARCH, opts(0));
        Span s = t.root();
        Runnable folds = () -> {
            for (int i = 0; i < 10_000; i++) {
                Span m = s.detail(MATCH);
                m.detail(LOAD).close();
                m.close();
            }
        };
        for (int i = 0; i < 3; i++) folds.run();
        assertEquals(0, allocatedBy(folds));
        t.close();
        assertEquals(80_000, t.folded());
        assertEquals(40_000, t.aggregate(LOAD).count());
    }

    // ---- handoffs past the budget -------------------------------------------------------------------

    /**
     * Past the budget a handoff's task folds wherever it runs: on two workers or the submitting thread
     * (caller-runs), the occurrences meet in one aggregate under the submitting span with their queue
     * waits, mixed threads ({@code @_}) and the flags they all have; on one worker, its thread.
     */
    @Test
    void foldedHandoffsKeepTheirWaitsThreadsAndSharedFlags() throws Exception {
        Trace t = Tempo.start(SEARCH, opts(1));
        Span s = t.root();
        Handoff first = s.handoff(MATCH); // the budget
        Handoff[] load = new Handoff[3];
        for (int i = 0; i < 3; i++) {
            clock.add(10);
            load[i] = s.handoff(LOAD);    // submitted at 10, 20, 30
        }
        clock.add(10);
        assertNull(onThread("w1", () -> {
            clock.add(5);
            Span x = load[0].begin();     // waited 35
            clock.add(15);
            x.fail().close();
        }));
        assertNull(onThread("w2", () -> {
            Span x = load[1].begin();     // waited 40
            clock.add(5);
            x.fail().close();
        }));
        Span x = load[2].begin();         // caller-runs, waited 35
        clock.add(10);
        x.fail().close();
        Span rerank = s.span(RERANK);
        Handoff m1 = rerank.handoff(MATCH), m2 = rerank.handoff(MATCH);
        clock.add(2);
        assertNull(onThread("w1", () -> {
            m1.begin().fail().close();    // waited 2
            clock.add(3);
            Span y = m2.begin();          // waited 5
            clock.add(4);
            y.close();
        }));
        rerank.close();
        assertNull(onThread("w1", () -> first.runnable(() -> clock.add(1)).run()));
        t.close();
        assertEquals(lines(
                "search 0+85",
                "  match 0+1 handoff~84 @w1",
                "  load 10+30 failed agg*3 min=5 max=15 maxAt=0 extent=65 handoff~110/40 @_",
                "  rerank 75+9",
                "    match 75+4 agg*2 min=0 max=4 maxAt=0 extent=9 handoff~7/5 @w1"), tree(t));
        assertEquals("count=3 sum=30 min=5 max=15 waits=3 waitSum=110 waitMax=40", t.aggregate(LOAD).toString());
        assertEquals(t.aggregate(LOAD).toString(), t.snapshot().aggregate(LOAD).toString());
        assertEquals(5, t.folded());
        assertEquals(0, t.misuses() + t.dropped());
    }

    /**
     * Folded handoffs that never ran sit on the submitting thread with no duration: all cancelled or
     * rejected ({@code ^c}, waits until then), all never started ({@code ^n}, waits until the render),
     * or some of each (no flag in common). Only their waits reach {@link Trace#aggregate}, as for rows.
     */
    @Test
    void foldedHandoffsThatNeverRanAreCancelledOrNeverStarted() throws Exception {
        Trace t = Tempo.start(SEARCH, opts(0).cpu(false));
        Span rerank = t.root().span(RERANK);
        clock.add(10_000);
        Handoff a = rerank.handoff(LOAD);
        clock.add(10_000);
        Handoff b = rerank.handoff(LOAD);
        clock.add(10_000);
        a.cancel();                                    // waited 20
        clock.add(20_000);
        assertNull(onThread("canceller", b::reject));  // waited 30
        clock.add(10_000);
        rerank.handoff(MATCH);                         // never started: 60 and 70
        clock.add(10_000);
        rerank.handoff(MATCH);
        clock.add(5_000);
        rerank.handoff(DECODE);                        // never started, the first of its aggregate: exact
        clock.add(5_000);
        Handoff e = rerank.handoff(DECODE);
        clock.add(5_000);
        e.cancel();                                    // waited 5
        clock.add(5_000);
        rerank.close();
        clock.add(10_000);
        t.close();
        assertEquals(lines(
                "search 0+100000",
                "  rerank 0+90000",
                "    load 10000+0 cancelled agg*2 min=0 max=0 maxAt=0 extent=40000 handoff~50000/30000",
                "    match 60000+0 agg*2 min=0 max=0 maxAt=0 extent=40000 handoff~70000/40000 unstarted",
                "    decode 75000+0 agg*2 min=0 max=0 maxAt=5000 extent=25000 handoff~30000/25000"), tree(t));
        String h = t.toHeader();
        assertTrue(h.endsWith(";x=6.0;T=" + HeaderTest.name(Thread.currentThread().getName()) + ";N=search:rerank:load:match:decode;"
                + "S=00.0.2s/11.0.2i/22.a.0~1e.u+2.0.0.14.0^c/23.1e.0~1y.14+2.0.0.14.0^n/24.f.0~u.p+2.0.0.p.5;e=5"), h);
        assertEquals("count=0 sum=0 min=0 max=0 waits=2 waitSum=50000 waitMax=30000", t.aggregate(LOAD).toString());
        assertEquals("count=0 sum=0 min=0 max=0 waits=0 waitSum=0 waitMax=0", t.aggregate(MATCH).toString());
        assertEquals("count=0 sum=0 min=0 max=0 waits=1 waitSum=5000 waitMax=5000", t.snapshot().aggregate(DECODE).toString());
        assertEquals(6, t.folded());
        assertEquals(0, t.misuses() + t.dropped());
    }

    /**
     * A folded task begun on a thread outside the trace has no recorded span to hang its spans from: they
     * are aggregate-only, its handoffs fold, and their aggregates are top-level nodes of the lane, which
     * the header leaves out as dropped.
     */
    @Test
    void spansInAFoldedTaskOutsideTheTraceAreAggregateOnly() throws Exception {
        Trace t = Tempo.start(SEARCH, opts(0).cpu(false));
        Handoff h = t.root().handoff(MATCH);
        Handoff[] g = new Handoff[1];
        assertNull(onThread("w1", () -> {
            clock.add(10_000);
            try (Span x = h.begin()) {
                Span d = x.span(DECODE);
                assertEquals(Tempo.Lane.FRAME, d.row);
                clock.add(5_000);
                d.close();
                Span l = x.detail(LOAD);
                clock.add(3_000);
                l.close();
                g[0] = x.handoff(LOAD);
            }
        }));
        assertNull(onThread("w2", () -> {
            clock.add(2_000);
            Span y = g[0].begin();
            clock.add(4_000);
            y.close();
        }));
        clock.add(6_000);
        t.close();
        assertEquals(lines(
                "search 0+30000",
                "  match 0+8000 agg*1 min=8000 max=8000 maxAt=0 extent=18000 handoff~10000/10000 @w1",
                "decode 10000+5000 agg*1 min=5000 max=5000 maxAt=0 extent=5000 @w1",
                "load 15000+3000 agg*1 min=3000 max=3000 maxAt=0 extent=3000 @w1",
                "load 18000+4000 agg*1 min=4000 max=4000 maxAt=0 extent=6000 handoff~2000/2000 @w2"), tree(t));
        String v = t.toHeader();
        assertTrue(v.contains(";x=1.3;") && v.endsWith(":w1;N=search:match;S=00.0.u/11.0.8@1~a.a+1.8.8.i.0;e=2"), v);
        assertEquals(1, t.aggregate(DECODE).count());
        assertEquals(2, t.folded());
        assertEquals(0, t.misuses() + t.dropped());
    }

    /**
     * Folded handoffs of a label-after lap move under it with their aggregate; the occurrence run inline
     * on the submitting thread, kept in that lane by the index of its aggregate, stays with it.
     */
    @Test
    void foldedHandoffsOfALapLandUnderTheLap() throws Exception {
        Trace t = Tempo.start(SEARCH, opts(0));
        Span s = t.root();
        Handoff h1 = s.handoff(LOAD);
        clock.add(5);
        Span x = h1.begin(); // caller-runs
        clock.add(5);
        x.close();
        Handoff h2 = s.handoff(LOAD);
        assertNull(onThread("w1", () -> {
            clock.add(2);
            Span y = h2.begin();
            clock.add(8);
            y.close();
        }));
        s.done(DECODE);
        Handoff h3 = s.handoff(LOAD);
        assertNull(onThread("w1", () -> {
            clock.add(1);
            h3.begin().close();
        }));
        clock.add(1);
        t.close();
        assertEquals(lines(
                "search 0+22",
                "  decode 0+20 lap",
                "    load 0+13 agg*2 min=5 max=8 maxAt=10 extent=20 handoff~7/5 @_",
                "  load 20+0 agg*1 min=0 max=0 maxAt=0 extent=1 handoff~1/1 @w1"), tree(t));
        assertEquals("count=3 sum=13 min=0 max=8 waits=3 waitSum=8 waitMax=5", t.aggregate(LOAD).toString());
        assertEquals(0, t.misuses() + t.dropped());
    }

    /** Pool threads run handoffs past the budget while a render loops: each task is counted once, nothing is lost. */
    @Test
    void contendedFoldedHandoffsAreCountedOnce() throws Exception {
        int threads = 8, tasks = 2000, budget = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Trace t = Tempo.start(SEARCH, new Options().budget(budget));
            java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
            Thread renderer = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        TempoTest.assertWellFormed(t);
                        WireDecoder.decode(t.toHeader());
                    }
                } catch (Throwable e) {
                    thrown.set(e);
                }
            }, "renderer");
            renderer.start();
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                Runnable task = t.root().handoff(MATCH).runnable(() -> Tempo.current().span(LOAD).close());
                fs.add(pool.submit(task));
            }
            for (Future<?> f : fs) f.get();
            stop.set(true);
            renderer.join();
            t.close();
            if (thrown.get() != null) throw new AssertionError(thrown.get());
            long[] handoffs = {0, 0}; // recorded, folded
            t.forEachSpan(v -> {
                if (v.handoff()) handoffs[v.aggregate() ? 1 : 0] += v.count();
            });
            assertEquals(budget, handoffs[0]);
            assertEquals(tasks - budget, handoffs[1]);
            assertEquals(tasks - budget, t.folded());
            assertEquals(tasks, t.aggregate(MATCH).count());
            assertEquals(tasks, t.aggregate(MATCH).waitCount());
            assertEquals(tasks, t.aggregate(LOAD).count()); // aggregate-only inside the folded tasks
            assertEquals((long) tasks - budget, WireDecoder.decode(t.toHeader()).get("folded"));
            assertEquals(0, t.misuses());
            t.release();
        } finally {
            pool.shutdown();
        }
    }

    /** A folded task past the lane cap is dropped, not left as never started; its wait still counts. */
    @Test
    void foldedTasksPastTheLaneCapAreDropped() throws Exception {
        Trace t = Tempo.start(SEARCH, opts(0).lanes(2).cpu(false));
        Span root = t.root();
        Handoff a = root.handoff(MATCH), b = root.handoff(MATCH), c = root.handoff(MATCH);
        clock.add(4_000);
        assertNull(onThread("w1", () -> a.begin().close()));
        clock.add(3_000);
        assertNull(onThread("w2", () -> assertSame(NOOP, b.begin())));
        c.begin().close(); // caller-runs
        t.close();
        assertEquals(lines(
                "search 0+7000",
                "  match 0+0 agg*2 min=0 max=0 maxAt=0 extent=7000 handoff~11000/7000 @_"), tree(t));
        assertEquals(1, t.dropped());
        assertEquals("count=2 sum=0 min=0 max=0 waits=3 waitSum=18000 waitMax=7000", t.aggregate(MATCH).toString());
        assertTrue(t.toHeader().contains(";x=2.1;"), t.toHeader());
        assertEquals(0, t.misuses());
    }
}
