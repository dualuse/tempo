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

/** jcstress tests of the task state: begin against cancel, release and the cancel of the task's future. */
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
        Tempo.Lane root = ctx.register(new Tempo.Lane(ctx, new Tempo.ThreadSlot()));
        root.openRoot(N, 0, -1); // stays open
        return root;
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
}
