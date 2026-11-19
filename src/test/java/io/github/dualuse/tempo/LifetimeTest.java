package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.Handoff;
import io.github.dualuse.tempo.Tempo.Name;
import io.github.dualuse.tempo.Tempo.Options;
import io.github.dualuse.tempo.Tempo.Snapshot;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static io.github.dualuse.tempo.Tempo.STEP;
import static io.github.dualuse.tempo.Tempo.NOOP;
import static io.github.dualuse.tempo.TempoTest.lines;
import static io.github.dualuse.tempo.TempoTest.onThread;
import static io.github.dualuse.tempo.TempoTest.tree;
import static org.junit.jupiter.api.Assertions.*;

/** Release, snapshots, the chunk pool, stragglers and the lane state under concurrency. */
class LifetimeTest {

    static final Name SEARCH = TempoTest.SEARCH, DECODE = TempoTest.DECODE, LOAD = TempoTest.LOAD,
            MATCH = TempoTest.MATCH, BYTES = TempoTest.BYTES, SCORE = TempoTest.SCORE, STAMP = Tempo.name("stamp");

    final TempoTest.Clock clock = new TempoTest.Clock();

    Options opts() {
        return new Options().clock(clock);
    }

    @AfterEach
    void noTraceLeftOnThread() {
        TempoTest.assertNothingLeftOpen();
    }

    // ---- release and snapshots ---------------------------------------------------------------------

    @Test
    void aSnapshotStaysCompleteAfterReleaseAndTheReuseOfItsChunks() throws Exception {
        Trace t = Tempo.trace(SEARCH, opts().budget(1)).records(STEP);
        Span s = t.set(BYTES, 7);
        s.phase(DECODE).label("Decode #1");
        s.step(LOAD).close();
        clock.add(2);
        s.step(LOAD).close(); // folds
        s.done();
        Handoff h = s.handoff(MATCH).set(SCORE, 3);
        clock.add(1);
        assertNull(onThread("worker-1", () -> {
            try (Span w = h.begin()) {
                w.set(BYTES, 9);
                clock.add(4);
            }
        }));
        Span open = s.span(LOAD);
        Snapshot early = t.snapshot(); // the open span is clamped to this instant
        clock.add(5);
        open.close();
        t.close();
        Snapshot snap = t.snapshot();
        String expected = lines(
                "search 0+12 bytes=7",
                "  decode 0+2 phase 'Decode #1'",
                "  load 0+0 step",
                "  load 2+0 agg*1 min=0 max=0 maxAt=0 extent=0",
                "  match 2+4 agg*1 min=4 max=4 maxAt=0 extent=5 handoff~1/1 @worker-1", // folded: past the budget too
                "  load 7+5");
        assertEquals(expected, tree(snap));
        assertEquals(lines(
                "search 0+7 open bytes=7",
                "  decode 0+2 phase 'Decode #1'",
                "  load 0+0 step",
                "  load 2+0 agg*1 min=0 max=0 maxAt=0 extent=0",
                "  match 2+4 agg*1 min=4 max=4 maxAt=0 extent=5 handoff~1/1 @worker-1",
                "  load 7+0 open"), tree(early));
        t.release();
        t.release();
        assertEquals("", tree(t));
        assertEquals("", tree(t.snapshot()));
        assertEquals(0, t.total(LOAD).count());
        assertEquals(0, t.elapsedNanos());

        Trace other = Tempo.trace(MATCH, opts()).records(STEP); // takes the released chunks and overwrites them
        for (int i = 0; i < 3_000; i++) other.span(DECODE).set(SCORE, -1).close();
        other.close();
        assertEquals(expected, tree(snap));
        assertEquals(12, snap.elapsedNanos());
        assertEquals("count=3 sum=5 mean=1 min=0 max=5 waitSum=0 waitMax=0", snap.total(LOAD).toString());
        assertEquals(2, snap.folded());
        other.release();
    }

