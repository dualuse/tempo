package io.github.dualuse.tempo.demo.medium;

import io.github.dualuse.tempo.Tempo;
import io.github.dualuse.tempo.demo.Demo;

import java.util.ArrayList;
import java.util.List;
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
 * <p>It shows a {@code Handoff} captured at submit and its queue wait, a blocking join marked
 * {@code waiting()}, CPU time per pool task at {@code STEP}, and the three ways a handoff ends other
 * than running on a pool thread: inline (caller-runs), rejected ({@code reject()}) and skipped by an
 * early exit ({@code cancel()}, which says whether it won the race with the worker).
 *
 * <pre>
 * ./gradlew -q demo -Pdemo=WorkerPool                         the story and the response
 * ./gradlew -q demo -Pdemo=WorkerPool | tools/tempo           lanes: work-1..3, store-1, notify-1, main
 * ./gradlew -q demo -Pdemo=WorkerPool | tools/tempo --tree    wait, cpu and off-cpu per task
 * </pre>
 *
 * Look for: {@code match} tasks on {@code work-1..3} whose queue wait ({@code ░}) grows round by round;
 * {@code load} inside the odd shards, which run long off CPU; {@code join} and {@code flush} drawn as
 * blocking waits on {@code main}; the last {@code match} tasks flagged {@code c}, skipped after the hit;
 * one {@code store} on {@code main} with no queue wait; one {@code notify} flagged {@code c}, refused.
 * Or open the printed {@code Tempo-Link} in a browser.
 */
public final class WorkerPool {
    static final Tempo.Name REQUEST = Tempo.name("POST /match"), MATCH = Tempo.name("match"),
            LOAD = Tempo.name("load"), JOIN = Tempo.name("join"), STORE = Tempo.name("store"),
            NOTIFY = Tempo.name("notify"), FLUSH = Tempo.name("flush");
    static final Tempo.Name SHARD = Tempo.name("shard"), QUEUED = Tempo.name("queued"),
            HIT = Tempo.name("hit"), SKIPPED = Tempo.name("skipped");

    static final int SHARDS = 12, THREADS = 3, HIT_SHARD = 4, SUBSCRIBERS = 3, WRITES = 3;
    static final long TASK_MS = 15, LOAD_MS = 14, WRITE_MS = 10, NOTIFY_MS = 10;

    public static void main(String[] args) throws Exception {
        // Pools live as long as the service, not the request; the request only submits to them.
        ThreadPoolExecutor work = Demo.pool("work", THREADS);
        work.prestartAllCoreThreads(); // idle and waiting, as in a service that has run for a while
        ThreadPoolExecutor store = Demo.pool("store", 1, 1, new ThreadPoolExecutor.CallerRunsPolicy());
        ThreadPoolExecutor notify = Demo.pool("notify", 1, 1, new ThreadPoolExecutor.AbortPolicy());
        try {
            // STEP (see LevelsAndOptIn): the load spans are recorded, and every task begun on a pool
            // thread (a lane root) samples its thread's CPU time, so the response shows CPU next to wall
            // time per task.
            Tempo.Trace t = Tempo.trace(REQUEST, new Tempo.Options().strict(true)).records(Tempo.STEP);
            try (t) {
                match(t, work, store, notify);
            }
            check(t, Thread.currentThread().getName());
            Demo.respond(t);
        } finally {
            Demo.shutdown(work, store, notify);
        }
    }

    /** The request handler. */
    static void match(Tempo.Span s, ThreadPoolExecutor work, ThreadPoolExecutor store, ThreadPoolExecutor notify)
            throws Exception {
        // Scatter. The handoff is taken here, at submit, because the queue wait runs from submit to the
        // moment a pool thread begins the task; taken inside the task it could not see the queue. It is
        // the one object Tempo allocates while recording (32 bytes). Its attributes describe the submit:
        // which shard, and how many tasks were already waiting in the pool's queue.
        List<Future<Boolean>> tasks = new ArrayList<>();
        List<Tempo.Handoff> handoffs = new ArrayList<>();
        for (int shard = 0; shard < SHARDS; shard++) {
            int n = shard;
            Tempo.Handoff h = s.handoff(MATCH).set(SHARD, n).set(QUEUED, work.getQueue().size());
            // callable() wraps the task in h.begin() and close(): the span opens on the pool thread.
            handoffs.add(h);
            tasks.add(work.submit(h.callable(() -> matchShard(n))));
        }

        // Gather. The request thread blocks here; waiting() tells the viewers so: they draw the join as
        // a wait, not as work of main, and follow the critical path across into the task it waits for.
        int hit = -1;
        Tempo.Span join = s.span(JOIN).waiting();
        try (join) {
            for (int n = 0; n < SHARDS && hit < 0; n++) {
                if (tasks.get(n).get()) hit = n;
            }
        }

        // Early exit: the hit is all the request needs. Each later shard's handoff is cancelled. Cancel and
        // begin race for a handoff and exactly one wins, so cancel() returning true proves the task never
        // began, nor will: callable() skips it even if a worker takes it this very moment, and it shows as
        // cancelled before it began (flag c), with no run time. false means it is running: it is left to
        // finish, past the join, before the response. Future.cancel completes the future for anyone who
        // waits on it. The cancelled tasks stay in the queue at no cost; ThreadPoolExecutor.remove would only
        // free the slots of a bounded queue sooner. Handoff.NOOP (an untraced request) takes no part in the
        // race and its cancel() is always false, so there remove() decides it: it takes the task out of the
        // queue only if no worker took it. Future.cancel alone would succeed on a running shard too, whose
        // body runs on, and count it as skipped.
        int skipped = 0;
        for (int n = hit + 1; n < SHARDS; n++) {
            Tempo.Handoff h = handoffs.get(n);
            Future<Boolean> task = tasks.get(n);
            boolean won = h == Tempo.Handoff.NOOP ? work.remove((Runnable) task) : h.cancel();
            if (won) {
                task.cancel(false);
                skipped++;
            }
        }
        s.set(HIT, hit).set(SKIPPED, skipped);

        List<Future<?>> pending = new ArrayList<>();

        // The writer pool has one thread and room for one queued write. When both are taken,
        // CallerRunsPolicy runs the write right here on the request thread: in Tempo-Timing the handoff
        // becomes a plain child of the request, with no queue wait, and the request is slowed down
        // (backpressure). No span or handoff may be opened on this thread between handoff() and submit(),
        // or the write is no longer recognised as inline; attributes are fine.
        for (int i = 0; i < WRITES; i++) {
            Tempo.Handoff h = s.handoff(STORE).set(QUEUED, store.getQueue().size());
            pending.add(store.submit(h.runnable(() -> Demo.io(WRITE_MS))));
        }

        // The notifier refuses work when it is full (AbortPolicy). The refused task never runs, so the
        // request marks its handoff rejected; otherwise it would show as still waiting in a queue.
        for (int i = 0; i < SUBSCRIBERS; i++) {
            Tempo.Handoff h = s.handoff(NOTIFY).set(QUEUED, notify.getQueue().size());
            try {
                pending.add(notify.submit(h.runnable(() -> Demo.io(NOTIFY_MS))));
            } catch (RejectedExecutionException full) {
                h.reject();
            }
        }

        Tempo.Span flush = s.span(FLUSH).waiting();
        try (flush) {
            for (Future<?> f : pending) f.get();
        }
    }

