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
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

/**
 * jcstress tests of the scope state of self-contained blocks (nested traces): the level a block fixes
 * against widenings of the block and of the trace, its sub-budget taken from the trace's by lanes on two
 * threads and given back, the scope a task carries to the thread where it runs (undrawn, drawn and
 * folded), a late task against the reuse of its submitter's table by another trace, and a block's totals
 * read while its task still records.
 */
public final class BlockStress {

    static final Tempo.Name N = Tempo.name("stress"), B = Tempo.name("stress-block");

    /** An open root lane of {@code ctx} with a block open on it, of {@code options}. */
    static Tempo.Trace block(Tempo.Context ctx, Tempo.Lane lane, Tempo.Options options) {
        lane.openRoot(N, 0, -1);
        Tempo.Trace t = new Tempo.Trace(lane, ctx, lane.depth, lane.scope, options);
        lane.nest(t, B, 0);
        return t;
    }

    static Tempo.Lane lane(Tempo.Context ctx) {
        return ctx.register(new Tempo.Lane(ctx, new Tempo.ThreadSlot()));
    }

    /** A trace whose clock moves on every read, without CPU time, drawing its tasks, with {@code budget}. */
    static Tempo.Context drawing(int budget) {
        Tempo.Context ctx = new Tempo.Context(new Tempo.Options().clock(new AtomicLong()::incrementAndGet).cpu(false).budget(budget));
        ctx.level(Tempo.STEP);
        return ctx;
    }

    /**
     * The block's own widening races the first step in it: either the widening came first and the step is
     * drawn, or the step came first and the widening is refused. Either way the step fixed the trace's
     * level too. Result: widened (1), the step drawn (1), the trace's level fixed (1).
     */
    @JCStressTest
    @Outcome(id = {"1, 1, 1", "0, 0, 1"}, expect = ACCEPTABLE, desc = "Widened before a drawn step, or refused after an undrawn one")
    @Outcome(expect = FORBIDDEN, desc = "Widened after an undrawn step, refused before a drawn one, or the trace left unfixed")
    @State
    public static class WidenBlock {
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options().cpu(false));
        final Tempo.Lane lane = lane(ctx);
        final Tempo.Trace block = block(ctx, lane, new Tempo.Options());

        @Actor
        public void widen(III_Result r) {
            synchronized (ctx) {
                r.r1 = block.widen(Tempo.STEP) ? 1 : 0;
            }
        }

        @Actor
        public void step(III_Result r) {
            r.r2 = lane.openStep(N, 0).row != Tempo.Lane.FRAME ? 1 : 0;
        }

