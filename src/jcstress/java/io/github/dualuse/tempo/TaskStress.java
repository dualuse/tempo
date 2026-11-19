package io.github.dualuse.tempo;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;

import java.util.concurrent.atomic.AtomicLong;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE_INTERESTING;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

/**
 * jcstress tests of the task state: begin against cancel, release and the cancel of the task's future, for
 * drawn, folded and undrawn tasks, and the interrupt of {@code Future.cancel(true)}.
 */
public final class TaskStress {

    static final Tempo.Name N = Tempo.name("stress");

    /** A trace whose clock moves on every read, so a task that ran has a duration and one cancelled before it began has none. */
    static Tempo.Context context() {
        AtomicLong clock = new AtomicLong();
        return new Tempo.Context(new Tempo.Options().clock(clock::incrementAndGet).cpu(false));
    }

    /** The submitter's root lane of {@code ctx}, open, drawing tasks. */
    static Tempo.Lane root(Tempo.Context ctx) {
        ctx.level(Tempo.STEP);
        return open(ctx);
    }

    /** The submitter's root lane of {@code ctx}, open, at the level {@code ctx} has. */
    static Tempo.Lane open(Tempo.Context ctx) {
        Tempo.Lane root = ctx.register(new Tempo.Lane(ctx, new Tempo.ThreadSlot()));
        root.openRoot(N, 0, -1); // stays open
        return root;
    }

    /** Of the one task in the totals: 1 if it ran, plus 2 if its queue wait was counted, plus 4 for misuse. */
    static int counted(Tempo.Context ctx) {
        Tempo.Total t = ctx.total(N.id);
        return (int) t.count() + (t.waitSum() > 0 ? 2 : 0) + (ctx.misuses.get() > 0 ? 4 : 0);
    }

    /** The record of the one task: 1 run, 2 cancelled before it began, 3 cancelled while it ran, 0 else; plus 4 for misuse. */
    static int record(Tempo.Context ctx) {
        int[] record = {0};
        Tempo.Snapshot.of(ctx).forEachSpan(v -> {
            if (!v.handoff()) return;
            record[0] = v.neverStarted() ? 0 : !v.cancelled() ? 1 : v.durationNanos() == 0 ? 2 : 3;
        });
        return record[0] + (ctx.misuses.get() > 0 ? 4 : 0);
    }

