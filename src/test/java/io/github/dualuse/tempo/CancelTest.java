package io.github.dualuse.tempo;

import io.github.dualuse.tempo.Tempo.CallableTask;
import io.github.dualuse.tempo.Tempo.Name;
import io.github.dualuse.tempo.Tempo.Options;
import io.github.dualuse.tempo.Tempo.RunnableTask;
import io.github.dualuse.tempo.Tempo.Span;
import io.github.dualuse.tempo.Tempo.SupplierTask;
import io.github.dualuse.tempo.Tempo.Task;
import io.github.dualuse.tempo.Tempo.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static io.github.dualuse.tempo.Tempo.NOOP;
import static io.github.dualuse.tempo.Tempo.STEP;
import static io.github.dualuse.tempo.TempoTest.lines;
import static io.github.dualuse.tempo.TempoTest.onThread;
import static io.github.dualuse.tempo.TempoTest.tree;
import static org.junit.jupiter.api.Assertions.*;

/** Cancel races begin: exactly one wins, inside a trace or not, a task's body honours a won cancel, and the record matches. */
class CancelTest {

    static final Name SEARCH = TempoTest.SEARCH, MATCH = TempoTest.MATCH, LOAD = TempoTest.LOAD,
            DECODE = TempoTest.DECODE, INDEX = Tempo.name("index");

    final TempoTest.Clock clock = new TempoTest.Clock();

    Options strict() {
        return new Options().clock(clock).strict(true);
    }

    /** A strict trace that draws its tasks. */
    Trace traced() {
        return Tempo.trace(SEARCH, strict()).records(STEP);
    }

    @AfterEach
    void noTraceLeftOnThread() {
        TempoTest.assertNothingLeftOpen();
    }