        @Arbiter
        public void arbiter(III_Result r) {
            r.r3 = (ctx.root.mode & Tempo.Context.SEEN) != 0 ? 1 : 0;
        }
    }

    /**
     * The trace's widening races the first step in a block that asked for nothing: the step fixes the
     * trace's level before its block's, so either the trace widened first and the block draws at its
     * level, or the step came first and the trace's widening is refused. Result: widened (1), drawn (1).
     */
    @JCStressTest
    @Outcome(id = {"1, 1", "0, 0"}, expect = ACCEPTABLE, desc = "Widened before a drawn step, or refused after an undrawn one")
    @Outcome(expect = FORBIDDEN, desc = "The trace widened after an undrawn step in its block, or was refused before a drawn one")
    @State
    public static class WidenTrace {
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options().cpu(false));
        final Tempo.Lane lane = lane(ctx);
        final Tempo.Trace block = block(ctx, lane, new Tempo.Options());

        @Actor
        public void widen(II_Result r) {
            synchronized (ctx) {
                r.r1 = ctx.root.widen(Tempo.STEP) ? 1 : 0;
            }
        }

        @Actor
        public void step(II_Result r) {
            r.r2 = lane.openStep(N, 0).row != Tempo.Lane.FRAME ? 1 : 0;
        }
    }

    /**
     * A lane of the block takes its sub-budget (2) twice, one at a time, while a lane of the trace outside
     * the block takes from the trace's budget (2). The block never draws more than the trace has left and
     * no credit is lost or made. Result: what the block's lane got, what the trace's lane got, and whether
     * both budgets balance (1).
     */
    @JCStressTest
    @Outcome(id = {"2, 0, 1", "1, 1, 1"}, expect = ACCEPTABLE, desc = "The block took the trace's last credits, or shared them")
    @Outcome(expect = FORBIDDEN, desc = "More handed out than the trace had, or a budget out of balance")
    @State
    public static class SubBudget {
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options().budget(2));
        final Tempo.Lane lane = lane(ctx);
        final Tempo.Trace block = block(ctx, lane, new Tempo.Options().budget(2));
        int inBlock, outside;

        @Actor
        public void blockLane() {
            inBlock = block.credits() + block.credits();
        }

        @Actor
        public void traceLane() {
            outside = ctx.root.credits();
        }

        @Arbiter
        public void arbiter(III_Result r) {
            r.r1 = inBlock;
            r.r2 = outside;
            r.r3 = ctx.unspent() + inBlock + outside == 2 && block.unspent() + inBlock == 2 ? 1 : 0;
        }
    }

    /**
     * Two workers run, each on its own thread, a task submitted in the block and one submitted after it
     * closed: each task carries its scope to the lane it runs in, so the block counts its own task only
     * and the trace both. Result: the block's count, the trace's count.
     */
    @JCStressTest
    @Outcome(id = "1, 2", expect = ACCEPTABLE, desc = "The block counts its task, the trace both")
    @Outcome(expect = FORBIDDEN, desc = "A task counted in the wrong scope, or lost")
    @State
    public static class TaskScope {
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options().clock(new AtomicLong()::incrementAndGet).cpu(false));
        final Tempo.Lane lane = lane(ctx);
        final Tempo.Trace block = block(ctx, lane, new Tempo.Options());
        final Tempo.RunnableTask inside = new Tempo.RunnableTask(lane, N, () -> { });
        final Tempo.RunnableTask after;

        {
            lane.close(block.depth);
            after = new Tempo.RunnableTask(lane, N, () -> { });
        }

        @Actor
        public void first() {
            inside.run();
        }

        @Actor
        public void second() {
            after.run();
        }

        @Arbiter
        public void arbiter(II_Result r) {
            r.r1 = (int) ctx.total(N.id, block).count();
            r.r2 = (int) ctx.total(N.id).count(); // the root, still open, is not counted
        }
    }

    /**
     * A lane of the block takes a block of its sub-budget (2) while the trace has 1 credit left, so it gives
     * back what the trace could not hand it, and races another lane of the block refunding the 2 it held:
     * the give-back and the refund both land, whatever the order. Result: what the block's lane got (1 if
     * the trace had only 1 left, 2 after the refund), and whether both budgets balance (1).
     */
    @JCStressTest
    @Outcome(id = {"1, 1", "2, 1"}, expect = ACCEPTABLE, desc = "Took before the refund (one, the rest given back) or after it")
    @Outcome(expect = FORBIDDEN, desc = "A credit lost or made by the give-back racing the refund")
    @State
    public static class SubBudgetRefund {
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options().budget(3));
        final Tempo.Lane lane = lane(ctx);
        final Tempo.Trace block = block(ctx, lane, new Tempo.Options().budget(128)); // takes 2 at a time
        final int held = block.credits(); // the other lane's: the trace has 1 left
        int got;

        @Actor
        public void take() {
            got = block.credits();
        }

        @Actor
        public void refund() {
            block.refund(held);
        }

        @Arbiter
        public void arbiter(II_Result r) {
            r.r1 = got;
            r.r2 = held == 2 && ctx.unspent() + got == 3 && block.unspent() + got == 128 ? 1 : 0;
        }
    }

    /**
     * {@link TaskScope} for drawn tasks, whose scope travels in the task's own link field and whose run is
     * linked to its record in the block: the block counts its task, wherever it ran, and the trace both.
     * Result: the block's count, the trace's count.
     */
    @JCStressTest
    @Outcome(id = "1, 2", expect = ACCEPTABLE, desc = "The block counts its task, the trace both")
    @Outcome(expect = FORBIDDEN, desc = "A task counted in the wrong scope, or lost")
    @State
    public static class DrawnTaskScope {
        final Tempo.Context ctx = drawing(512);
        final Tempo.Lane lane = lane(ctx);
        final Tempo.Trace block = block(ctx, lane, new Tempo.Options());
        final Tempo.RunnableTask inside = new Tempo.RunnableTask(lane, N, () -> { });
        final Tempo.RunnableTask after;

        {
            lane.close(block.depth);
            after = new Tempo.RunnableTask(lane, N, () -> { });
        }

        @Actor
        public void first() {
            inside.run();
        }

        @Actor
        public void second() {
            after.run();
        }

        @Arbiter
        public void arbiter(II_Result r) {
            r.r1 = (int) ctx.total(N.id, block).count();
            r.r2 = (int) ctx.total(N.id).count(); // the root, still open, is not counted
        }
    }

    /**
     * A folded task of the block, whose scope is its TASKS entry's and whose link field is the submitter's
     * waiting list, runs on a worker while the submitter keeps submitting folded tasks in the block, so the
     * list is cleaned up (links rewritten) and the table grows under the worker's read. The task still runs
     * in the block. Result: the block's count, the trace's count of tasks that ran.
     */
    @JCStressTest
    @Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "The folded task ran in the block")
    @Outcome(expect = FORBIDDEN, desc = "It ran in the wrong scope, or was lost")
    @State
    public static class FoldedTaskScope {
        static final Tempo.Name[] MORE = new Tempo.Name[40];

        static {
            for (int i = 0; i < MORE.length; i++) MORE[i] = Tempo.name("stress-more-" + i); // one entry each: the table grows
        }

        final Tempo.Context ctx = drawing(0);
        final Tempo.Lane lane = lane(ctx);
        final Tempo.Trace block = block(ctx, lane, new Tempo.Options());
        final Tempo.RunnableTask first = new Tempo.RunnableTask(lane, N, () -> { });

        @Actor
        public void worker() {
            first.run();
        }

        @Actor
        public void submitter() { // the lane's one writer from now on
            for (Tempo.Name n : MORE) new Tempo.RunnableTask(lane, n, () -> { });
        }

        @Arbiter
        public void arbiter(II_Result r) {
            r.r1 = (int) ctx.total(N.id, block).count();
            r.r2 = (int) ctx.total(N.id).count();
        }
    }

    /**
     * A folded task of a block begins as its trace is released, and the released submitter's arrays go to
     * its thread's next lane, of another trace with a strict block whose first TASKS entry takes the same
     * place. Whatever the worker reads of the submitter's table, it never takes the other trace's scope: a
     * misuse in the task never throws there nor counts there. Result: the task threw (1), the other trace's
     * misuses.
     */
    @JCStressTest
    @Outcome(id = "0, 0", expect = ACCEPTABLE, desc = "The late task stayed in its own trace")
    @Outcome(expect = FORBIDDEN, desc = "The late task took the other trace's strict scope")
    @State
    public static class LateTaskScope {
        final Tempo.ThreadSlot slot = new Tempo.ThreadSlot();
        final Tempo.Context first = drawing(0), second = drawing(0);
        final Tempo.Lane sub = first.register(new Tempo.Lane(first, slot));
        final Tempo.RunnableTask late;

        {
            sub.openRoot(N, 0, -1);
            Tempo.Trace block = new Tempo.Trace(sub, first, sub.depth, sub.scope, new Tempo.Options());
            sub.nest(block, B, 0);
            late = new Tempo.RunnableTask(sub, N, () -> Tempo.span((Tempo.Name) null).close());
            sub.close(block.depth);
            sub.close(0); // idle: the release retires it at once
        }

        @Actor
        public void worker(II_Result r) {
            try {
                late.run();
            } catch (AssertionError e) {
                r.r1 = 1;
            }
        }

        @Actor
        public void release() {
            first.release();
            Tempo.Lane next = second.register(new Tempo.Lane(second, slot)); // takes the retired lane's arrays
            next.openRoot(N, 0, -1);
            Tempo.Trace strict = new Tempo.Trace(next, second, next.depth, next.scope, new Tempo.Options().strict(true));
            next.nest(strict, B, 0);
            new Tempo.RunnableTask(next, N, () -> { }); // TASKS entry 0, in the strict block
        }

        @Arbiter
        public void arbiter(II_Result r) {
            r.r2 = second.misuses.get();
        }
    }

    /**
     * A block's totals, read in place and through a snapshot, while its task records a span in it on
     * another thread: each read sees the span ended or not, never fails, and once the task is done both
     * count it. Result: the live count, the snapshot's count, the count once done.
     */
    @JCStressTest
    @Outcome(id = {"0, 0, 1", "0, 1, 1", "1, 0, 1", "1, 1, 1"}, expect = ACCEPTABLE, desc = "Each read before or after the span ended")
    @Outcome(expect = FORBIDDEN, desc = "A read saw more than there is, or the span was lost")
    @State
    public static class TotalWhileRecording {
        static final Tempo.Name S = Tempo.name("stress-span");
        final Tempo.Context ctx = drawing(512);
        final Tempo.Lane lane = lane(ctx);
        final Tempo.Trace block = block(ctx, lane, new Tempo.Options());
        final Tempo.RunnableTask task = new Tempo.RunnableTask(lane, N, () -> Tempo.span(S).close());

        @Actor
        public void worker() {
            task.run();
        }

        @Actor
        public void reader(III_Result r) {
            r.r1 = (int) ctx.total(S.id, block).count();
            r.r2 = (int) block.snapshot().total(S).count();
        }

        @Arbiter
        public void arbiter(III_Result r) {
            r.r3 = (int) Math.min(ctx.total(S.id, block).count(), block.snapshot().total(S).count());
        }
    }
}
