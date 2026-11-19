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

/** jcstress tests of the handle pool: a lane retiring hands its handles back, and a stale use races it. */
public final class HandleStress {

    static final Tempo.Name N = Tempo.name("stress"), K = Tempo.name("stress-key"), M = Tempo.name("stress-add");

    static Tempo.Lane lane(Tempo.Context ctx, Tempo.ThreadSlot slot) {
        return ctx.register(new Tempo.Lane(ctx, slot));
    }

    /** How often {@code h} is in the slot's pool, plus one if it was taken. */
    static int held(Tempo.ThreadSlot slot, Tempo.Span h, Tempo.Span taken) {
        int n = taken == h ? 1 : 0;
        for (int i = slot.pooled(); i > 0; i--) if (slot.take() == h) n++;
        return n;
    }

    /**
     * The owner calls {@code set} on a closed span while release() retires its lane and pools the handle;
     * then the owner takes a handle from its pool. The set either lands before the lane retires, or is
     * refused and counted as misuse, never lost; a handle comes out of the pool only once detached; and the
     * lane retires with the handle pooled exactly once. Result: 1 recorded or 2 refused and counted; a
     * detached handle taken (1) or none yet (0); retired with the handle pooled once (1).
     */
    @JCStressTest
    @Outcome(id = {"1, 0, 1", "2, 0, 1"}, expect = ACCEPTABLE, desc = "Recorded or refused; the pool not yet refilled")
    @Outcome(id = {"1, 1, 1", "2, 1, 1"}, expect = ACCEPTABLE_INTERESTING, desc = "Recorded or refused; a pooled handle taken")
    @Outcome(expect = FORBIDDEN, desc = "A set lost or counted twice, a bound handle pooled, or a handle pooled twice")
    @State
    public static class StaleSet {
        final Tempo.ThreadSlot slot = new Tempo.ThreadSlot();
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options());
        final Tempo.Lane lane = lane(ctx, slot);
        final Tempo.Span root, h;
        Tempo.Span taken;

        {
            root = lane.openRoot(N, 0, -1);
            h = lane.openSpan(N, 0);
            lane.close(1);
            lane.close(0);
        }

        @Actor
        public void owner(III_Result r) {
            h.owner = Thread.currentThread(); // the actor thread stands in for the lane's owner
            h.set(K, 42);
            Tempo.Span x = slot.take();
            taken = x;
            r.r2 = x == null ? 0 : x.lane == Tempo.Lane.POOLED && x.stamp() == 0 && (x == h || x == root) ? 1 : -1;
        }

        @Actor
        public void release() {
            ctx.release();
        }

        @Arbiter
        public void arbiter(III_Result r) {
            r.r1 = lane.eventCount + 2 * ctx.misuses.get();
            boolean pooled = h.lane == Tempo.Lane.POOLED && h.stamp() == 0 && held(slot, h, taken) == 1;
            r.r3 = lane.retired() && pooled ? 1 : 0;
        }
    }

    /**
     * Another thread calls {@code add} through a handle of a released trace while the owner issues that
     * handle to a span of its next trace. The add is either counted as misuse (the handle was still pooled)
     * or, issued first, reaches the new span's trace, undetected by design; never both, never neither.
     * Result: the handle issued again (1); the add in the new trace's total (1); misuse counted (1).
     */
    @JCStressTest
    @Outcome(id = "1, 0, 1", expect = ACCEPTABLE, desc = "Stale: counted as misuse, nothing added")
    @Outcome(id = "1, 1, 0", expect = ACCEPTABLE_INTERESTING, desc = "Issued first: the add reaches the new trace")
    @Outcome(expect = FORBIDDEN, desc = "Lost, or added and counted, or the handle not reused")
    @State
    public static class StaleAdd {
        final Tempo.ThreadSlot slot = new Tempo.ThreadSlot();
        final Tempo.Context first = new Tempo.Context(new Tempo.Options()), second = new Tempo.Context(new Tempo.Options());
        final Tempo.Lane a = lane(first, slot), b = lane(second, slot);
        final Tempo.Span h;
        Tempo.Span issued;

        {
            a.openRoot(N, 0, -1);
            h = a.openSpan(N, 0);
            a.close(1);
            a.close(0);
            b.openRoot(N, 0, -1); // the pool is empty yet: a new handle
            first.release();      // a retires: h is pooled, first to be taken
        }

        @Actor
        public void owner() {
            issued = b.openSpan(N, 0);
        }

        @Actor
        public void other() {
            h.add(M, 5);
        }

        @Arbiter
        public void arbiter(III_Result r) {
            r.r1 = issued == h ? 1 : 0;
            r.r2 = (int) second.total(M.id).count();
            r.r3 = first.misuses.get() + second.misuses.get();
        }
    }

    /**
     * {@link StaleSet} for a phase: the owner sets an attribute on an ended phase while release() retires
     * the lane and pools the phase's handle. The set lands before the lane retires, or is refused and
     * counted as misuse, never lost; the handle ends pooled and detached. Result: 1 recorded or 2 refused
     * and counted; the handle detached (1).
     */
    @JCStressTest
    @Outcome(id = {"1, 1", "2, 1"}, expect = ACCEPTABLE, desc = "Recorded or refused; the handle pooled")
    @Outcome(expect = FORBIDDEN, desc = "A set lost or counted twice, or the handle left bound")
    @State
    public static class StalePhaseSet {
        final Tempo.ThreadSlot slot = new Tempo.ThreadSlot();
        final Tempo.Context ctx = new Tempo.Context(new Tempo.Options());
        final Tempo.Lane lane = lane(ctx, slot);
        final Tempo.Phase p;

        {
            lane.openRoot(N, 0, -1);
            lane.openSpan(N, 0);
            p = lane.phase(1, N, 0);
            lane.close(1);
            lane.close(0);
        }

        @Actor
        public void owner() {
            p.owner = Thread.currentThread(); // the actor thread stands in for the lane's owner
            p.set(K, 42);
        }

        @Actor
        public void release() {
            ctx.release();
        }

        @Arbiter
        public void arbiter(II_Result r) {
            r.r1 = lane.eventCount + 2 * ctx.misuses.get();
            r.r2 = p.lane == Tempo.Lane.POOLED && p.ctx == Tempo.Context.NONE ? 1 : 0;
        }
    }
}