    /** One shard, on a pool thread: Tempo.current() is the task's span, so the load nests under it. */
    static boolean matchShard(int shard) {
        if (shard % 2 == 0) {
            Demo.cpu(TimeUnit.MILLISECONDS.toMicros(TASK_MS));          // in memory: compute only
        } else {
            Tempo.Span load = Tempo.current().step(LOAD);
            try (load) {
                Demo.io(LOAD_MS);                                       // cold: blocks, off CPU
            }
            Demo.cpu(TimeUnit.MILLISECONDS.toMicros(TASK_MS - LOAD_MS));
        }
        return shard == HIT_SHARD;
    }

    // ------------------------------------------------------------------ what the trace shows

    static void check(Tempo.Trace t, String request) {
        Predicate<Tempo.SpanView> ran = v -> v.handoff() && !v.neverStarted() && !v.cancelled();
        Predicate<Tempo.SpanView> task = v -> v.name() == MATCH && ran.test(v);
        long ms = TimeUnit.MILLISECONDS.toNanos(1);

        Demo.check(Demo.count(t, v -> v.name() == MATCH && v.handoff()) == SHARDS,
                "every submitted shard is a match handoff, the skipped ones included");
        Demo.check(Demo.count(t, task.and(v -> v.thread().startsWith("work-"))) == Demo.count(t, task),
                "every match that ran, ran on a work-N thread");
        // Shards 0..2 went straight to the three idle threads; every later one sat in the queue at
        // least until one of those finished, TASK_MS after the first submit. (On a cold JVM the very
        // first task also shows a small wait: class loading, not the queue.)
        Demo.check(Demo.count(t, task.and(v -> shard(v) >= THREADS && v.waitNanos() < 2 * ms)) == 0
                        && Demo.count(t, task.and(v -> shard(v) >= THREADS)) >= HIT_SHARD + 1 - THREADS,
                "the matches submitted beyond the pool size waited in the queue for milliseconds");

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

        long skipped = Demo.attribute(t, REQUEST, SKIPPED);
        Demo.check(Demo.attribute(t, REQUEST, HIT) == HIT_SHARD, "the request found its hit in shard " + HIT_SHARD);
        Demo.check(skipped >= 1, "the early exit cancelled at least one match before it began");
        Demo.check(Demo.count(t, v -> v.name() == MATCH && v.cancelled() && v.durationNanos() == 0
                        && v.cpuNanos() < 0 && shard(v) > HIT_SHARD) == skipped,
                "each skipped match is cancelled before it began (c, not n): no run time, no CPU");

        Demo.check(Demo.count(t, v -> v.name() == STORE && ran.test(v)) == WRITES, "every write ran");
        Demo.check(Demo.count(t, v -> v.name() == STORE && request.equals(v.thread()) && v.parent() == 0) >= 1,
                "with the writer busy and its queue full, a write ran inline on the request thread, a child of the root");
        Demo.check(Demo.count(t, v -> v.name() == STORE && !request.equals(v.thread()) && !"store-1".equals(v.thread())) == 0,
                "each write ran either on store-1 or inline on the request thread");

        Demo.check(Demo.count(t, v -> v.name() == NOTIFY && v.handoff()) == SUBSCRIBERS, "every notify is a handoff");
        Demo.check(Demo.count(t, v -> v.name() == NOTIFY && v.cancelled() && v.durationNanos() == 0) == 1,
                "the notify the full pool refused is rejected and never ran");
        Demo.check(Demo.count(t, v -> v.healed() || v.misuse()) == 0, "the strict trace has no healed or misused span");

        Demo.say("POST /match: 12 shards on 3 work threads; shard " + HIT_SHARD + " held the hit, "
                + skipped + " shards were cancelled before they began (c); those already running finished after the join.");
        Demo.say("store: the writer was full, so one write ran on " + request + " itself; notify: the full pool refused one (c).");
    }

    /** The shard a match was submitted for (set on the handoff at submit), or -1. */
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