    static ThreadPoolExecutor pool(int threads) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                r -> new Thread(r, "worker"));
    }

    @Test
    void cancelReturnsWhetherItWon() {
        Trace t = traced();
        Task a = Tempo.task(MATCH), b = Tempo.task(LOAD), c = Tempo.task(DECODE);
        clock.add(3);
        assertTrue(a.cancel());               // won: recorded as cancelled before it began
        assertFalse(a.cancel());              // ended already
        assertFalse(a.reject());
        assertTrue(b.reject());
        Span s = c.tryBegin();
        assertFalse(c.cancel());              // lost: the task began, and runs
        s.close();
        assertFalse(c.cancel());
        Task late = Tempo.task(MATCH), released = Tempo.task(MATCH);
        t.close();
        assertEquals(0, t.misuses());
        assertEquals(lines(
                "search 0+3",
                "  match 0+0 cancelled handoff~3",
                "  load 0+0 cancelled handoff~3",
                "  decode 0+0 handoff~3",
                "  match 3+0 handoff~0 unstarted",
                "  match 3+0 handoff~0 unstarted"), tree(t));
        t.release();
        assertTrue(late.cancel());            // released: nothing recorded, and still the cancel wins
        assertNull(late.tryBegin());
        assertSame(NOOP, released.tryBegin()); // begun after release: recorded nowhere
        assertFalse(released.cancel());
        assertEquals(0, t.misuses());
    }

    @Test
    void beginAfterCancelIsTheCancelsWinNotMisuseAndBeginTwiceIsMisuse() {
        Trace t = traced();
        Task h = Tempo.task(MATCH);
        assertTrue(h.cancel());
        assertNull(h.tryBegin());             // the hand-written early return; no AssertionError under strict
        assertNull(h.tryBegin());

        Task g = Tempo.task(LOAD);
        Span s = g.tryBegin();
        assertTrue(s.recording());
        s.close();
        assertEquals("Tempo misuse: task begun twice", assertThrows(AssertionError.class, g::tryBegin).getMessage());
        t.close();
        assertEquals(1, t.misuses());
        assertEquals(lines(
                "search 0+0",
                "  match 0+0 cancelled handoff~0",
                "  load 0+0 handoff~0"), tree(t));

        Trace u = Tempo.trace(SEARCH, new Options().clock(clock));
        Task twice = Tempo.task(LOAD);
        twice.tryBegin().close();
        assertNull(twice.tryBegin());         // not strict: counted, and the work is not done twice
        RunnableTask r = Tempo.runnable(MATCH, () -> { });
        assertNull(r.tryBegin());             // a task with a body begins when it runs
        u.close();
        assertEquals(2, u.misuses());
        u.release();
    }

    @Test
    void aTaskSkipsItsBodyWhenCancelledBeforeItBegan() throws Exception {
        Trace t = traced();
        AtomicInteger ran = new AtomicInteger();
        RunnableTask r = Tempo.runnable(MATCH, ran::incrementAndGet);
        CallableTask<Integer> c = Tempo.callable(LOAD, ran::incrementAndGet);
        SupplierTask<Integer> s = Tempo.supplier(DECODE, ran::incrementAndGet);
        clock.add(4);
        assertTrue(r.cancel());
        assertTrue(c.cancel());
        assertTrue(s.reject());
        assertTrue(r.isCancelled() && r.isDone() && c.isCancelled());
        assertNull(onThread("worker", () -> {
            r.run();                          // returns at once
            CancellationException e = assertThrows(CancellationException.class, c::call);
            assertEquals("Tempo task cancelled before it began", e.getMessage());
            assertThrows(CancellationException.class, s::get);
        }));
        assertEquals(0, ran.get());
        assertThrows(CancellationException.class, r::get);
        assertThrows(CancellationException.class, c::get);
        t.close();
        assertEquals(0, t.misuses());
        assertEquals(lines(
                "search 0+4",
                "  match 0+0 cancelled handoff~4",
                "  load 0+0 cancelled handoff~4",
                "  decode 0+0 cancelled handoff~4"), tree(t));

        // A won cancel holds after release too: the body still does not run.
        Trace u = traced();
        RunnableTask skipped = Tempo.runnable(MATCH, ran::incrementAndGet), run = Tempo.runnable(LOAD, ran::incrementAndGet);
        assertTrue(skipped.cancel());
        u.close();
        u.release();
        skipped.run();
        assertEquals(0, ran.get());
        run.run();                            // released, not cancelled: the body runs, recorded nowhere
        assertEquals(1, ran.get());
        assertNull(run.get());
        assertEquals(0, u.misuses());
    }

    @Test
    void theEarlyExitCancelsQueuedTasksAndLeavesRunningOnesAlone() throws Exception {
        ThreadPoolExecutor pool = pool(1);
        try {
            Trace t = traced();
            CountDownLatch running = new CountDownLatch(1), finish = new CountDownLatch(1);
            AtomicInteger ran = new AtomicInteger();
            List<CallableTask<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int n = i;
                CallableTask<Integer> task = Tempo.callable(MATCH, () -> {
                    if (n == 0) {
                        running.countDown();
                        finish.await();
                    }
                    return ran.incrementAndGet();
                });
                task.set(INDEX, n);
                tasks.add(task);
                pool.submit(task);            // the pool calls it; the task is a Future of its own
            }
            assertTrue(running.await(10, TimeUnit.SECONDS));
            int cancelled = 0;
            for (Task task : tasks) if (task.cancel()) cancelled++;
            assertEquals(3, cancelled);       // the first one runs: its cancel lost
            finish.countDown();
            assertEquals(1, tasks.get(0).get());
            for (int i = 1; i < 4; i++) assertThrows(CancellationException.class, tasks.get(i)::get);
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
     * Outside a trace a task is still a task: its cancel wins against a worker that has not taken it, and
     * loses against one that has, exactly as inside a trace, with no help from the executor. Future.cancel
     * of the task goes through the same race first.
     */
    @Test
    void theEarlyExitStopsTheWorkWithoutATraceToo() throws Exception {
        assertSame(NOOP, Tempo.current());
        ThreadPoolExecutor pool = pool(1);
        try {
            CountDownLatch busy = new CountDownLatch(1), finish = new CountDownLatch(1);
            AtomicInteger ran = new AtomicInteger();
            CallableTask<Integer> running = Tempo.callable(MATCH, () -> {
                busy.countDown();
                finish.await();
                return ran.incrementAndGet();
            });
            pool.submit(running);
            assertTrue(busy.await(10, TimeUnit.SECONDS));
            CallableTask<Integer> queued = Tempo.callable(MATCH, ran::incrementAndGet);
            RunnableTask viaFuture = Tempo.runnable(MATCH, ran::incrementAndGet);
            pool.submit(queued);
            Future<?> f = pool.submit((Runnable) viaFuture); // the pool's own future around the task
            assertFalse(running.cancel());    // began: left to finish
            assertFalse(running.isCancelled());
            assertTrue(queued.cancel());      // queued: will never run
            assertTrue(viaFuture.cancel(false)); // through the task's Future: the task first
            finish.countDown();
            assertEquals(1, running.get(10, TimeUnit.SECONDS)); // its result arrives
            f.get(10, TimeUnit.SECONDS);      // the pool ran it, and the task returned at once
            assertThrows(CancellationException.class, queued::get);
            assertThrows(CancellationException.class, viaFuture::get);
            assertEquals(1, ran.get());       // the stopped tasks did not run
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Many tasks on a pool, and an early exit that cancels every one still pending while the workers take
     * them: every body that ran is recorded as run, every task recorded as cancelled never ran its body,
     * each cancel's result says which, and strict mode finds no misuse. Every fourth round is untraced,
     * where the same cancel still says exactly which tasks it stopped.
     */
    @Test
    void anEarlyExitRacingTheWorkersKeepsTheRecordAndTheWorkInStep() throws Exception {
        int tasks = 1_000, rounds = 24, won = 0, lost = 0, untracedWon = 0, untracedLost = 0;
        ThreadPoolExecutor pool = pool(4);
        try {
            for (int round = 0; round < rounds; round++) {
                boolean traced = round % 4 != 3;
                Trace t = traced ? Tempo.trace(SEARCH, strict().clock(Tempo.SYSTEM_CLOCK).budget(2 * tasks)).records(STEP) : null;
                AtomicIntegerArray ran = new AtomicIntegerArray(tasks), cancelled = new AtomicIntegerArray(tasks);
                AtomicInteger started = new AtomicInteger();
                List<Task> pending = new ArrayList<>();
                for (int i = 0; i < tasks; i++) {
                    int n = i;
                    Runnable body = () -> {
                        ran.incrementAndGet(n);
                        started.incrementAndGet();
                        for (long until = System.nanoTime() + 2_000; System.nanoTime() < until; ) Thread.onSpinWait();
                    };
                    Task task;
                    if (n % 2 == 0) {
                        RunnableTask r = Tempo.runnable(MATCH, body);
                        pool.execute(r);
                        task = r;
                    } else {
                        CallableTask<Integer> c = Tempo.callable(MATCH, () -> {
                            body.run();
                            return n;
                        });
                        pool.submit(c);       // the pool calls it from a future of its own
                        task = c;
                    }
                    task.set(INDEX, n);
                    pending.add(task);
                }
                while (started.get() < tasks / 10) Thread.onSpinWait();
                for (int i = 0; i < tasks; i++) if (pending.get(i).cancel()) cancelled.set(i, 1);
                for (Task task : pending) {
                    try {
                        ((Future<?>) task).get(10, TimeUnit.SECONDS);
                    } catch (CancellationException | ExecutionException e) {
                        // cancelled before it began: its body did not run
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
