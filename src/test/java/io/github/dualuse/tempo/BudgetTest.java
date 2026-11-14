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
        for (int i = 0; i < lanes; i++) {
            int before = 128 - i;
            Handoff h = s.handoff(MATCH);
            assertNull(onThread("worker-" + i, () -> {
                try (Span w = h.begin()) {
                    w.detail(LOAD).close();
                    assertEquals(before - 2, t.ctx.unspent()); // the lane holds the rest of its block while open
                }
            }));
        }
        assertEquals(128 - lanes, t.ctx.unspent()); // one credit used per lane, the rest returned when idle
        for (int i = 0; i < 200; i++) s.detail(LOAD).close();
        t.close();
        int[] rows = {0}, folded = {0};
        t.forEachSpan(v -> {
            if (v.aggregate()) folded[0] += v.count();
            else if (v.level() == DETAIL) rows[0]++;
        });
        assertEquals(128, rows[0]);
        assertEquals(200 + lanes - 128, folded[0]);
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
            assertEquals(budget, rows[0] + t.ctx.unspent()); // every lane is idle: nothing is held
            assertTrue(rows[0] >= budget - threads * (block - 1), "recorded " + rows[0]);
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
        Trace t = Tempo.start(SEARCH, opts(1));
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
}
