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
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Supplier;

import static io.github.dualuse.tempo.Tempo.NOOP;
import static io.github.dualuse.tempo.TempoTest.lines;
import static io.github.dualuse.tempo.TempoTest.onThread;
import static io.github.dualuse.tempo.TempoTest.tree;
import static org.junit.jupiter.api.Assertions.*;

/** Cancel races begin: exactly one wins, the wrappers honour a won cancel, and the record matches. */
class CancelTest {

    static final Name SEARCH = TempoTest.SEARCH, MATCH = TempoTest.MATCH, LOAD = TempoTest.LOAD,
            DECODE = TempoTest.DECODE, INDEX = Tempo.name("index");

    final TempoTest.Clock clock = new TempoTest.Clock();

    Options strict() {
        return new Options().clock(clock).strict(true);
    }

    @AfterEach
    void noTraceLeftOnThread() {
        TempoTest.assertNothingLeftOpen();
    }

    /**
     * The early exit of the Handoff Javadoc and the README for one pending task: true if it will never run.
     * Where Tempo takes no part ({@link Handoff#NOOP}) the executor decides the race: {@code remove} takes
     * the task out of the queue only if no worker took it.
     */
    static boolean earlyExit(ThreadPoolExecutor pool, Handoff h, Future<?> future) {
        boolean won = h == Handoff.NOOP ? pool.remove((Runnable) future) : h.cancel();
        if (won) future.cancel(false); // the body will not run
        return won;
    }

