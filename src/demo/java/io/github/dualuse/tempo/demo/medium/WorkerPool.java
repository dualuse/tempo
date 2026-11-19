package io.github.dualuse.tempo.demo.medium;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * A request handed to thread pools: {@code POST /match} matches a query against 12 shards on a pool of
 * 3 threads and stops as soon as one shard holds an exact hit. Even shards are in memory and only
 * compute; odd shards are cold and first load from storage, which blocks. The request then stores the
 * result through a small writer pool that runs a task on the caller's thread when it is full, and
 * notifies three subscribers through a pool that refuses work when it is full.
 *
 * <p>It shows {@code Tempo.executor}, which turns every submit into a task with its queue wait, a
 * blocking join marked {@code waiting()}, CPU time per pool task at {@code STEP}, and the three ways a
 * task ends other than running on a pool thread: inline (caller-runs), rejected (recorded by the
 * executor) and cancelled by an early exit ({@code Future.cancel}, which takes back a task that has not
 * begun: it never runs).
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=WorkerPool                         the story and the response
 * ./gradlew -q demo -Pdemo=WorkerPool | tools/tempo           lanes: work-1..3, store-1, notify-1, main
 * ./gradlew -q demo -Pdemo=WorkerPool | tools/tempo --tree    wait, cpu and off-cpu per task
 * </pre>
 *
 * Look for: {@code match} tasks on {@code work-1..3} whose queue wait ({@code ░}) grows round by round;
 * {@code load} inside the odd shards, which run long off CPU; {@code join} and {@code flush} drawn as
 * blocking waits on {@code main}; the last {@code match} tasks flagged {@code c}, taken back after the
 * hit, and the ones that were running then flagged {@code c} too; one {@code store} on {@code main} with
 * no queue wait; one {@code notify} flagged {@code c}, refused. Or open the printed {@code Tempo-Link} in
 * a browser.
 */
@SuppressWarnings("try") // blocks that only time their work never name the span; see src/demo/README.md
public final class WorkerPool {
    static final Tempo.Name REQUEST = Tempo.name("POST /match"), MATCH = Tempo.name("match"),
            LOAD = Tempo.name("load"), JOIN = Tempo.name("join"), STORE = Tempo.name("store"),
            NOTIFY = Tempo.name("notify"), FLUSH = Tempo.name("flush");
    static final Tempo.Name SHARD = Tempo.name("shard"), HIT = Tempo.name("hit"),
            CANCELLED = Tempo.name("cancelled"), REFUSED = Tempo.name("refused");

    static final int SHARDS = 12, THREADS = 3, HIT_SHARD = 4, SUBSCRIBERS = 3, WRITES = 3;
    static final long TASK_MS = 15, LOAD_MS = 14, WRITE_MS = 10, NOTIFY_MS = 10;

    public static void main(String[] args) throws Exception {
        // Pools live as long as the service, not the request; the request only submits to them. The
        // service wraps each pool once in a Tempo.executor: every submit to it becomes a task of its name.
        ThreadPoolExecutor work = Demo.pool("work", THREADS);
        work.prestartAllCoreThreads(); // idle and waiting, as in a service that has run for a while
        ThreadPoolExecutor store = Demo.pool("store", 1, 1, new ThreadPoolExecutor.CallerRunsPolicy());
        ThreadPoolExecutor notify = Demo.pool("notify", 1, 1, new ThreadPoolExecutor.AbortPolicy());
        ExecutorService matcher = Tempo.executor(work, MATCH), writer = Tempo.executor(store, STORE),
                notifier = Tempo.executor(notify, NOTIFY);

        // STEP (see LevelsAndOptIn): tasks and the load steps are drawn, and every task begun on a pool
        // thread (a lane root) samples its thread's CPU time, so the response shows CPU next to wall
        // time per task.
        Tempo.Trace t = Tempo.trace(REQUEST, new Tempo.Options().strict(true)).records(Tempo.STEP);
        Outcome o;
        try (t) {
            o = match(matcher, writer, notifier);
            t.set(HIT, o.hit).set(CANCELLED, o.cancelled).set(REFUSED, o.refused);
        }
        // A service would render right away, and the matches still running would show open (o). The demo
        // waits for the pools first, so that they have ended and show how they ended. A closed trace that
        // is not released still records the tasks of the request.
        Demo.shutdown(work, store, notify);
        check(t, o, Thread.currentThread().getName());
        Demo.respond(t);
    }

    /** What the handler reports. */
    static final class Outcome {
        int hit = -1, cancelled, refused;
    }

