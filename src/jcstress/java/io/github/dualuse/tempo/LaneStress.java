package io.github.dualuse.tempo;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE_INTERESTING;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

/**
 * jcstress tests of the lane state (IDLE, ACTIVE, RETIRED), the commit protocol, the pooled arrays of a
 * retired lane, and the widening of what a trace records; {@link TaskStress} has the task state.
 */
public final class LaneStress {

    static final Tempo.Name N = Tempo.name("stress"), K = Tempo.name("stress-key");

    static Tempo.Lane lane(Tempo.Context ctx) {
        return ctx.register(new Tempo.Lane(ctx, new Tempo.ThreadSlot()));
    }

    /**
     * The owner records one span (open, flag, attribute, close) while a reader copies the lane. A copy
     * that found the lane in the same IDLE state before and after holds either nothing or the whole span.
     * Result: live (1) or not, rows, and for one row 1 closed + 2 flagged + 4 attribute.
     */
    @JCStressTest
    @Outcome(id = "0, 0, -1", expect = ACCEPTABLE, desc = "Idle before the span")
    @Outcome(id = "0, 1, 7", expect = ACCEPTABLE, desc = "Idle after the span, complete")
    @Outcome(id = "1, .*", expect = ACCEPTABLE_INTERESTING, desc = "Copied while recording: best effort, marked live")
    @Outcome(expect = FORBIDDEN, desc = "An idle copy of a partial span")
    @State
    public static class Commit {
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options());
        final Tempo.Lane lane = lane(ctx);

        @Actor
        public void owner() {
            lane.openRoot(N, 0, -1);
            lane.flag(0, Tempo.FAILED);
            lane.event(0, K.id << Tempo.KIND_BITS | Tempo.ATTR_LONG, 42);
            lane.close(0);
        }

