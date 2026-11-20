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
 * threads, and the scope a task carries to the thread where it runs.
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
}
