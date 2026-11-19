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
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.dualuse.tempo.Tempo.NOOP;
import static io.github.dualuse.tempo.Tempo.STEP;
import static io.github.dualuse.tempo.TempoTest.lines;
import static io.github.dualuse.tempo.TempoTest.onThread;
import static io.github.dualuse.tempo.TempoTest.tree;
import static io.github.dualuse.tempo.TempoTest.worker;
import static org.junit.jupiter.api.Assertions.*;

/** Tasks counted like steps and drawn when the trace records steps, and the executor whose every submit is a task. */
class TaskTest {

    static final Name SEARCH = TempoTest.SEARCH, RERANK = TempoTest.RERANK, MATCH = TempoTest.MATCH,
            LOAD = TempoTest.LOAD, DECODE = TempoTest.DECODE, BYTES = TempoTest.BYTES, JOIN = TempoTest.JOIN;

    final TempoTest.Clock clock = new TempoTest.Clock();

    Options opts() {
        return new Options().clock(clock);
    }

    @AfterEach
    void noTraceLeftOnThread() {
        TempoTest.assertNothingLeftOpen();
    }

    // ---- tasks a trace of spans does not draw -----------------------------------------------------

    /**
     * A trace that records spans only draws no task, but counts each one's run time and queue wait, calls
     * its listeners, and draws the spans opened inside it under the span it was submitted from, on the
     * thread that ran it.
     */
    @Test
    void anUndrawnTaskIsCountedAndItsSpansHangFromItsSubmitter() throws Exception {
        ExecutorService pool = worker("worker-1");
        try {
            Trace t = Tempo.trace(SEARCH, opts());
            List<Long> heard = new CopyOnWriteArrayList<>();
            t.on(MATCH, heard::add);
            try (Span r = Tempo.span(RERANK)) {
                clock.add(10);
                RunnableTask a = Tempo.runnable(MATCH, () -> {
                    clock.add(5);
                    try (Span l = Tempo.span(LOAD)) {
                        l.set(BYTES, 7);
                        clock.add(20);
                    }
                });
                clock.add(30);
                pool.submit(a).get();
                Task b = Tempo.task(MATCH);
                clock.add(4);
                assertTrue(b.cancel());
                assertEquals(4, b.elapsedNanos());
                assertEquals(30, a.elapsedNanos());
            }
            t.close();
            assertEquals(lines(
                    "search 0+69",
                    "  rerank 0+69",
                    "    load 45+20 @worker-1 bytes=7"), tree(t));
            assertEquals("count=1 sum=25 mean=25 min=25 max=25 waitSum=34 waitMax=30", t.total(MATCH).toString());
            assertEquals(t.total(MATCH).toString(), t.snapshot().total(MATCH).toString());
            assertEquals(List.of(25L), heard);
            assertEquals(0, t.misuses() + t.dropped() + t.folded());
            String header = t.toHeader();
            WireDecoder.decode(header); // a valid value, the load on thread 1 under the rerank on thread 0
            assertTrue(header.contains(":worker-1;N=search:rerank:load:bytes;S=00.0.0/11.0.0/22.0.0@1:3=7;"), header);
            t.release();
        } finally {
            pool.shutdown();
        }
    }

    /** Run on the submitting thread, an undrawn task's spans are children of the innermost span there. */
    @Test
    void anUndrawnTaskRunInlineHangsItsSpansUnderTheInnermostSpan() {
        Trace t = Tempo.trace(SEARCH, opts());
        try (Span r = Tempo.span(RERANK)) {
            RunnableTask a = Tempo.runnable(MATCH, () -> {
                Tempo.span(LOAD).close();
                clock.add(3);
            });
            clock.add(2);
            a.run();
            assertSame(r, Tempo.current());
        }
        t.close();
        assertEquals(lines("search 0+5", "  rerank 0+5", "    load 2+0"), tree(t));
        assertEquals("count=1 sum=3 mean=3 min=3 max=3 waitSum=2 waitMax=2", t.total(MATCH).toString());
    }