    /**
     * A bare task's begin on a worker races release(): the task begins (recorded, or after release not)
     * and its lane retires once it ends. Result: begun (1), and every task lane retired (1).
     */
    @JCStressTest
    @Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "Begun, every task lane retired")
    @Outcome(expect = FORBIDDEN, desc = "Not begun, or a task lane left unretired")
    @State
    public static class Release {
        final Tempo.Context ctx = context();
        final Tempo.Task task = new Tempo.Task(root(ctx), N, null, 0);

        @Actor
        public void worker(II_Result r) {
            Tempo.Span s = task.tryBegin();
            r.r1 = s == null ? 0 : 1;
            if (s != null) s.close();
        }

        @Actor
        public void release() {
            ctx.release();
        }

        @Arbiter
        public void arbiter(II_Result r) {
            int retired = 1;
            for (Tempo.Lane l : ctx.lanesById()) if (l.id != 0 && !l.retired()) retired = 0;
            r.r2 = retired;
        }
    }

    /**
     * A worker runs a task while the submitter cancels it (an early exit). Exactly one of them wins: the
     * cancel, and the body never runs and the task is recorded as cancelled before it began; or the run,
     * and the body runs, the task is recorded as run and the cancel says it lost. Neither is misuse.
     * Result: the cancel won (1), the body ran (1), and the record (see {@link #record}).
     */
    @JCStressTest
    @Outcome(id = "1, 0, 2", expect = ACCEPTABLE, desc = "Cancelled before it began: the body did not run")
    @Outcome(id = "0, 1, 1", expect = ACCEPTABLE, desc = "Begun first: the body ran, the cancel lost")
    @Outcome(expect = FORBIDDEN, desc = "Both or neither won, the record disagrees with the body, or misuse")
    @State
    public static class Cancel {
        final Tempo.Context ctx = context();
        volatile int ran;
        final Tempo.RunnableTask task = new Tempo.RunnableTask(root(ctx), N, () -> ran = 1);

        @Actor
        public void worker() {
            task.run();
        }

        @Actor
        public void submitter(III_Result r) {
            r.r1 = task.cancel() ? 1 : 0;
        }

        @Arbiter
        public void arbiter(III_Result r) {
            r.r2 = ran;
            r.r3 = record(ctx);
        }
    }

    /**
     * Outside a trace the same race: exactly one of run and cancel wins, with no trace to record it.
     * Result: the cancel won (1), the body ran (1).
     */
    @JCStressTest
    @Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "Cancelled before it began: the body did not run")
    @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "Begun first: the body ran, the cancel lost")
    @Outcome(expect = FORBIDDEN, desc = "Both or neither won")
    @State
    public static class Untraced {
        volatile int ran;
        final Tempo.RunnableTask task = new Tempo.RunnableTask(null, N, () -> ran = 1);

        @Actor
        public void worker() {
            task.run();
        }

        @Actor
        public void submitter(II_Result r) {
            r.r1 = task.cancel() ? 1 : 0;
        }

        @Arbiter
        public void arbiter(II_Result r) {
            r.r2 = ran;
        }
    }

    /**
     * A worker runs a task while another thread cancels its future, {@code Future.cancel(false)}, which
     * cancels the task first. Before the begin the body never runs and the task is recorded cancelled
     * before it began; once it began the future is cancelled as a {@code FutureTask}'s is, and the span
     * is marked cancelled while it runs; once it ended, the cancel fails. The future is cancelled exactly
     * when the cancel says so. Result: the cancel succeeded (1), the body ran (1), and the record plus 8
     * if the future's state disagrees with the cancel's result.
     */
    @JCStressTest
    @Outcome(id = "1, 0, 2", expect = ACCEPTABLE, desc = "Cancelled before it began: the body did not run")
    @Outcome(id = "1, 1, 3", expect = ACCEPTABLE, desc = "Cancelled while the body ran: its span marked so")
    @Outcome(id = "1, 0, 3", expect = ACCEPTABLE_INTERESTING, desc = "Cancelled right after the begin: the body skipped, its span marked")
    @Outcome(id = "1, 1, 1", expect = ACCEPTABLE_INTERESTING, desc = "Cancelled as the body ended: the result dropped, the span closed unmarked")
    @Outcome(id = "0, 1, 1", expect = ACCEPTABLE, desc = "Ended first: the cancel failed")
    @Outcome(expect = FORBIDDEN, desc = "The body ran after a won cancel, the future disagrees, or misuse")
    @State
    public static class FutureCancel {
        final Tempo.Context ctx = context();
        volatile int ran;
        final Tempo.RunnableTask task = new Tempo.RunnableTask(root(ctx), N, () -> ran = 1);

        @Actor
        public void worker() {
            task.run();
        }

        @Actor
        public void canceller(III_Result r) {
            r.r1 = task.cancel(false) ? 1 : 0;
        }

        @Arbiter
        public void arbiter(III_Result r) {
            r.r2 = ran;
            r.r3 = record(ctx) + (task.isDone() && task.isCancelled() == (r.r1 == 1) ? 0 : 8);
        }
    }

    /**
     * The race of {@link Cancel} for a task the trace does not draw (it records spans only, the default):
     * nothing at the submit, its run or cancel goes to the totals. Result: the cancel won (1), the body
     * ran (1), and the totals (see {@link #counted}): the wait always, the run only if it ran.
     */
    @JCStressTest
    @Outcome(id = "1, 0, 2", expect = ACCEPTABLE, desc = "Cancelled before it began: its wait counted, no run")
    @Outcome(id = "0, 1, 3", expect = ACCEPTABLE, desc = "Begun first: its wait and its run counted")
    @Outcome(expect = FORBIDDEN, desc = "Both or neither won, the totals disagree with the body, or misuse")
    @State
    public static class UndrawnCancel {
        final Tempo.Context ctx = context();
        volatile int ran;
        final Tempo.RunnableTask task = new Tempo.RunnableTask(open(ctx), N, () -> ran = 1);

        @Actor
        public void worker() {
            task.run();
        }

        @Actor
        public void submitter(III_Result r) {
            r.r1 = task.cancel() ? 1 : 0;
        }

        @Arbiter
        public void arbiter(III_Result r) {
            r.r2 = ran;
            r.r3 = counted(ctx);
        }
    }

    /**
     * The race of {@link Cancel} for a task past the budget: no row at the submit, its run or cancel an
     * occurrence of its aggregate. Result as in {@link UndrawnCancel}.
     */
    @JCStressTest
    @Outcome(id = "1, 0, 2", expect = ACCEPTABLE, desc = "Cancelled before it began: its wait counted, no run")
    @Outcome(id = "0, 1, 3", expect = ACCEPTABLE, desc = "Begun first: its wait and its run counted")
    @Outcome(expect = FORBIDDEN, desc = "Both or neither won, the totals disagree with the body, or misuse")
    @State
    public static class FoldedCancel {
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options().clock(new AtomicLong()::incrementAndGet)
                .cpu(false).budget(0));
        volatile int ran;
        final Tempo.RunnableTask task = new Tempo.RunnableTask(root(ctx), N, () -> ran = 1);

        @Actor
        public void worker() {
            task.run();
        }

        @Actor
        public void submitter(III_Result r) {
            r.r1 = task.cancel() ? 1 : 0;
        }

        @Arbiter
        public void arbiter(III_Result r) {
            r.r2 = ran;
            r.r3 = counted(ctx);
        }
    }

    /**
     * {@code Future.cancel(true)} races the run: before the begin the task is cancelled and the body never
     * runs; once it began the runner is interrupted while it runs the body, never after {@code run()}
     * returned, so the interrupt cannot reach the thread's next task. The worker reads its interrupt flag
     * right after {@code run()}, without clearing it first, so an interrupt left over from an earlier
     * iteration shows too. Result: the cancel succeeded (1), the body ran (1), interrupted by the end of
     * the run (1) plus 2 if the task was cancelled before it began.
     */
    @JCStressTest
    @Outcome(id = "1, 0, 2", expect = ACCEPTABLE, desc = "Cancelled before it began: no body, no interrupt")
    @Outcome(id = "1, 1, 1", expect = ACCEPTABLE, desc = "Cancelled while the body ran: interrupted")
    @Outcome(id = "0, 1, 0", expect = ACCEPTABLE, desc = "Ended first: the cancel failed, no interrupt")
    @Outcome(id = "1, 0, 0", expect = ACCEPTABLE_INTERESTING, desc = "Cancelled before the runner was set: body skipped")
    @Outcome(id = "1, 0, 1", expect = ACCEPTABLE_INTERESTING, desc = "Cancelled after the runner was set: body skipped, interrupted")
    @Outcome(id = "1, 1, 0", expect = ACCEPTABLE_INTERESTING, desc = "Cancelled as the body ended: no runner left to interrupt")
    @Outcome(expect = FORBIDDEN, desc = "An interrupt without a won cancel, or a body run after the task was cancelled")
    @State
    public static class InterruptCancel {
        final Tempo.Context ctx = context();
        volatile int ran;
        final Tempo.RunnableTask task = new Tempo.RunnableTask(root(ctx), N, () -> ran = 1);

        @Actor
        public void worker(III_Result r) {
            task.run();
            r.r3 = Thread.interrupted() ? 1 : 0;
        }

        @Actor
        public void canceller(III_Result r) {
            r.r1 = task.cancel(true) ? 1 : 0;
        }

        @Arbiter
        public void arbiter(III_Result r) {
            r.r2 = ran;
            if (record(ctx) == 2) r.r3 += 2;
        }
    }
}