    /** The request handler. */
    static Outcome match(ExecutorService matcher, ExecutorService writer, ExecutorService notifier) throws Exception {
        Outcome o = new Outcome();
        // Scatter. Each submit makes a match task here, under the request: its queue wait runs from now to
        // the moment a pool thread begins it. The task is the one object Tempo allocates for it, and it is
        // also the Future that submit returns.
        List<Future<Boolean>> matches = new ArrayList<>();
        for (int shard = 0; shard < SHARDS; shard++) {
            int n = shard;
            matches.add(matcher.submit(() -> matchShard(n)));
        }

        // Gather. The request thread blocks here; waiting() tells the viewers so: they draw the join as
        // a wait, not as work of main, and follow the critical path across into the task it waits for.
        try (Tempo.Span join = Tempo.span(JOIN).waiting()) {
            for (int n = 0; n < SHARDS && o.hit < 0; n++) {
                if (matches.get(n).get()) o.hit = n;
            }
        }

        // Early exit: the hit is all the request needs, so it cancels every later shard's future. Cancel
        // and begin race for a task and exactly one wins: a task that has not begun never runs, even if a
        // worker takes it this very moment, and shows as cancelled before it began (flag c), with no run
        // time. A task already running runs to its end, as with FutureTask.cancel(false): its result is
        // dropped and its span flagged c. The taken-back tasks stay in the queue at no cost;
        // ThreadPoolExecutor.remove would only free the slots of a bounded queue sooner. The race is the
        // same in an untraced request.
        for (int n = o.hit + 1; n < SHARDS; n++) {
            if (matches.get(n).cancel(false)) o.cancelled++;
        }

        List<Future<?>> pending = new ArrayList<>();

        // The writer pool has one thread and room for one queued write. When both are taken,
        // CallerRunsPolicy runs the write right here on the request thread: in Tempo-Timing the task
        // becomes a plain child of the request, with no queue wait, and the request is slowed down
        // (backpressure).
        for (int i = 0; i < WRITES; i++) pending.add(writer.submit(() -> Demo.io(WRITE_MS)));

        // The notifier refuses work when it is full (AbortPolicy). The executor records the refused task
        // as rejected before the exception reaches the request; otherwise it would show as still waiting.
        for (int i = 0; i < SUBSCRIBERS; i++) {
            try {
                pending.add(notifier.submit(() -> Demo.io(NOTIFY_MS)));
            } catch (RejectedExecutionException full) {
                o.refused++;
            }
        }

        try (Tempo.Span flush = Tempo.span(FLUSH).waiting()) {
            for (Future<?> f : pending) f.get();
        }
        return o;
    }

    /**
     * One shard, on a pool thread. Inside a task, Tempo.current() is the task's span: the explicit way to
     * put a number on it from code that was handed no span. The load step nests under it.
     */
    static boolean matchShard(int shard) {
        Tempo.current().set(SHARD, shard);
        if (shard % 2 == 0) {
            Demo.cpu(TimeUnit.MILLISECONDS.toMicros(TASK_MS));          // in memory: compute only
        } else {
            try (Tempo.Span load = Tempo.step(LOAD)) {
                Demo.io(LOAD_MS);                                       // cold: blocks, off CPU
            }
            Demo.cpu(TimeUnit.MILLISECONDS.toMicros(TASK_MS - LOAD_MS));
        }
        return shard == HIT_SHARD;
    }

    // ------------------------------------------------------------------ what the trace shows