    /** A task submitted inside an undrawn task on a worker keeps the drawn span its submitter's spans hang from. */
    @Test
    void aTaskSubmittedInsideAnUndrawnTaskHangsFromTheSameSpan() throws Exception {
        ExecutorService one = worker("worker-1"), two = worker("worker-2");
        try {
            Trace t = Tempo.trace(SEARCH, opts());
            try (Span r = Tempo.span(RERANK)) {
                one.submit(Tempo.runnable(MATCH, () -> {
                    clock.add(1);
                    try (Span d = Tempo.span(DECODE)) {
                        clock.add(1);
                        try {
                            two.submit(Tempo.runnable(LOAD, () -> {
                                Tempo.span(BYTES).close();
                                clock.add(2);
                            })).get();
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    }
                    Tempo.span(LOAD).close(); // after the decode: a sibling of it under rerank
                })).get();
            }
            t.close();
            assertEquals(lines(
                    "search 0+4",
                    "  rerank 0+4",
                    "    decode 1+3 @worker-1",
                    "      bytes 2+0 @worker-2",
                    "    load 4+0 @worker-1"), tree(t));
            assertEquals(2, t.total(LOAD).count()); // the task and the span
            assertEquals(0, t.misuses());
        } finally {
            one.shutdown();
            two.shutdown();
        }
    }

    /** A task is a step: submitting one fixes what the trace records, like opening a step. */
    @Test
    void aTaskFixesWhatTheTraceRecords() {
        Trace t = Tempo.trace(SEARCH, opts());
        Tempo.task(MATCH).cancel();
        t.records(STEP);
        assertEquals(Tempo.SPAN, t.records());
        assertEquals(1, t.misuses());
        t.close();
        assertEquals(lines("search 0+0"), tree(t));
    }

    /** Outside a trace a task of a name a global listener hears is timed for it, like a span. */
    @Test
    void aTaskOutsideATraceIsTimedForAGlobalListener() throws Exception {
        Name heard = Tempo.name("task-outside-heard");
        List<Long> got = new CopyOnWriteArrayList<>();
        Tempo.on(heard, got::add);
        RunnableTask r = Tempo.runnable(heard, () -> { });
        assertNull(onThread("worker", r));
        assertEquals(1, got.size());
        assertSame(NOOP, Tempo.task(MATCH).tryBegin());
        assertEquals(1, got.size());
    }

    // ---- tasks with a body --------------------------------------------------------------------------

    @Test
    void aRunnableTaskThrowsOnAndCompletesItsFuture() throws Exception {
        Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
        IllegalStateException boom = new IllegalStateException("boom");
        RunnableTask r = Tempo.runnable(MATCH, () -> {
            clock.add(2);
            throw boom;
        });
        assertSame(boom, assertThrows(IllegalStateException.class, r::run));
        assertSame(boom, assertThrows(ExecutionException.class, r::get).getCause());
        assertTrue(r.isDone() && !r.isCancelled());
        assertFalse(r.cancel(true));
        r.run(); // at most once: nothing happens
        CallableTask<Integer> c = Tempo.callable(LOAD, () -> 7);
        assertEquals(7, c.call());
        assertEquals(7, c.get());
        assertEquals(7, c.call());       // a second call is misuse: the body runs again, unrecorded
        SupplierTask<String> s = Tempo.supplier(DECODE, () -> "x");
        assertEquals("x", CompletableFuture.supplyAsync(s, Runnable::run).get());
        assertEquals("x", CompletableFuture.supplyAsync(s, Runnable::run).get()); // the same, healed
        RunnableTask cancelled = Tempo.runnable(JOIN, () -> fail("cancelled"));
        CallableTask<Integer> taken = Tempo.callable(JOIN, () -> 1);
        assertTrue(cancelled.cancel() && taken.cancel());
        cancelled.run();                 // a cancelled task is not run, and is not misuse
        assertThrows(CancellationException.class, taken::call);
        t.close();
        assertEquals(lines(
                "search 0+2",
                "  match 0+2 failed handoff~0",
                "  load 2+0 handoff~0",
                "  decode 2+0 handoff~0",
                "  join 2+0 cancelled handoff~0",
                "  join 2+0 cancelled handoff~0"), tree(t));
        assertEquals(3, t.misuses()); // begun twice: the second run, call and get
        assertEquals(1, t.total(LOAD).count());
        assertEquals(1, t.total(DECODE).count());
    }

    @Test
    void aTaskWithABodyIsNotBegunByHand() {
        Trace t = Tempo.trace(SEARCH, opts().strict(true)).records(STEP);
        RunnableTask r = Tempo.runnable(MATCH, () -> { });
        assertEquals("Tempo misuse: tryBegin on a task with a body", assertThrows(AssertionError.class, r::tryBegin).getMessage());
        assertTrue(r.cancel());
        t.close();
    }

    @Test
    void getWaitsForTheBodyAndTimesOut() throws Exception {
        ExecutorService pool = worker("worker-1");
        try {
            CountDownLatch go = new CountDownLatch(1);
            CallableTask<String> c = Tempo.callable(MATCH, () -> {
                go.await();
                return "done";
            });
            pool.submit(c);
            assertThrows(TimeoutException.class, () -> c.get(1, TimeUnit.MILLISECONDS));
            assertFalse(c.isDone());
            AtomicReference<String> got = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                try {
                    got.set(c.get());
                } catch (Exception e) {
                    got.set(e.toString());
                }
            });
            waiter.start();
            go.countDown();
            waiter.join(10_000);
            assertEquals("done", got.get());
            assertEquals("done", c.get(0, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
        }
    }

    // ---- the executor -------------------------------------------------------------------------------

    @Test
    void anExecutorMakesATaskOfEverySubmitWhichIsItsFuture() throws Exception {
        ExecutorService pool = worker("worker-1");
        ExecutorService match = Tempo.executor(pool, "match");
        try {
            Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
            Future<?> a = match.submit(() -> clock.add(1));
            assertNull(a.get());
            Future<Integer> b = match.submit(() -> {
                clock.add(2);
                return 2;
            });
            assertEquals(2, b.get());
            Future<String> c = match.submit(() -> clock.add(3), "three");
            assertEquals("three", c.get());
            for (Future<?> f : List.of(a, b, c)) assertTrue(f instanceof Task, f.getClass().getName());
            List<Callable<Integer>> all = List.of(() -> 4, () -> 5);
            int sum = 0;
            for (Future<Integer> f : match.invokeAll(all)) sum += f.get();
            assertEquals(9, sum);
            CountDownLatch ran = new CountDownLatch(1);
            match.execute(ran::countDown);
            assertTrue(ran.await(10, TimeUnit.SECONDS));
            match.submit(() -> { }).get(); // after the execute
            t.close();
            assertEquals(lines(
                    "search 0+6",
                    "  match 0+1 handoff~0 @worker-1",
                    "  match 1+2 handoff~0 @worker-1",
                    "  match 3+3 handoff~0 @worker-1",
                    "  match 6+0 handoff~0 @worker-1",
                    "  match 6+0 handoff~0 @worker-1",
                    "  match 6+0 handoff~0 @worker-1",
                    "  match 6+0 handoff~0 @worker-1"), tree(t));
            assertEquals(7, t.total(MATCH).count());
            assertEquals(0, t.misuses());
            assertTrue(match.toString().contains("match"));
        } finally {
            match.shutdown();
            assertTrue(match.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(pool.isShutdown() && match.isTerminated());
        }
    }

    @Test
    void aTaskSubmittedToAnExecutorKeepsItsNameAndIsTheFuture() throws Exception {
        ExecutorService pool = worker("worker-1");
        ExecutorService match = Tempo.executor(pool, MATCH);
        try {
            Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
            RunnableTask load = Tempo.runnable(LOAD, () -> clock.add(1));
            CallableTask<Integer> decode = Tempo.callable(DECODE, () -> 2);
            assertSame(load, match.submit(load));
            assertSame(decode, match.submit(decode));
            assertNull(load.get());
            assertEquals(2, decode.get());
            t.close();
            assertEquals(lines(
                    "search 0+1",
                    "  load 0+1 handoff~0 @worker-1",
                    "  decode 0+0 handoff~1 @worker-1"), tree(t));
        } finally {
            match.shutdownNow();
        }
    }

    @Test
    void submitWithAResultKeepsTheTaskAndItsName() throws Exception {
        ExecutorService pool = worker("worker-1");
        ExecutorService match = Tempo.executor(pool, MATCH);
        try {
            Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
            RunnableTask load = Tempo.runnable(LOAD, () -> clock.add(1));
            Future<String> f = match.submit(load, "done");
            assertSame(load, f);
            assertEquals("done", f.get());
            t.close();
            assertEquals(lines(
                    "search 0+1",
                    "  load 0+1 handoff~0 @worker-1"), tree(t));
            assertEquals(0, t.total(MATCH).count());
        } finally {
            match.shutdownNow();
        }
    }

    /**
     * invokeAll and invokeAny make one task per element, under the executor's name or the task's own, and
     * a cancel at their timeout or after the first result reaches the task.
     */
    @Test
    void invokeAllAndInvokeAnyRecordEachElementOnce() throws Exception {
        ExecutorService pool = worker("worker-1");
        ExecutorService match = Tempo.executor(pool, MATCH);
        try {
            Trace t = Tempo.trace(SEARCH, opts().cpu(false)).records(STEP);
            String first = match.invokeAny(List.of(() -> "a", () -> "b"));
            assertTrue(first.equals("a") || first.equals("b"));
            List<CallableTask<Integer>> own = List.of(Tempo.callable(LOAD, () -> 1), Tempo.callable(LOAD, () -> 2));
            List<Future<Integer>> fs = match.invokeAll(own);
            assertSame(own.get(0), fs.get(0));
            assertSame(own.get(1), fs.get(1));
            assertEquals(3, fs.get(0).get() + fs.get(1).get());
            match.submit(() -> { }).get(); // the invokeAny loser, if it ran, has ended too
            t.close();
            String tree = tree(t);
            assertEquals(3, tree.split("  match ", -1).length - 1, tree); // two of invokeAny, one submit
            assertEquals(2, tree.split("  load ", -1).length - 1, tree);
            assertEquals(2, t.total(LOAD).count());
            long matches = t.total(MATCH).count(); // the loser may be cancelled before it began
            assertTrue(matches == 2 || matches == 3, tree);
            assertEquals(0, t.misuses());
        } finally {
            match.shutdownNow();
        }
    }

    @Test
    void aTimedInvokeAllCancelsTheTasksItGivesUpOn() throws Exception {
        ExecutorService pool = worker("worker-1");
        ExecutorService match = Tempo.executor(pool, MATCH);
        CountDownLatch busy = new CountDownLatch(1), finish = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                busy.countDown();
                try {
                    finish.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(busy.await(10, TimeUnit.SECONDS));
            Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
            AtomicInteger ran = new AtomicInteger();
            CallableTask<Integer> late = Tempo.callable(LOAD, ran::incrementAndGet);
            Callable<Integer> plain = ran::incrementAndGet;
            List<Future<Integer>> fs = match.invokeAll(List.of(late, plain), 20, TimeUnit.MILLISECONDS);
            for (Future<Integer> f : fs) assertTrue(f.isCancelled() && f instanceof Task);
            assertFalse(late.cancel()); // cancelled already, by invokeAll
            finish.countDown();
            match.submit(() -> { }).get();
            t.close();
            assertEquals(0, ran.get());
            assertEquals(lines(
                    "search 0+0",
                    "  load 0+0 cancelled handoff~0",
                    "  match 0+0 cancelled handoff~0",
                    "  match 0+0 handoff~0 @worker-1"), tree(t));
        } finally {
            finish.countDown();
            match.shutdownNow();
        }
    }

    @Test
    void timingsHaveANameAndAStart() throws Exception {
        Trace t = Tempo.trace(SEARCH, opts());
        clock.add(5);
        Span s = Tempo.span(LOAD);
        clock.add(2);
        Tempo.Phase p = s.phase(DECODE);
        Task k = Tempo.task(MATCH);
        clock.add(3);
        assertEquals(LOAD, s.name());
        assertEquals(5, s.startNanos());
        assertEquals(DECODE, p.name());
        assertEquals(7, p.startNanos());
        assertEquals(MATCH, k.name());
        assertEquals(7, k.startNanos());
        assertEquals(3, k.waitNanos());
        assertEquals(3, k.elapsedNanos()); // a task's own time is its queue wait
        Span step = Tempo.step(DECODE);     // undrawn: kept while open
        clock.add(1);
        assertEquals(DECODE, step.name());
        assertEquals(10, step.startNanos());
        step.close();
        assertEquals(1, step.elapsedNanos());
        assertEquals(0, step.startNanos());
        s.close();
        assertEquals(SEARCH, t.name());
        assertEquals(0, t.startNanos());
        t.close();
        assertNull(NOOP.name());
        assertEquals(0, NOOP.startNanos());
        t.release();
        assertNull(s.name());
        assertNull(p.name());
        assertEquals(0, s.startNanos());
        assertEquals(0, k.startNanos());
        assertEquals(MATCH, k.name());
    }

    @Test
    void aSubmitThePoolRefusesIsRecordedRejected() throws Exception {
        ExecutorService dead = Executors.newSingleThreadExecutor();
        dead.shutdown();
        ExecutorService match = Tempo.executor(dead, MATCH);
        Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
        AtomicInteger ran = new AtomicInteger();
        clock.add(2);
        assertThrows(RejectedExecutionException.class, () -> match.submit(ran::incrementAndGet));
        assertThrows(RejectedExecutionException.class, () -> match.execute(ran::incrementAndGet));
        RunnableTask own = Tempo.runnable(LOAD, ran::incrementAndGet);
        assertThrows(RejectedExecutionException.class, () -> match.submit(own));
        assertTrue(own.isCancelled());
        assertFalse(own.cancel());
        t.close();
        assertEquals(0, ran.get());
        assertEquals(lines(
                "search 0+2",
                "  match 2+0 cancelled handoff~0",
                "  match 2+0 cancelled handoff~0",
                "  load 2+0 cancelled handoff~0"), tree(t));
    }

    /**
     * A JDK {@code ExecutorCompletionService} over the executor makes each element through its {@code
     * newTaskFor} and hands the executor a wrapper of it: still one task per submit, its run time and
     * queue wait counted once, a task of its own kept as it is.
     */
    @Test
    void aCompletionServiceOverTheExecutorRecordsEachSubmitOnce() throws Exception {
        ExecutorService pool = worker("worker-1");
        ExecutorService match = Tempo.executor(pool, MATCH);
        CountDownLatch busy = new CountDownLatch(1), finish = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                busy.countDown();
                try {
                    finish.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(busy.await(10, TimeUnit.SECONDS));
            Trace t = Tempo.trace(SEARCH, opts().cpu(false)).records(STEP);
            CompletionService<Integer> cs = new ExecutorCompletionService<>(match);
            Future<Integer> a = cs.submit(() -> {
                clock.add(3);
                return 1;
            });
            assertTrue(a instanceof Task);
            clock.add(2);
            CallableTask<Integer> own = Tempo.callable(LOAD, () -> 2);
            assertSame(own, cs.submit(own));
            clock.add(5);
            finish.countDown();
            assertEquals(3, cs.take().get() + cs.take().get());
            t.close();
            assertEquals(lines(
                    "search 0+10",
                    "  match 0+3 handoff~7 @worker-1",
                    "  load 2+0 handoff~8 @worker-1"), tree(t));
            assertEquals("count=1 sum=3 mean=3 min=3 max=3 waitSum=7 waitMax=7", t.total(MATCH).toString());
            assertEquals("count=1 sum=0 mean=0 min=0 max=0 waitSum=8 waitMax=8", t.total(LOAD).toString());
            assertEquals(0, t.misuses());
        } finally {
            finish.countDown();
            match.shutdownNow();
        }
    }

    /**
     * A refused submit is recorded as rejected, with its queue wait in the totals, whether it came through
     * {@code submit}, {@code invokeAny} or a completion service over the executor: never left as a task
     * that has not started.
     */
    @Test
    void aRefusedInvokeAnyOrCompletionServiceSubmitIsRecordedRejected() {
        ExecutorService refusing = new AbstractExecutorService() { // refuses each task 4 ns after the submit
            @Override
            public void execute(Runnable r) {
                clock.add(4);
                throw new RejectedExecutionException("full");
            }

            @Override
            public void shutdown() { }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return false;
            }
        };
        ExecutorService match = Tempo.executor(refusing, MATCH);
        Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
        AtomicInteger ran = new AtomicInteger();
        assertThrows(RejectedExecutionException.class, () -> match.submit(ran::incrementAndGet));
        assertThrows(RejectedExecutionException.class, () -> match.invokeAny(List.of(ran::incrementAndGet)));
        CallableTask<Integer> own = Tempo.callable(LOAD, ran::incrementAndGet);
        assertThrows(RejectedExecutionException.class, () -> match.invokeAny(List.of(own)));
        assertTrue(own.isCancelled());
        CompletionService<Integer> cs = new ExecutorCompletionService<>(match);
        assertThrows(RejectedExecutionException.class, () -> cs.submit(ran::incrementAndGet));
        t.close();
        assertEquals(0, ran.get());
        assertEquals(lines(
                "search 0+16",
                "  match 0+0 cancelled handoff~4",
                "  match 4+0 cancelled handoff~4",
                "  load 8+0 cancelled handoff~4",
                "  match 12+0 cancelled handoff~4"), tree(t));
        assertEquals("count=0 sum=0 mean=0 min=0 max=0 waitSum=12 waitMax=4", t.total(MATCH).toString());
        assertEquals("count=0 sum=0 mean=0 min=0 max=0 waitSum=4 waitMax=4", t.total(LOAD).toString());
    }

    /** {@code Future.cancel} cancels the task first: a queued task's body never runs, traced or not. */
    @Test
    void futureCancelStopsAQueuedTaskWithOrWithoutATrace() throws Exception {
        for (boolean traced : new boolean[] {true, false}) {
            ExecutorService pool = worker("worker-1");
            ExecutorService match = Tempo.executor(pool, MATCH);
            try {
                Trace t = traced ? Tempo.trace(SEARCH, opts()).records(STEP) : null;
                CountDownLatch busy = new CountDownLatch(1), finish = new CountDownLatch(1);
                AtomicInteger ran = new AtomicInteger();
                Future<?> running = match.submit(() -> {
                    busy.countDown();
                    finish.await();
                    return ran.incrementAndGet();
                });
                assertTrue(busy.await(10, TimeUnit.SECONDS));
                Future<?> queued = match.submit(ran::incrementAndGet);
                assertTrue(queued.cancel(false));
                assertTrue(queued.isCancelled());
                assertFalse(((Task) queued).cancel());
                finish.countDown();
                running.get(10, TimeUnit.SECONDS);
                match.submit(() -> { }).get();
                assertEquals(1, ran.get());
                assertThrows(CancellationException.class, queued::get);
                if (traced) {
                    t.close();
                    assertEquals(lines(
                            "search 0+0",
                            "  match 0+0 handoff~0 @worker-1",
                            "  match 0+0 cancelled handoff~0",
                            "  match 0+0 handoff~0 @worker-1"), tree(t));
                    t.release();
                }
            } finally {
                match.shutdownNow();
            }
        }
    }

    /**
     * Once the body runs, {@code Future.cancel(true)} cancels the future as a {@code FutureTask} does: it
     * interrupts the body, drops its result and marks the task's span cancelled.
     */
    @Test
    void futureCancelOfARunningTaskInterruptsItAndMarksItsSpan() throws Exception {
        ExecutorService pool = worker("worker-1");
        ExecutorService match = Tempo.executor(pool, MATCH);
        try {
            Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
            CountDownLatch busy = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            Future<Integer> f = match.submit(() -> {
                busy.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
                return 1;
            });
            assertTrue(busy.await(10, TimeUnit.SECONDS));
            assertFalse(((Task) f).cancel()); // it has begun
            assertTrue(f.cancel(true));
            assertFalse(f.cancel(true));
            assertThrows(CancellationException.class, f::get);
            match.submit(() -> { }).get(); // the cancelled task has ended
            assertTrue(interrupted.get());
            assertFalse(pool.submit(() -> Thread.currentThread().isInterrupted()).get(), "the interrupt stays with its task");
            t.close();
            assertEquals(lines(
                    "search 0+0",
                    "  match 0+0 cancelled handoff~0 @worker-1",
                    "  match 0+0 handoff~0 @worker-1"), tree(t));
            assertEquals(2, t.total(MATCH).count()); // it ran
        } finally {
            match.shutdownNow();
        }
    }

    /** {@code execute} throws a body's throwable on the pool thread, as the pool does; {@code submit} keeps it in the future. */
    @Test
    void executeThrowsOnThePoolThreadAndSubmitKeepsItInTheFuture() throws Exception {
        List<Throwable> uncaught = new CopyOnWriteArrayList<>();
        CountDownLatch died = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread w = new Thread(r, "worker-1");
            w.setUncaughtExceptionHandler((th, e) -> {
                uncaught.add(e);
                died.countDown();
            });
            return w;
        });
        ExecutorService match = Tempo.executor(pool, MATCH);
        try {
            IllegalStateException boom = new IllegalStateException("boom");
            Future<?> f = match.submit(() -> {
                throw boom;
            });
            assertSame(boom, assertThrows(ExecutionException.class, f::get).getCause());
            RunnableTask own = Tempo.runnable(LOAD, () -> {
                throw boom;
            });
            assertSame(boom, assertThrows(ExecutionException.class, match.submit(own)::get).getCause());
            assertEquals(List.of(), uncaught);
            match.execute(() -> {
                throw boom;
            });
            assertTrue(died.await(10, TimeUnit.SECONDS));
            assertEquals(List.of(boom), uncaught);
        } finally {
            match.shutdownNow();
        }
    }

    @Test
    void aNullPoolOrBodyIsMisuseAndHealed() throws Exception {
        Trace t = Tempo.trace(SEARCH, opts()).records(STEP);
        ExecutorService inline = Tempo.executor(null, MATCH); // runs every task on the submitting thread
        assertEquals(1, t.misuses());
        assertEquals(3, inline.submit(() -> 3).get());
        RunnableTask r = Tempo.runnable(LOAD, null);
        CallableTask<Integer> c = Tempo.callable(LOAD, null);
        SupplierTask<Integer> s = Tempo.supplier(LOAD, null);
        assertEquals(4, t.misuses());
        r.run();
        assertNull(c.call());
        assertNull(s.get());
        assertNull(r.get());
        inline.shutdown();
        assertThrows(RejectedExecutionException.class, () -> inline.execute(() -> { }));
        t.close();
        assertEquals(1, t.total(MATCH).count());
        assertEquals(3, t.total(LOAD).count());
        Trace u = Tempo.trace(SEARCH, opts().strict(true));
        assertThrows(AssertionError.class, () -> Tempo.executor(null, MATCH));
        assertThrows(AssertionError.class, () -> Tempo.runnable(LOAD, null));
        u.close();
        Tempo.runnable(LOAD, null).run(); // outside a trace: nothing to count, nothing thrown
    }

    /** The old untraced early exit needed the executor: a task outside a trace now decides it alone. */
    @Test
    void untracedTasksCancelInEveryForm() throws Exception {
        ExecutorService pool = worker("worker-1");
        try {
            CountDownLatch busy = new CountDownLatch(1), finish = new CountDownLatch(1);
            pool.submit(() -> {
                busy.countDown();
                finish.await();
                return null;
            });
            assertTrue(busy.await(10, TimeUnit.SECONDS));
            AtomicInteger ran = new AtomicInteger();
            RunnableTask r = Tempo.runnable(MATCH, ran::incrementAndGet);
            CallableTask<Integer> c = Tempo.callable(MATCH, ran::incrementAndGet);
            SupplierTask<Integer> s = Tempo.supplier(MATCH, ran::incrementAndGet);
            Future<?> fr = pool.submit(r);
            Future<?> fc = pool.submit(c);
            CompletableFuture<Integer> fs = CompletableFuture.supplyAsync(s, pool);
            List<Task> all = new ArrayList<>(List.of(r, c, s));
            for (Task task : all) assertTrue(task.cancel());
            finish.countDown();
            fr.get(10, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> fc.get(10, TimeUnit.SECONDS)); // call() threw CancellationException
            assertThrows(ExecutionException.class, () -> fs.get(10, TimeUnit.SECONDS));
            assertEquals(0, ran.get());
        } finally {
            pool.shutdown();
        }
    }
}