        @Actor
        public void reader(III_Result r) {
            Tempo.Shape s = Tempo.Shape.of(lane);
            r.r1 = s.live ? 1 : 0;
            r.r2 = s.rows;
            r.r3 = s.rows == 0 ? -1 : (s.dur[0] != Tempo.Lane.OPEN ? 1 : 0)
                    + ((s.flags[0] & Tempo.FAILED) != 0 ? 2 : 0) + (s.from[1] - s.from[0] == 1 ? 4 : 0);
        }
    }

    /**
     * A late annotation by the owner (IDLE to ACTIVE and back) races release(). Whoever moves the lane,
     * it ends RETIRED with its chunks returned, exactly once. Result: the owner got in (1) or was
     * refused, and the lane retired with its chunk table cleared (1).
     */
    @JCStressTest
    @Outcome(id = {"1, 1", "0, 1"}, expect = ACCEPTABLE, desc = "Retired, by the owner or by release()")
    @Outcome(expect = FORBIDDEN, desc = "A lane left unretired, or retired with its chunks")
    @State
    public static class Retire {
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options());
        final Tempo.Lane lane = lane(ctx);

        {
            lane.openRoot(N, 0, -1);
            lane.close(0);
        }

        @Actor
        public void owner(II_Result r) {
            if (lane.enter()) {
                lane.rows[0].flags[0] |= Tempo.FAILED;
                lane.leave();
                r.r1 = 1;
            }
        }

        @Actor
        public void release() {
            ctx.release();
        }

        @Arbiter
        public void arbiter(II_Result r) {
            r.r2 = lane.retired() && lane.rows[0] == null ? 1 : 0;
        }
    }

    /**
     * A straggler registers a lane and opens a task in it while release() walks the registry. The task
     * either records in a lane that retires when it ends, or is refused. Result: recorded (1) or not, and
     * every lane retired (1).
     */
    @JCStressTest
    @Outcome(id = {"1, 1", "0, 1"}, expect = ACCEPTABLE, desc = "Every lane retired")
    @Outcome(expect = FORBIDDEN, desc = "A lane left unretired")
    @State
    public static class Register {
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options());
        final Tempo.Lane first = lane(ctx);
        Tempo.Lane late;

        @Actor
        public void straggler(II_Result r) {
            Tempo.Lane l = lane(ctx);
            late = l;
            if (l != null && l.openRoot(N, 0, -1) != Tempo.NOOP) {
                l.close(0);
                r.r1 = 1;
            }
        }

        @Actor
        public void release() {
            ctx.release();
        }

        @Arbiter
        public void arbiter(II_Result r) {
            r.r2 = first.retired() && (late == null || late.retired()) ? 1 : 0;
        }
    }

    /**
     * {@code records(STEP)} races a step opened on another thread. The step reads the level in the same
     * atomic step that fixes it: either the widening came first and the step is drawn, or the step came
     * first and the widening is refused. Result: widened (1), the step drawn (1).
     */
    @JCStressTest
    @Outcome(id = {"1, 1", "0, 0"}, expect = ACCEPTABLE, desc = "Widened before a drawn step, or refused after an undrawn one")
    @Outcome(expect = FORBIDDEN, desc = "Widened after an undrawn step, or refused before a drawn one")
    @State
    public static class WidenStep {
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options().cpu(false));
        final Tempo.Lane lane = lane(ctx);

        {
            lane.openRoot(N, 0, -1);
        }

        @Actor
        public void widen(II_Result r) {
            synchronized (ctx) {
                r.r1 = ctx.widen(Tempo.STEP) ? 1 : 0;
            }
        }

        @Actor
        public void step(II_Result r) {
            r.r2 = lane.openStep(N, 0).row != Tempo.Lane.FRAME ? 1 : 0;
        }
    }

    /** {@link WidenStep} for a tick, which is recorded only at {@code TICK}. Result: widened (1), the tick recorded (1). */
    @JCStressTest
    @Outcome(id = {"1, 1", "0, 0"}, expect = ACCEPTABLE, desc = "Widened before a recorded tick, or refused after an unrecorded one")
    @Outcome(expect = FORBIDDEN, desc = "Widened after an unrecorded tick, or refused before a recorded one")
    @State
    public static class WidenTick {
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options().cpu(false));
        final Tempo.Lane lane = lane(ctx);

        {
            lane.openRoot(N, 0, -1);
        }

        @Actor
        public void widen(II_Result r) {
            synchronized (ctx) {
                r.r1 = ctx.widen(Tempo.TICK) ? 1 : 0;
            }
        }

        @Actor
        public void tick(II_Result r) {
            r.r2 = lane.openTick(N, 0) != Tempo.NOOP ? 1 : 0;
        }
    }

    /**
     * A retired lane's arrays go to its owner's next lane. The owner records 5 spans of N in a lane of a
     * second trace, which takes the arrays of the first trace's lane if release() has retired it by then,
     * while a reader totals N in the first trace, which has 3. The reader sees the first trace's 3 or,
     * released, none: never the second trace's spans. Result: the reader's count, the second trace's
     * count, and whether the second lane took the first one's arrays (1).
     */
    @JCStressTest
    @Outcome(id = {"3, 5, 0", "0, 5, 0"}, expect = ACCEPTABLE, desc = "The first trace's count, or none once released")
    @Outcome(id = {"3, 5, 1", "0, 5, 1"}, expect = ACCEPTABLE_INTERESTING, desc = "The same, the arrays reused")
    @Outcome(expect = FORBIDDEN, desc = "The reader saw the second trace's spans, or the second trace lost some")
    @State
    public static class Reuse {
        final Tempo.ThreadSlot slot = new Tempo.ThreadSlot();
        final Tempo.Context first = new Tempo.Context(new Tempo.Options()), second = new Tempo.Context(new Tempo.Options());
        final Tempo.Lane a = first.register(new Tempo.Lane(first, slot));
        Tempo.Lane b;

        {
            a.openRoot(K, 0, -1);
            for (int i = 0; i < 3; i++) {
                a.openSpan(N, 0);
                a.close(1);
            }
            a.close(0);
        }

        @Actor
        public void owner() {
            Tempo.Lane l = second.register(new Tempo.Lane(second, slot));
            l.openRoot(K, 0, -1);
            for (int i = 0; i < 5; i++) {
                l.openSpan(N, 0);
                l.close(1);
            }
            l.close(0);
            b = l;
        }

        @Actor
        public void release() {
            first.release();
        }

        @Actor
        public void reader(III_Result r) {
            r.r1 = (int) first.total(N.id).count();
        }

        @Arbiter
        public void arbiter(III_Result r) {
            r.r2 = (int) second.total(N.id).count();
            r.r3 = b.rows == a.rows ? 1 : 0;
        }
    }
}