    static void check(Tempo.Trace t, Outcome o, String request) {
        // A task that began has a run time; one cancelled or rejected before it began has none.
        Predicate<Tempo.SpanView> ran = v -> v.handoff() && !v.neverStarted() && (!v.cancelled() || v.durationNanos() > 0);
        Predicate<Tempo.SpanView> task = v -> v.name() == MATCH && ran.test(v);
        long ms = TimeUnit.MILLISECONDS.toNanos(1);

        Demo.check(Demo.count(t, v -> v.name() == MATCH && v.handoff()) == SHARDS,
                "every submit is a match task, the cancelled ones included");
        Demo.check(Demo.count(t, task.and(v -> v.thread().startsWith("work-"))) == Demo.count(t, task),
                "every match that ran, ran on a work-N thread");
        // Shards 0..2 went straight to the three idle threads; every later one sat in the queue at
        // least until one of those finished, TASK_MS after the first submit. (On a cold JVM the very
        // first task also shows a small wait: class loading, not the queue.)
        Demo.check(Demo.count(t, task.and(v -> shard(v) >= THREADS && v.waitNanos() < 2 * ms)) == 0
                        && Demo.count(t, task.and(v -> shard(v) >= THREADS)) >= HIT_SHARD + 1 - THREADS,
                "the matches submitted beyond the pool size waited in the queue for milliseconds");

        Demo.check(Demo.count(t, Tempo.SpanView::open) == 0, "the demo waited for the pools: nothing is open");
        Predicate<Tempo.SpanView> closed = task.and(v -> !v.open());
        Predicate<Tempo.SpanView> cold = closed.and(v -> shard(v) % 2 == 1), hot = closed.and(v -> shard(v) % 2 == 0);
        Demo.check(Demo.count(t, closed.and(v -> v.cpuNanos() < 0)) == 0, "CPU time is measured for every match task");
        Demo.check(Demo.count(t, cold.and(v -> v.cpuNanos() * 2 >= v.durationNanos())) == 0,
                "the cold shards spent most of their wall time off CPU, blocked in load");
        List<Long> shardById = new ArrayList<>(); // span ids are the order of the walk
        t.forEachSpan(v -> shardById.add(v.name() == MATCH ? shard(v) : -1L));
        Demo.check(Demo.count(t, LOAD) >= 1 && Demo.count(t, v -> v.name() == LOAD
                        && (shardById.get(v.parent()) % 2 != 1 || !v.thread().startsWith("work-"))) == 0,
                "load spans nest inside the cold (odd) match tasks, on the work threads");
        Demo.check(most(t, hot, Tempo.SpanView::cpuNanos) > most(t, cold, Tempo.SpanView::cpuNanos),
                "a hot shard burnt more CPU than any cold one");

        Demo.check(Demo.count(t, v -> v.name() == JOIN && v.waiting() && request.equals(v.thread())) == 1,
                "join is a blocking wait on the request thread");
        Demo.check(Demo.count(t, v -> v.name() == FLUSH && v.waiting()) == 1, "flush is a blocking wait too");

        Demo.check(o.hit == HIT_SHARD && Demo.attribute(t, REQUEST, HIT) == HIT_SHARD,
                "the request found its hit in shard " + HIT_SHARD);
        int skipped = Demo.count(t, v -> v.name() == MATCH && v.cancelled() && v.durationNanos() == 0 && v.cpuNanos() < 0),
                dropped = Demo.count(t, task.and(Tempo.SpanView::cancelled));
        Demo.check(skipped >= 1, "the early exit took back at least one match before it began");
        // A match whose body ends at the very moment of its cancel can finish without the flag, so the
        // flagged ones are at most the futures cancelled, never more.
        Demo.check(skipped + dropped <= o.cancelled && o.cancelled <= SHARDS - HIT_SHARD - 1
                        && Demo.attribute(t, REQUEST, CANCELLED) == o.cancelled,
                "the matches flagged c are futures the early exit cancelled: taken back before they began (no run "
                        + "time, no CPU) or running then (run to their end): " + skipped + " + " + dropped + " of " + o.cancelled);
        Demo.check(Demo.count(t, task.and(v -> shard(v) <= HIT_SHARD && !v.cancelled())) == HIT_SHARD + 1,
                "the matches up to the hit ran and were not cancelled");

        Demo.check(Demo.count(t, v -> v.name() == STORE && ran.test(v)) == WRITES, "every write ran");
        Demo.check(Demo.count(t, v -> v.name() == STORE && request.equals(v.thread()) && v.parent() == 0) >= 1,
                "with the writer busy and its queue full, a write ran inline on the request thread, a child of the root");
        Demo.check(Demo.count(t, v -> v.name() == STORE && !request.equals(v.thread()) && !"store-1".equals(v.thread())) == 0,
                "each write ran either on store-1 or inline on the request thread");

        Demo.check(Demo.count(t, v -> v.name() == NOTIFY && v.handoff()) == SUBSCRIBERS, "every notify is a task");
        Demo.check(o.refused == 1 && Demo.count(t, v -> v.name() == NOTIFY && v.cancelled() && v.durationNanos() == 0) == 1,
                "the notify the full pool refused is recorded rejected by the executor, and never ran");
        Demo.check(Demo.count(t, v -> v.healed() || v.misuse()) == 0, "the strict trace has no healed or misused span");

        Demo.say("POST /match: 12 shards on 3 work threads; shard " + HIT_SHARD + " held the hit; "
                + skipped + " shards were taken back before they began (c), " + dropped
                + " were running and ran to their end, their results dropped (c).");
        Demo.say("store: the writer was full, so one write ran on " + request + " itself; notify: the full pool refused one (c).");
    }

    /** The shard a match ran for (set by the task's body), or -1 for a match that never ran. */
    static long shard(Tempo.SpanView v) {
        return Demo.attribute(v, SHARD);
    }

    /** The largest value among the spans that match, or -1. */
    static long most(Tempo.Trace t, Predicate<Tempo.SpanView> which, ToLongFunction<Tempo.SpanView> value) {
        long[] most = {-1};
        t.forEachSpan(v -> {
            if (which.test(v)) most[0] = Math.max(most[0], value.applyAsLong(v));
        });
        return most[0];
    }
}
