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

/** jcstress tests of the lane state (IDLE, ACTIVE, RETIRED) and the commit protocol. */
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
            lane.openRoot(N, 0);
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
            lane.openRoot(N, 0);
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
            if (l != null && l.openRoot(N, 0) != Tempo.NOOP) {
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
     * Handoff.begin() on a worker races release(): the task begins and its lane retires once it ends, or
     * it is refused (NOOP). Result: begun (1) or not, and every task lane retired (1).
     */
    @JCStressTest
    @Outcome(id = {"1, 1", "0, 1"}, expect = ACCEPTABLE, desc = "Every task lane retired")
    @Outcome(expect = FORBIDDEN, desc = "A task lane left unretired")
    @State
    public static class Begin {
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options());
        final Tempo.Handoff handoff;

        {
            Tempo.Lane root = lane(ctx);
            root.openRoot(N, 0); // stays open: the submitter's root
            handoff = root.handoff(N, 0);
        }

        @Actor
        public void worker(II_Result r) {
            Tempo.Span s = handoff.begin();
            r.r1 = s == Tempo.NOOP ? 0 : 1;
            s.close();
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
}