    static ThreadPoolExecutor pool(int threads) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                r -> new Thread(r, "worker"));
    }

    @Test
    void cancelReturnsWhetherItWon() throws Exception {
        Trace t = Tempo.trace(SEARCH, strict());
        Handoff a = t.handoff(MATCH), b = t.handoff(LOAD), c = t.handoff(DECODE);
        clock.add(3);
        assertTrue(a.cancel());               // won: recorded as cancelled before it began
        assertFalse(a.cancel());              // ended already
        assertFalse(a.reject());
        assertTrue(b.reject());
        Span s = c.begin();
        assertFalse(c.cancel());              // lost: the task began, and runs
        s.close();
        assertFalse(c.cancel());
        assertFalse(Handoff.NOOP.cancel());
        Handoff late = t.handoff(MATCH);
        t.close();
        assertEquals(0, t.misuses());
        assertEquals(lines(
                "search 0+3",
                "  match 0+0 cancelled handoff~3",
                "  load 0+0 cancelled handoff~3",
                "  decode 0+0 handoff~3",
                "  match 3+0 handoff~0 unstarted"), tree(t));
        t.release();
        assertFalse(late.cancel());           // released: nothing to record, and nothing won
        assertSame(NOOP, late.begin());
    }

    @Test
    void beginAfterCancelIsTheCancelsWinNotMisuseAndBeginTwiceIsMisuse() {
        Trace t = Tempo.trace(SEARCH, strict());
        Handoff h = t.handoff(MATCH);
        assertTrue(h.cancel());
        assertNull(h.tryBegin());              // the hand-written early return
        assertSame(NOOP, h.begin());           // no AssertionError under strict
        assertNull(h.tryBegin());

        Handoff g = t.handoff(LOAD);
        Span s = g.tryBegin();
        assertTrue(s.recording());
        s.close();
        assertEquals("Tempo misuse: handoff begun twice", assertThrows(AssertionError.class, g::begin).getMessage());
        assertEquals("Tempo misuse: handoff begun twice", assertThrows(AssertionError.class, g::tryBegin).getMessage());
        t.close();
        assertEquals(2, t.misuses());
        assertEquals(lines(
                "search 0+0",
                "  match 0+0 cancelled handoff~0",
                "  load 0+0 handoff~0"), tree(t));
    }

    @Test
    void wrappersSkipTheBodyOfATaskCancelledBeforeItBegan() throws Exception {
        Trace t = Tempo.trace(SEARCH, strict());
        AtomicInteger ran = new AtomicInteger();
        Handoff r = t.handoff(MATCH), c = t.handoff(LOAD), s = t.handoff(DECODE);
        Runnable runnable = r.runnable(ran::incrementAndGet);
        Callable<Integer> callable = c.callable(ran::incrementAndGet);
        Supplier<Integer> supplier = s.supplier(ran::incrementAndGet);
        clock.add(4);
        assertTrue(r.cancel());
        assertTrue(c.cancel());
        assertTrue(s.reject());
        assertNull(onThread("worker", () -> {
            runnable.run();                      // returns at once
            CancellationException e = assertThrows(CancellationException.class, callable::call);
            assertEquals("Tempo handoff cancelled before it began", e.getMessage());
            assertThrows(CancellationException.class, supplier::get);
        }));
        assertEquals(0, ran.get());
        t.close();
        assertEquals(0, t.misuses());
        assertEquals(lines(
                "search 0+4",
                "  match 0+0 cancelled handoff~4",
                "  load 0+0 cancelled handoff~4",
                "  decode 0+0 cancelled handoff~4"), tree(t));

        // A won cancel holds after release too: the body still does not run.
        Trace u = Tempo.trace(SEARCH, strict());
        Handoff h = u.handoff(MATCH), k = u.handoff(LOAD);
        Runnable skipped = h.runnable(ran::incrementAndGet), run = k.runnable(ran::incrementAndGet);
        assertTrue(h.cancel());
        u.close();
        u.release();
        skipped.run();
        assertEquals(0, ran.get());
        run.run();                               // released, not cancelled: the body runs, recorded nowhere
        assertEquals(1, ran.get());
        assertEquals(0, u.misuses());
    }

    @Test
    void theEarlyExitPatternCancelsQueuedTasksAndLeavesRunningOnesAlone() throws Exception {
        ThreadPoolExecutor pool = pool(1);
        try {
            Trace t = Tempo.trace(SEARCH, strict());
            CountDownLatch running = new CountDownLatch(1), finish = new CountDownLatch(1);
            AtomicInteger ran = new AtomicInteger();
            List<Handoff> handoffs = new ArrayList<>();
            List<FutureTask<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int n = i;
                Handoff h = t.handoff(MATCH).set(INDEX, n);
                FutureTask<Integer> f = new FutureTask<>(h.callable(() -> {
                    if (n == 0) {
                        running.countDown();
                        finish.await();
                    }
                    return ran.incrementAndGet();
                }));
                handoffs.add(h);
                futures.add(f);
                pool.execute(f);
            }
            assertTrue(running.await(10, TimeUnit.SECONDS));
            int cancelled = 0;
            for (int i = 0; i < 4; i++) {
                if (earlyExit(pool, handoffs.get(i), futures.get(i))) cancelled++;
            }
            assertEquals(3, cancelled);           // the first one runs: its cancel lost
            finish.countDown();
            assertEquals(1, futures.get(0).get());
            for (int i = 1; i < 4; i++) assertThrows(CancellationException.class, futures.get(i)::get);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(1, ran.get());
            t.close();
            assertEquals(0, t.misuses());
            assertEquals(lines(
                    "search 0+0",
                    "  match 0+0 handoff~0 @worker index=0",
                    "  match 0+0 cancelled handoff~0 index=1",
                    "  match 0+0 cancelled handoff~0 index=2",
                    "  match 0+0 cancelled handoff~0 index=3"), tree(t));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The documented early exit stops the work also where Tempo takes no part, and says exactly which
     * tasks it stopped: a NOOP handoff (no live trace, or past the lane's cap) returns the task itself from
     * its wrappers and loses every cancel, so {@code h.cancel()} alone would let a queued task run. And
     * {@code Future.cancel(false)} alone cannot tell: it succeeds on a running task too, whose body runs
     * on. The executor's remove() decides it.
     */
    @Test
    void theEarlyExitPatternStopsTheWorkWithoutATraceToo() throws Exception {
        Trace closed = Tempo.trace(SEARCH, new Options().clock(clock)); // not strict: the use after close below is misuse
        closed.close();
        List<Handoff> noops = List.of(
                Tempo.current().handoff(MATCH),                     // no trace on this thread
                NOOP.handoff(MATCH),
                closed.handoff(MATCH));                      // not live
        for (Handoff h : noops) assertSame(Handoff.NOOP, h);
        closed.release();

        ThreadPoolExecutor pool = pool(1);
        try {
            CountDownLatch busy = new CountDownLatch(1), finish = new CountDownLatch(1);
            AtomicInteger ran = new AtomicInteger();
            Handoff h = Handoff.NOOP;
            Future<Integer> running = pool.submit(h.callable(() -> {
                busy.countDown();
                finish.await();
                return ran.incrementAndGet();
            }));
            assertTrue(busy.await(10, TimeUnit.SECONDS));
            Future<Integer> pattern = pool.submit(h.callable(ran::incrementAndGet));
            Future<Integer> cancelOnly = pool.submit(h.callable(ran::incrementAndGet));
            assertFalse(earlyExit(pool, h, running));               // began: not counted as stopped, left to finish
            assertFalse(running.isCancelled());
            assertTrue(earlyExit(pool, h, pattern));                // queued: will never run
            if (h.cancel()) cancelOnly.cancel(false);               // h.cancel() alone: never true for NOOP
            finish.countDown();
            assertEquals(1, running.get(10, TimeUnit.SECONDS));     // its result arrives
            assertEquals(2, cancelOnly.get(10, TimeUnit.SECONDS));  // ran: nothing stopped it
            assertThrows(CancellationException.class, pattern::get);
            assertEquals(2, ran.get());                             // the pattern's task did not run
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The wrappers are plain classes, not lambdas: a lambda is linked on its first use in a JVM, on the
     * submitting thread after the submit, and that (most of a millisecond) would be the first task's queue wait.
     */
    @Test
    void theWrappersAreNotLambdas() {
        Trace t = Tempo.trace(SEARCH, strict());
        Handoff h = t.handoff(MATCH);
        Runnable r = h.runnable(() -> { });
        Callable<Integer> c = h.callable(() -> 1);
        Supplier<Integer> s = h.supplier(() -> 1);
        assertTrue(h.cancel());
        t.close();
        for (Object w : List.of(r, c, s)) {
            assertFalse(w.getClass().isSynthetic(), w.getClass() + " is a lambda");
            assertSame(Tempo.class, w.getClass().getEnclosingClass().getEnclosingClass(), w.getClass().getName());
        }
    }

    /**
     * Many tasks on a pool, and an early exit that cancels every one still pending while the workers take
     * them: every body that ran is recorded as run, every task recorded as cancelled never ran its body,
     * each cancel's result says which, and strict mode finds no misuse. Every fourth round is untraced
     * (Handoff.NOOP throughout), where the documented pattern still says exactly which tasks it stopped.
     */
    @Test
    void anEarlyExitRacingTheWorkersKeepsTheRecordAndTheWorkInStep() throws Exception {
        int tasks = 1_000, rounds = 24, won = 0, lost = 0, untracedWon = 0, untracedLost = 0;
        ThreadPoolExecutor pool = pool(4);
        try {
            for (int round = 0; round < rounds; round++) {
                boolean traced = round % 4 != 3;
                Trace t = traced ? Tempo.trace(SEARCH, strict().clock(Tempo.SYSTEM_CLOCK).budget(2 * tasks)) : null;
                Span root = traced ? t : Tempo.current();
                AtomicIntegerArray ran = new AtomicIntegerArray(tasks), cancelled = new AtomicIntegerArray(tasks);
                AtomicInteger started = new AtomicInteger();
                List<Handoff> handoffs = new ArrayList<>();
                List<FutureTask<?>> futures = new ArrayList<>();
                for (int i = 0; i < tasks; i++) {
                    int n = i;
                    Handoff h = root.handoff(MATCH).set(INDEX, n);
                    Runnable body = () -> {
                        ran.incrementAndGet(n);
                        started.incrementAndGet();
                        for (long until = System.nanoTime() + 2_000; System.nanoTime() < until; ) Thread.onSpinWait();
                    };
                    FutureTask<?> f = n % 2 == 0 ? new FutureTask<>(h.runnable(body), null)
                            : new FutureTask<>(h.callable(() -> {
                                body.run();
                                return n;
                            }));
                    handoffs.add(h);
                    futures.add(f);
                    pool.execute(f);
                }
                while (started.get() < tasks / 10) Thread.onSpinWait();
                for (int i = 0; i < tasks; i++) {
                    if (earlyExit(pool, handoffs.get(i), futures.get(i))) cancelled.set(i, 1);
                }
                for (FutureTask<?> f : futures) {
                    try {
                        f.get(10, TimeUnit.SECONDS);
                    } catch (CancellationException | ExecutionException e) {
                        // a cancelled future, or a callable whose handoff was cancelled first: its body did not run
                    }
                }
                if (!traced) {
                    for (int n = 0; n < tasks; n++) {
                        assertTrue(ran.get(n) <= 1);
                        assertEquals(ran.get(n) == 0, cancelled.get(n) == 1, "untraced round " + round + ", task " + n + ": stopped iff the body did not run");
                        if (cancelled.get(n) == 1) untracedWon++;
                        else untracedLost++;
                    }
                    continue;
                }
                t.close();
                assertEquals(0, t.misuses(), "round " + round);
                int[] record = new int[tasks]; // 1 ran, 2 cancelled before it began
                t.forEachSpan(v -> {
                    if (v.name() != MATCH) return;
                    assertTrue(v.handoff() && !v.aggregate() && !v.neverStarted(), "every task began or was cancelled");
                    int n = (int) v.longAttribute(0);
                    assertEquals(INDEX, v.attributeKey(0));
                    assertEquals(0, record[n], "one record per task");
                    record[n] = v.cancelled() ? 2 : 1;
                    if (v.cancelled()) assertEquals(0, v.durationNanos());
                });
                for (int n = 0; n < tasks; n++) {
                    assertEquals(ran.get(n) == 1 ? 1 : 2, record[n], "round " + round + ", task " + n);
                    assertEquals(ran.get(n) == 0, cancelled.get(n) == 1, "round " + round + ", task " + n + ": the cancel won iff the body did not run");
                    assertTrue(ran.get(n) <= 1);
                    if (cancelled.get(n) == 1) won++;
                    else lost++;
                }
                t.release();
            }
            assertTrue(won > 0 && lost > 0, won + " cancels won, " + lost + " lost"); // both sides of the race were taken
            assertTrue(untracedWon > 0 && untracedLost > 0, untracedWon + " untraced tasks stopped, " + untracedLost + " ran");
        } finally {
            pool.shutdownNow();
        }
    }
}