    @Test
    void releaseReturnsEveryChunkAndKeepsOnlyLaneShells() throws Exception {
        Trace t = Tempo.trace(SEARCH, opts().budget(Integer.MAX_VALUE)).records(STEP);
        for (int i = 0; i < 2_000; i++) t.step(LOAD).set(BYTES, i).close();
        Handoff h = t.handoff(MATCH);
        assertNull(onThread("worker-1", () -> {
            try (Span w = h.begin()) {
                for (int i = 0; i < 1_000; i++) w.span(LOAD).set(BYTES, i).close();
            }
        }));
        t.close();
        Retained before = Retained.of(t);
        assertEquals(12, before.count(Tempo.Rows.class));
        t.release();
        Retained after = Retained.of(t);
        for (Tempo.Lane l : t.ctx.lanesById()) assertTrue(l.retired());
        assertEquals(0, after.count(Tempo.Rows.class), after.toString());
        assertEquals(0, after.count(Tempo.Events.class), after.toString());
        assertTrue(after.bytes <= 16 * 1024, "released trace of two lanes: " + after);
        assertTrue(before.bytes > 100 * 1024, "before release: " + before);
    }

    /**
     * Pool threads serve 1,000 traces, half of them released, half left to the GC; afterwards no idle
     * thread holds more than 16 KB through its slot.
     */
    @Test
    void anIdlePoolThreadRetainsAtMost16KB() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int k = 0; k < 1_000; k++) {
                Trace t = Tempo.trace(SEARCH, new Options()).records(STEP);
                List<Future<?>> fs = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    fs.add(pool.submit(t.handoff(MATCH).runnable(() -> {
                        for (int j = 0; j < 4; j++) Tempo.current().step(LOAD).set(BYTES, j).close();
                    })));
                }
                for (Future<?> f : fs) f.get();
                t.close();
                if (k % 2 == 0) t.release();
            }
            CyclicBarrier all = new CyclicBarrier(threads);
            List<Future<Retained>> slots = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                slots.add(pool.submit(() -> {
                    all.await(); // one per thread
                    return Retained.of(Tempo.SLOT.get());
                }));
            }
            for (Future<Retained> f : slots) {
                Retained r = f.get();
                assertTrue(r.bytes <= 16 * 1024, r.toString());
                assertEquals(0, r.count(Tempo.Lane.class), r.toString());
            }
        } finally {
            pool.shutdown();
        }
    }

    // ---- the chunk pool ------------------------------------------------------------------------------

    @Test
    void thePoolHandsEachChunkToOneHolderAtATime() throws Exception {
        for (int capacity : new int[] {64, 4}) {
            AtomicInteger fresh = new AtomicInteger();
            Tempo.Pool<Object> pool = new Tempo.Pool<>(capacity, () -> {
                fresh.incrementAndGet();
                return new Object();
            });
            Set<Object> held = ConcurrentHashMap.newKeySet();
            int threads = 8, rounds = 100_000;
            AtomicReference<Throwable> failed = new AtomicReference<>();
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> ts = new ArrayList<>();
            for (int k = 0; k < threads; k++) {
                Thread t = new Thread(() -> {
                    Object[] mine = new Object[3];
                    try {
                        go.await();
                        for (int i = 0; i < rounds; i++) {
                            int n = 1 + ThreadLocalRandom.current().nextInt(3);
                            for (int j = 0; j < n; j++) {
                                mine[j] = pool.take();
                                if (!held.add(mine[j])) throw new AssertionError("a chunk handed out twice");
                            }
                            for (int j = 0; j < n; j++) {
                                held.remove(mine[j]);
                                pool.give(mine[j]);
                            }
                        }
                    } catch (Throwable e) {
                        failed.compareAndSet(null, e);
                    }
                });
                ts.add(t);
                t.start();
            }
            go.countDown();
            for (Thread t : ts) t.join();
            if (failed.get() != null) throw new AssertionError(failed.get());
            assertTrue(pool.size() <= capacity);
            if (capacity == 64) { // never full: every chunk made came back
                assertTrue(fresh.get() <= threads * 4, "made " + fresh.get());
                assertEquals(fresh.get(), pool.size());
            }
        }
    }

    // ---- concurrency -------------------------------------------------------------------------------

    /**
     * Requests hand tasks to a pool and release their trace while most tasks still run; stragglers keep
     * writing, and handoffs of released traces begin on threads that have served newer traces since. A
     * renderer reads the traces throughout. Every trace stamps all it writes, and no read ever finds
     * another trace's stamp, also in snapshots kept after their chunks went to newer traces.
     */
    @Test
    void stragglersNeverWriteIntoAnotherTrace() throws Exception {
        int traces = 300, tasks = 12, spans = 20;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicReferenceArray<Trace> recent = new AtomicReferenceArray<>(4);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean();
        Thread renderer = new Thread(() -> {
            try {
                while (!stop.get()) {
                    for (int i = 0; i < recent.length(); i++) {
                        Trace t = recent.get(i);
                        if (t == null) continue;
                        oneStamp(t.snapshot());
                        t.total(LOAD);
                        t.elapsedNanos();
                    }
                }
            } catch (Throwable e) {
                failed.compareAndSet(null, e);
            }
        }, "renderer");
        renderer.start();
        List<Snapshot> kept = new ArrayList<>();
        List<Integer> records = new ArrayList<>();
        List<Future<?>> all = new ArrayList<>();
        Handoff previous = null;
        try {
            for (int k = 0; k < traces && failed.get() == null; k++) {
                long stamp = k;
                Trace t = Tempo.trace(SEARCH, new Options().budget(64)).records(STEP);
                t.set(STAMP, stamp);
                recent.set(k % recent.length(), t);
                List<Future<?>> fs = new ArrayList<>();
                for (int i = 0; i < tasks; i++) {
                    Handoff h = t.handoff(MATCH).set(STAMP, stamp);
                    fs.add(pool.submit(() -> {
                        Span w = h.begin();
                        w.set(STAMP, stamp);
                        for (int j = 0; j < spans; j++) w.step(LOAD).set(STAMP, stamp).close();
                        w.close();
                        w.set(STAMP, stamp); // a late annotation of a task that may be released by now
                    }));
                }
                Handoff late = t.handoff(MATCH);
                fs.get(0).get(); // the rest straggle past the release
                t.close();
                Snapshot snap = t.snapshot();
                assertEquals(stamp, oneStamp(snap));
                kept.add(snap);
                records.add(count(snap));
                t.release();
                all.addAll(fs);
                Handoff old = previous; // released before this trace's tasks ran on the pool
                if (old != null) {
                    all.add(pool.submit(() -> {
                        if (old.begin() != NOOP) throw new AssertionError("a handoff began after release");
                        old.runnable(() -> Tempo.current().span(LOAD).set(STAMP, -1).close()).run();
                    }));
                }
                previous = late;
            }
            for (Future<?> f : all) f.get();
        } finally {
            stop.set(true);
            renderer.join();
            pool.shutdown();
        }
        if (failed.get() != null) throw new AssertionError(failed.get());
        assertEquals(traces, kept.size());
        for (int k = 0; k < kept.size(); k++) {
            assertEquals(k, oneStamp(kept.get(k)));
            assertEquals(records.get(k), count(kept.get(k)));
        }
    }

    /** The one stamp found in a snapshot, or -1 if it has none (released); fails on a second stamp. */
    static long oneStamp(Snapshot s) {
        long[] stamp = {-1};
        s.forEachSpan(v -> {
            for (int i = 0; i < v.attributes(); i++) {
                if (v.attributeKey(i) != STAMP) continue;
                long x = v.longAttribute(i);
                if (stamp[0] < 0) stamp[0] = x;
                else if (x != stamp[0]) throw new AssertionError("stamp " + x + " in trace " + stamp[0]);
            }
        });
        return stamp[0];
    }

    static int count(Snapshot s) {
        int[] n = {0};
        s.forEachSpan(v -> n[0]++);
        return n[0];
    }

    /**
     * The commit protocol: a writer runs tasks on its lane while a reader copies the lane over and over.
     * A copy that found the lane in the same idle state before and after is complete: no open row, and
     * each child carries the flag and attribute set before its close. Hand-rolled; the jcstress tests in
     * {@code src/jcstress} check the same orderings exhaustively.
     */
    @Test
    void aLaneFoundIdleWasCopiedComplete() throws Exception {
        for (int round = 0; round < 4; round++) {
            Trace t = Tempo.trace(SEARCH);
            int n = 3_000;
            Handoff[] hs = new Handoff[n];
            for (int i = 0; i < n; i++) hs[i] = t.handoff(MATCH);
            Handoff first = t.handoff(DECODE);
            AtomicReference<Tempo.Lane> lane = new AtomicReference<>();
            AtomicBoolean done = new AtomicBoolean();
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                first.begin().close(); // registers the lane
                lane.set(t.ctx.lanesById()[1]);
                for (Handoff h : hs) {
                    Span w = h.begin();
                    Span x = w.span(LOAD);
                    x.fail();
                    x.set(BYTES, 1);
                    x.close();
                    w.close();
                    w.set(SCORE, 2); // late: an active period of its own
                }
                done.set(true);
            }, "writer");
            Thread reader = new Thread(() -> {
                try {
                    int complete = 0;
                    while (!done.get() || complete == 0) {
                        Tempo.Lane l = lane.get();
                        if (l == null) continue;
                        Tempo.Shape s = Tempo.Shape.of(l);
                        if (s == null || s.live) continue;
                        complete++;
                        for (int r = 0; r < s.rows; r++) {
                            assertNotEquals(Tempo.Lane.OPEN, s.dur[r], "open row in an idle copy");
                            if (s.name[r] != LOAD.id) continue;
                            assertNotEquals(0, s.flags[r] & Tempo.FAILED, "flag missing in an idle copy");
                            assertEquals(1, s.from[r + 1] - s.from[r], "attribute missing in an idle copy");
                        }
                    }
                } catch (Throwable e) {
                    failed.compareAndSet(null, e);
                }
            }, "reader");
            writer.start();
            reader.start();
            writer.join();
            reader.join();
            t.close();
            if (failed.get() != null) throw new AssertionError(failed.get());
            assertEquals(0, t.misuses() + t.dropped());
            t.release();
        }
    }

    /**
     * The reader's side of the commit protocol, step by step: the owner writes between the reader's two
     * reads of the state, once starting a task and once running a whole task (IDLE again, a new epoch).
     * Either way the copy is marked live; only a copy with no write in between is complete.
     */
    @Test
    void aCopyWithAWriteBetweenItsStateReadsIsLive() throws Exception {
        ExecutorService pool = TempoTest.worker("worker-1");
        try {
            Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
            Handoff a = t.handoff(MATCH), b = t.handoff(MATCH), c = t.handoff(MATCH);
            pool.submit(() -> a.begin().close()).get();
            Tempo.Lane l = t.ctx.lanesById()[1];

            int idle = l.state();
            assertFalse(Tempo.Shape.of(l, idle).live); // nothing written since
            Span open = pool.submit(b::begin).get();   // the next task starts: an open row
            Tempo.Shape s = Tempo.Shape.of(l, idle);
            assertTrue(s.live);
            assertEquals(Tempo.Lane.OPEN, s.dur[s.rows - 1]);
            pool.submit(open::close).get();

            idle = l.state();
            pool.submit(() -> c.begin().close()).get(); // a whole task: IDLE before and after, a new epoch
            assertEquals(idle & Tempo.Lane.KIND, l.state() & Tempo.Lane.KIND);
            assertTrue(Tempo.Shape.of(l, idle).live);
            assertFalse(Tempo.Shape.of(l).live);
            t.close();
            t.release();
            assertNull(Tempo.Shape.of(l));
        } finally {
            pool.shutdown();
        }
    }

    /**
     * release() races the start of a task, thousands of times: whichever side wins the lane state, every
     * lane ends retired with its chunks returned, and the task records either completely or not at all.
     */
    @Test
    void releaseRacingATaskStartRetiresEveryLane() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        int begun = 0, refused = 0;
        try {
            for (int i = 0; i < 5_000; i++) {
                Trace t = Tempo.trace(SEARCH);
                Handoff h = t.handoff(MATCH);
                t.close();
                AtomicBoolean go = new AtomicBoolean();
                Future<Boolean> task = pool.submit(() -> {
                    while (!go.get()) Thread.onSpinWait();
                    Span s = h.begin();
                    s.span(LOAD).set(BYTES, 1).close();
                    s.close();
                    s.set(BYTES, 2);
                    return s != NOOP;
                });
                go.set(true);
                t.release();
                if (task.get()) begun++;
                else refused++;
                for (Tempo.Lane l : t.ctx.lanesById()) {
                    assertTrue(l.retired(), "lane " + l.id + " of trace " + i);
                    assertNull(l.rows[0]);
                }
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(5_000, begun + refused);
    }
}
