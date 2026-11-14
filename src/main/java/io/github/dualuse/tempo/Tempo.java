package io.github.dualuse.tempo;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Phase profiling for request-shaped work.
 *
 * <p>A {@link Trace} records a tree of {@link Span}s into per-thread primitive buffers. Recording
 * never throws into the caller (unless {@link Options#strict(boolean)} asks for it), allocates
 * nothing per span once warm, and below the trace's level {@link Span#detail(Name)} and
 * {@link Span#deep(Name)} cost a branch or two, unless the name is always-on ({@link Name#aggregate()}).
 *
 * <pre>{@code
 * static final Tempo.Name SEARCH = Tempo.name("search"), DECODE = Tempo.name("decode");
 *
 * try (Tempo.Trace t = Tempo.start(SEARCH)) {
 *     try (Tempo.Span s = t.root().span(DECODE)) {
 *         s.set(BYTES, image.length);
 *     }
 * }
 * }</pre>
 *
 * <p>A {@link Span} is a handle that only its lane's owner thread may use; the handle object is
 * reused for the next span opened at the same depth, so it must not be used after it is closed.
 * Work for another thread goes through a {@link Handoff}: {@code s.handoff(n)} where the task is
 * submitted, {@code h.begin()} (or {@code h.runnable(r)}) where it runs, which records its queue wait.
 */
public final class Tempo {

    /** Recording levels: coarse spans are always recorded, detail and deep spans only on opt-in. */
    public static final int COARSE = 0, DETAIL = 1, DEEP = 2;

    // Row flag bits (16-bit column).
    static final int PHASE = 1, RETRO = 1 << 1, HANDOFF = 1 << 2, WAIT = 1 << 3, FAILED = 1 << 4,
            CANCELLED = 1 << 5, HEALED = 1 << 6, MISUSE = 1 << 7, DETAIL_ROW = 1 << 8, DEEP_ROW = 1 << 9,
            LEGACY = 1 << 10, TASK = 1 << 11, UNRUN = 1 << 12; // UNRUN: a task cancelled before it began

    // Event kinds, in the low KIND_BITS of an event's key; the rest is a Name id for attributes.
    static final int KIND_BITS = 4, KIND_MASK = (1 << KIND_BITS) - 1,
            XPARENT = 1, QWAIT = 2, CPU = 3, ATTR_LONG = 4, ATTR_DOUBLE = 5, LABEL = 6;

    static final int CPU_AUTO = -1, CPU_OFF = 0, CPU_ON = 1;

    static final LongSupplier SYSTEM_CLOCK = System::nanoTime;

    static final ThreadLocal<ThreadSlot> SLOT = ThreadLocal.withInitial(ThreadSlot::new);

    /** The handle that records nothing. Every method on it returns at once; never {@code null}. */
    public static final Span NOOP = new Span(null, Context.NONE, 0);

    private Tempo() {}

    /** Interns a name. Keep the result in a {@code static final}; {@code null} and {@code ""} map to {@code ?}. */
    public static Name name(String literal) {
        return Name.intern(literal, false);
    }

    /** Starts a {@link #COARSE} trace whose root span is opened on the calling thread. */
    public static Trace start(Name root) {
        return new Trace(root, null);
    }

    public static Trace start(Name root, Options options) {
        return new Trace(root, options);
    }

    /**
     * The innermost open span of the calling thread, or {@link #NOOP}. An open phase is not a span of
     * its own here: inside {@code s.phase(n)} this returns {@code s}, whose new children still nest
     * under the phase. Costs one {@code ThreadLocal} lookup, so {@code Tempo.current().detail(n)} is
     * not free even when detail is off.
     */
    public static Span current() {
        Lane l = SLOT.get().current;
        if (l == null) return NOOP;
        int d = l.depth - 1;
        while (d > 0 && l.isPhase(d)) d--;
        return d < 0 ? NOOP : l.handles[d];
    }

    /** A listener that passes nanoseconds on to {@code sink} as seconds. */
    public static LongConsumer seconds(DoubleConsumer sink) {
        Objects.requireNonNull(sink, "sink");
        return nanos -> sink.accept(nanos / 1e9);
    }

    /**
     * Wraps a listener that is not thread-safe: calls to the returned one are serialized by a lock of
     * its own. Listeners run on the threads that end spans, concurrently, and are not locked otherwise.
     */
    public static LongConsumer serialized(LongConsumer sink) {
        Objects.requireNonNull(sink, "sink");
        Object lock = new Object();
        return nanos -> {
            synchronized (lock) {
                sink.accept(nanos);
            }
        };
    }

    /**
     * Runs {@code body} and passes its wall time in nanoseconds to {@code sink}, also when it throws; the
     * body's throwable is rethrown unwrapped. Needs no trace: two clock reads, nothing recorded. A
     * throwing sink never reaches the caller: its throwable is added as suppressed to the body's, or
     * dropped when the body completed.
     */
    public static <T, E extends Exception> T time(LongConsumer sink, ThrowingSupplier<T, E> body) throws E {
        long start = SYSTEM_CLOCK.getAsLong();
        Throwable failed = null;
        try {
            return body.get();
        } catch (Throwable t) {
            failed = t;
            throw t;
        } finally {
            report(sink, start, failed);
        }
    }

    /** Like {@link #time(LongConsumer, ThrowingSupplier)} for a body without a result. */
    public static <E extends Exception> void time(LongConsumer sink, ThrowingRunnable<E> body) throws E {
        long start = SYSTEM_CLOCK.getAsLong();
        Throwable failed = null;
        try {
            body.run();
        } catch (Throwable t) {
            failed = t;
            throw t;
        } finally {
            report(sink, start, failed);
        }
    }

    private static void report(LongConsumer sink, long start, Throwable failed) {
        if (sink == null) return;
        try {
            sink.accept(SYSTEM_CLOCK.getAsLong() - start);
        } catch (Throwable t) {
            if (failed != null && failed != t) failed.addSuppressed(t);
        }
    }

    static int clampLevel(int level) {
        return Math.max(COARSE, Math.min(DEEP, level));
    }

    /**
     * Thread CPU time from the management bean. A holder of its own, so {@code Tempo} loads and records
     * without the {@code java.management} module, where every read is -1 ("not measured").
     */
    static final class Cpu {
        private static final java.lang.management.ThreadMXBean BEAN = bean();

        private static java.lang.management.ThreadMXBean bean() {
            try {
                java.lang.management.ThreadMXBean b = java.lang.management.ManagementFactory.getThreadMXBean();
                return b.isCurrentThreadCpuTimeSupported() ? b : null;
            } catch (Throwable missing) { // NoClassDefFoundError without java.management
                return null;
            }
        }

        /** CPU nanoseconds of the calling thread, or -1 if not measured. */
        static long now() {
            java.lang.management.ThreadMXBean b = BEAN;
            return b != null && b.isThreadCpuTimeEnabled() ? b.getCurrentThreadCpuTime() : -1;
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** An interned span or attribute name. Names are constants; dynamic data belongs in attributes. */
    public static final class Name {
        static final int DYNAMIC_CAP = 4096;
        private static final ConcurrentHashMap<String, Name> TABLE = new ConcurrentHashMap<>();
        private static volatile Name[] byId = new Name[256];
        private static int size, dynamicSize; // guarded by TABLE
        static volatile boolean anySink;    // some name has a sink; read once per trace
        private static final LongConsumer[] NO_SINKS = {};
        static final Name UNKNOWN = intern("?", false), OTHER = intern("<other>", false);

        public final int id;
        final String text;
        boolean marked; // always-on; set at class init in practice, so a plain read on the detail path
        volatile LongConsumer[] sinks = NO_SINKS; // replaced whole on add, so a racy read sees it whole

        private Name(int id, String text) {
            this.id = id;
            this.text = text;
        }

        /**
         * Adds a global listener, called with the duration in nanoseconds each time a span of this name
         * ends, in every trace started from now on, and for {@link Trace#add}. It runs on the thread that
         * ends the span, without a lock, so it must be thread-safe (see {@link Tempo#serialized}); a
         * throwable it raises is counted as misuse. Marks the name always-on like {@link #aggregate()}.
         */
        public Name sink(LongConsumer nanos) {
            if (nanos == null) return this;
            synchronized (this) {
                LongConsumer[] s = Arrays.copyOf(sinks, sinks.length + 1);
                s[s.length - 1] = nanos;
                sinks = s;
            }
            marked = true;
            anySink = true;
            return this;
        }

        /**
         * Marks the name always-on: below the trace's level {@link Span#detail} and {@link Span#deep}
         * return an aggregate-only span instead of {@link Tempo#NOOP}. It writes no row, costs two clock
         * reads, adds its duration to its lane's aggregate and so to {@link Trace#aggregate(Name)}, and
         * calls the listeners.
         */
        public Name aggregate() {
            marked = true;
            return this;
        }

        /** Dynamic names (the {@code String} overloads) stop growing the table at the cap and map to {@code <other>}. */
        static Name intern(String text, boolean dynamic) {
            if (text == null || text.isEmpty()) return UNKNOWN;
            Name n = TABLE.get(text);
            if (n != null) return n;
            synchronized (TABLE) {
                n = TABLE.get(text);
                if (n != null) return n;
                if (dynamic && dynamicSize == DYNAMIC_CAP) return OTHER;
                if (dynamic) dynamicSize++;
                Name[] t = byId;
                if (size == t.length) t = Arrays.copyOf(t, size * 2);
                t[size] = n = new Name(size, text);
                size++;
                byId = t;
                TABLE.put(text, n);
                return n;
            }
        }

        static Name byId(int id) {
            return byId[id];
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** Settings for one trace; read once at {@link Tempo#start(Name, Options)}. */
    public static final class Options {
        static final Options DEFAULTS = new Options(); // never handed out, so never mutated

        int level = COARSE, budget = 512, headerBytes = 3000, lanes = 256, cpu = CPU_AUTO;
        boolean strict;
        LongSupplier clock = SYSTEM_CLOCK, cpuClock; // cpuClock: tests only; null reads the thread's CPU time
        LongSupplier wallClock = System::currentTimeMillis; // tests only

        /** {@link #COARSE} (default), {@link #DETAIL} or {@link #DEEP}; out-of-range values are clamped. */
        public Options level(int level) {
            this.level = clampLevel(level);
            return this;
        }

        /**
         * {@link #DETAIL} and {@link #DEEP} spans recorded individually per trace, default 512; past it they
         * fold into aggregates ({@link SpanView#aggregate()}). Lanes take the budget in blocks of
         * {@code max(1, budget / 64)} and give back what they did not use when their task ends.
         */
        public Options budget(int detailSpans) {
            this.budget = Math.max(0, detailSpans);
            return this;
        }

        /**
         * Thread CPU time of each lane root (the trace's root and every task begun on another thread) and
         * of the spans that ask with {@link Span#cpu()}; see {@link SpanView#cpuNanos()}. By default it
         * is measured whenever the level is {@link #DETAIL} or above when the span opens, never at
         * {@link #COARSE}. {@code true} measures at every level, {@code false} never.
         */
        public Options cpu(boolean on) {
            this.cpu = on ? CPU_ON : CPU_OFF;
            return this;
        }

        /** A fake CPU clock for tests, read on the calling thread like the real one; -1 is "not measured". */
        Options cpuClock(LongSupplier cpuNanos) {
            this.cpuClock = cpuNanos;
            return this;
        }

        /** A fake wall clock in epoch milliseconds for tests, read once when the trace starts. */
        Options wallClock(LongSupplier millis) {
            this.wallClock = millis;
            return this;
        }

        /** Byte limit of the detailed header ({@link Trace#toHeader()}); default 3000. */
        public Options headerBytes(int max) {
            this.headerBytes = Math.max(0, max);
            return this;
        }

        /** Nanosecond clock for every timestamp of the trace; default {@code System::nanoTime}. For tests. */
        public Options clock(LongSupplier nanoClock) {
            this.clock = nanoClock == null ? SYSTEM_CLOCK : nanoClock;
            return this;
        }

        /**
         * Lanes of one trace, the starting thread's root lane included; default 256, at least 1. A lane
         * is one thread's record of the trace: a thread records its tasks in one lane, and opens another
         * only for a task that begins while that lane is open under another trace's task. A pool
         * of platform threads stays far below the cap; with a thread per task (virtual threads) the tasks
         * begun past it are not recorded. Such a task's handoff is left out of the tree, its queue wait
         * is added to {@link Trace#aggregate(Name)} of the handoff's name, and it counts as dropped.
         */
        public Options lanes(int max) {
            this.lanes = Math.max(1, max);
            return this;
        }

        /** Throws {@link AssertionError} at the offending call on misuse instead of healing silently. */
        public Options strict(boolean on) {
            this.strict = on;
            return this;
        }
    }

    /**
     * Per-trace settings, counters, the lane registry and the lane-less aggregates. Lanes and handoffs
     * reference this instead of the {@link Trace}.
     */
    static final class Context {
        private static final AtomicLong IDS = new AtomicLong();
        private static final VarHandle LANES, TOTALS, DROPPED, CREDITS;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                LANES = l.findVarHandle(Context.class, "lanes", Lane.class);
                TOTALS = l.findVarHandle(Context.class, "totals", Totals.class);
                DROPPED = l.findVarHandle(Context.class, "dropped", int.class);
                CREDITS = l.findVarHandle(Context.class, "credits", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static final Context NONE = new Context();

        final long id, epoch, wall; // wall: epoch milliseconds at the start
        final LongSupplier clock, cpuClock;
        final boolean strict;
        final int budget, block, headerBytes, maxLanes, cpu;
        final AtomicInteger misuses = new AtomicInteger();
        volatile int level;
        volatile boolean detailSeen, released;
        boolean raised; // guarded by this
        // Written by the starting thread before the first child span; other threads read them after a
        // handoff, which orders the reads. Listeners is immutable, so even a racy read sees it whole.
        boolean listening; // a global sink existed at start, or a per-trace listener was added
        Listeners on;
        Consumer<String> printer; // printOnClose; written and read by the starting thread
        private volatile Lane lanes;    // CAS-linked registry, newest first
        private volatile Totals totals; // created with the first lane-less record
        private volatile int dropped;   // tasks left unrecorded at the lane cap
        private volatile int credits;   // detail budget not handed out to a lane
        private long[] lost;            // guarded by this: (laneId << 32 | row) of handoffs whose task has no record
        private int lostCount;

        private Context() {
            id = -1;
            epoch = wall = 0;
            clock = SYSTEM_CLOCK;
            cpuClock = null;
            strict = false;
            budget = block = headerBytes = maxLanes = 0;
            cpu = CPU_OFF;
            level = -1; // below COARSE, so NOOP.detail() returns at the level check
        }

        Context(Options o) {
            id = IDS.incrementAndGet();
            clock = o.clock;
            cpuClock = o.cpuClock;
            strict = o.strict;
            cpu = o.cpu;
            budget = credits = o.budget;
            block = Math.max(1, budget / 64);
            headerBytes = o.headerBytes;
            maxLanes = o.lanes;
            level = o.level;
            listening = Name.anySink;
            wall = o.wallClock.getAsLong();
            epoch = clock.getAsLong();
        }

        long now() {
            return clock.getAsLong() - epoch;
        }

        /** Whether a span opening now has its CPU time measured. */
        boolean cpu() {
            return cpu == CPU_ON || cpu == CPU_AUTO && level >= DETAIL;
        }

        long cpuNow() {
            return cpuClock == null ? Cpu.now() : cpuClock.getAsLong();
        }

        void misuse(String what) {
            misuses.incrementAndGet();
            if (strict) throw new AssertionError("Tempo misuse: " + what);
        }

        boolean listens(int nameId) {
            Listeners l = on;
            if (l != null) for (int id : l.ids) if (id == nameId) return true;
            return false;
        }

        /**
         * Calls the global sinks and this trace's listeners of {@code n}, each on its own: a throwable is
         * caught and counted as misuse, so the others still run and the recording stays consistent; the
         * first one is returned for strict mode.
         */
        Throwable notify(Name n, long nanos) {
            Throwable first = null;
            for (LongConsumer s : n.sinks) {
                Throwable t = call(s, nanos);
                if (first == null) first = t;
            }
            Listeners l = on;
            if (l != null) {
                for (int i = 0; i < l.ids.length; i++) {
                    if (l.ids[i] != n.id) continue;
                    Throwable t = call(l.sinks[i], nanos);
                    if (first == null) first = t;
                }
            }
            return first;
        }

        private Throwable call(LongConsumer sink, long nanos) {
            try {
                sink.accept(nanos);
                return null;
            } catch (Throwable t) {
                misuses.incrementAndGet();
                return t;
            }
        }

        static AssertionError listenerThrew(Throwable t) {
            return new AssertionError("Tempo misuse: a listener threw " + t, t);
        }

        int key(Name key) {
            if (key != null) return key.id;
            misuse("null attribute key");
            return Name.UNKNOWN.id;
        }

        boolean full() {
            Lane head = lanes;
            return head != null && head.id + 1 >= maxLanes;
        }

        /** Adds a lane to the registry; null when the trace has its maximum of lanes. */
        Lane register(Lane lane) {
            Lane head;
            do {
                head = lanes;
                if (head != null && head.id + 1 >= maxLanes) return null;
                lane.next = head;
                lane.id = head == null ? 0 : head.id + 1;
            } while (!LANES.compareAndSet(this, head, lane));
            // release() stores released, then walks the registry; this CASed the registry, then reads released
            if (released) lane.requestRetire();
            return lane;
        }

        /** A task lane of {@code owner} whose root is closed; called by the owner, which alone writes depth. */
        Lane idleLane(Thread owner) {
            for (Lane l = lanes; l != null; l = l.next) {
                if (l.owner == owner && l.id != 0 && l.depth == 0 && !l.retired()) return l;
            }
            return null;
        }

        Lane[] lanesById() {
            Lane head = lanes;
            Lane[] out = new Lane[head.id + 1];
            for (Lane l = head; l != null; l = l.next) out[l.id] = l;
            return out;
        }

        void release() {
            if (released) return;
            released = true;
            for (Lane l = lanes; l != null; l = l.next) l.requestRetire();
        }

        int dropped() {
            int n = dropped;
            for (Lane l = lanes; l != null; l = l.next) n += l.dropped;
            return n;
        }

        int folded() {
            int n = 0;
            for (Lane l = lanes; l != null; l = l.next) n += l.folded;
            return n;
        }

        /**
         * {@link Snapshot#aggregate} read in place: the same rows, task waits and lane aggregates, summed
         * without a copy. A read racing {@link #release()} may see recycled chunks; like a Snapshot's, it
         * is thrown away, also when what it read made it fail.
         */
        Aggregate aggregate(int id) {
            if (released) return Aggregate.EMPTY;
            long count = 0, sum = 0, min = Long.MAX_VALUE, max = 0, waits = 0, waitSum = 0, waitMax = 0;
            try {
                for (Lane l = lanes; l != null; l = l.next) {
                    if (l.retired()) continue;
                    long commit = l.committed();
                    int rows = (int) (commit >>> 32), events = (int) commit;
                    for (int r = 0; r < rows; r++) {
                        Rows c = l.rows[r >>> Lane.CHUNK_BITS];
                        int i = r & Lane.MASK, f = c.flags[i];
                        if (c.name[i] != id || (f & HANDOFF) != 0 || (f & (RETRO | MISUSE)) == (RETRO | MISUSE)
                                || (f & (TASK | CANCELLED)) == (TASK | CANCELLED)) continue;
                        long d = (long) Lane.DUR.getOpaque(c.dur, i);
                        if (d == Lane.OPEN) continue;
                        count++;
                        sum += d;
                        min = Math.min(min, d);
                        max = Math.max(max, d);
                    }
                    for (int e = 0; e < events; e++) { // every task row, begun or cancelled, has one QWAIT
                        Events c = l.events[e >>> Lane.CHUNK_BITS];
                        int i = e & Lane.MASK;
                        if ((c.key[i] & KIND_MASK) != QWAIT || l.nameOf(c.row[i]) != id) continue;
                        long w = c.value[i];
                        waits++;
                        waitSum += w;
                        waitMax = Math.max(waitMax, w);
                    }
                    Aggs a = (Aggs) Lane.AGGS.getAcquire(l);
                    for (int e = 0, n = a == null ? 0 : a.size(); e < n; e++) {
                        int k = e * Aggs.KEY, b = e * Aggs.CELLS;
                        // as in Shape: not ended yet, or moved under a lap row past the rows read
                        if (a.keys[k + Aggs.NAME] != id || a.cells[b + Aggs.COUNT] == 0 || a.keys[k] >= rows) continue;
                        count += a.cells[b + Aggs.COUNT];
                        sum += a.cells[b + Aggs.SUM];
                        min = Math.min(min, a.cells[b + Aggs.MIN]);
                        max = Math.max(max, a.cells[b + Aggs.MAX_DUR]);
                    }
                }
            } catch (RuntimeException e) {
                VarHandle.acquireFence();
                if (released) return Aggregate.EMPTY;
                throw e;
            }
            VarHandle.acquireFence();
            return released ? Aggregate.EMPTY : Totals.merge(totals, id, count, sum, min, max, waits, waitSum, waitMax);
        }

        /** Hands a lane up to one block of the detail budget; 0 when it is spent. */
        int credits() {
            int c, n;
            do {
                c = credits;
                if (c <= 0) return 0;
                n = Math.min(block, c);
            } while (!CREDITS.compareAndSet(this, c, c - n));
            return n;
        }

        void refund(int n) {
            CREDITS.getAndAdd(this, n);
        }

        int unspent() {
            return credits;
        }

        Totals frozenTotals() {
            Totals t = totals;
            return t == null ? null : t.copy();
        }

        Totals totals() {
            Totals t = totals;
            if (t == null && !TOTALS.compareAndSet(this, null, t = new Totals(8))) t = totals;
            return t;
        }

        /**
         * A handoff whose task gets no record: at the lane cap ({@code count}), or at a lane's row, depth or
         * event cap (counted there). Its row is left out of the tree and its queue wait goes to the aggregate.
         */
        void lost(Handoff h, boolean count) {
            if (count) DROPPED.getAndAdd(this, 1);
            int name = h.lane.nameAt(h.row);
            if (name < 0 || released) return;
            totals().add(name, true, Math.max(0, now() - h.submit));
            synchronized (this) {
                if (lost == null) lost = new long[8];
                else if (lostCount == lost.length) lost = Arrays.copyOf(lost, 2 * lostCount);
                lost[lostCount++] = (long) h.lane.id << 32 | h.row;
            }
        }

        synchronized long[] lost() {
            return lost == null ? new long[0] : Arrays.copyOf(lost, lostCount);
        }
    }

    /** A trace's listeners as (name id, sink) pairs; replaced whole when one is added. */
    static final class Listeners {
        final int[] ids;
        final LongConsumer[] sinks;

        Listeners(Listeners old, int id, LongConsumer sink) {
            int n = old == null ? 0 : old.ids.length;
            ids = old == null ? new int[1] : Arrays.copyOf(old.ids, n + 1);
            sinks = old == null ? new LongConsumer[1] : Arrays.copyOf(old.sinks, n + 1);
            ids[n] = id;
            sinks[n] = sink;
        }
    }

    /**
     * Lane-less aggregates of one trace, keyed by name: thread-safe, lock-free, allocation-free once a
     * name has a slot. Open addressing whose slots are claimed once and never move; a full table chains
     * a larger one, so a name lives in exactly one table of the chain.
     */
    static final class Totals {
        static final int COUNT = 0, SUM = 1, MIN = 2, MAX = 3, WAITS = 4, WAIT_SUM = 5, WAIT_MAX = 6, CELLS = 7;
        private static final VarHandle KEY = MethodHandles.arrayElementVarHandle(int[].class),
                CELL = MethodHandles.arrayElementVarHandle(long[].class), NEXT;

        static {
            try {
                NEXT = MethodHandles.lookup().findVarHandle(Totals.class, "next", Totals.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final int[] keys;   // name id + 1; 0 is a free slot
        private final long[] cells; // CELLS per slot; MIN holds Long.MAX_VALUE - min, so every extreme is a max from 0
        private volatile Totals next;

        Totals(int capacity) {
            keys = new int[capacity];
            cells = new long[capacity * CELLS];
        }

        private Totals(Totals t) {
            keys = new int[t.keys.length];
            cells = new long[t.cells.length];
            for (int i = 0; i < keys.length; i++) keys[i] = (int) KEY.getAcquire(t.keys, i);
            for (int i = 0; i < cells.length; i++) cells[i] = cell(t.cells, i);
            Totals n = t.next;
            next = n == null ? null : new Totals(n);
        }

        /** A copy that no later add changes. */
        Totals copy() {
            return new Totals(this);
        }

        void add(int id, boolean wait, long nanos) {
            Totals t = this;
            int s;
            while ((s = t.slot(id, true)) < 0) {
                Totals n = t.next;
                if (n == null && !NEXT.compareAndSet(t, null, n = new Totals(2 * t.keys.length))) n = t.next;
                t = n;
            }
            long[] c = t.cells;
            int b = s * CELLS;
            if (wait) {
                CELL.getAndAdd(c, b + WAITS, 1L);
                CELL.getAndAdd(c, b + WAIT_SUM, nanos);
                max(c, b + WAIT_MAX, nanos);
            } else {
                CELL.getAndAdd(c, b + COUNT, 1L);
                CELL.getAndAdd(c, b + SUM, nanos);
                max(c, b + MAX, nanos);
                max(c, b + MIN, Long.MAX_VALUE - nanos);
            }
        }

        /**
         * The lane sums of {@code id} (min {@link Long#MAX_VALUE} when count is 0) plus its lane-less
         * entry in {@code totals}, which may be null; one allocation.
         */
        static Aggregate merge(Totals totals, int id, long count, long sum, long min, long max,
                long waits, long waitSum, long waitMax) {
            for (Totals t = totals; t != null; t = t.next) {
                int s = t.slot(id, false);
                if (s < 0) continue;
                long[] c = t.cells;
                int b = s * CELLS;
                long n = cell(c, b + COUNT);
                if (n > 0) min = Math.min(min, Long.MAX_VALUE - cell(c, b + MIN));
                count += n;
                sum += cell(c, b + SUM);
                max = Math.max(max, cell(c, b + MAX));
                waits += cell(c, b + WAITS);
                waitSum += cell(c, b + WAIT_SUM);
                waitMax = Math.max(waitMax, cell(c, b + WAIT_MAX));
                break;
            }
            return new Aggregate(count, sum, count == 0 ? 0 : min, max, waits, waitSum, waitMax);
        }

        /** The slot of {@code id}, claiming a free one if {@code claim}; -1 if absent (and, claiming, the table is full). */
        private int slot(int id, boolean claim) {
            int mask = keys.length - 1, key = id + 1;
            for (int i = id & mask, k = 0; k <= mask; k++, i = (i + 1) & mask) {
                int s = (int) KEY.getAcquire(keys, i);
                if (s == 0 && claim) s = (int) KEY.compareAndExchange(keys, i, 0, key);
                if (s == 0 && claim || s == key) return i;
                if (s == 0) return -1;
            }
            return -1;
        }

        private static long cell(long[] c, int i) {
            return (long) CELL.getVolatile(c, i);
        }

        private static void max(long[] c, int i, long v) {
            long cur;
            while ((cur = cell(c, i)) < v && !CELL.compareAndSet(c, i, cur, v)) { }
        }
    }

    /**
     * Per-thread state. Holds no {@link Trace}; only lane-root opens and closes touch {@code current}.
     * Pool threads interleave tasks of concurrent traces, so the slot remembers its lanes of the last
     * four traces; a lane found retired or collected is dropped at the next lookup. A miss reuses the
     * thread's idle lane from the trace's registry before it registers a new one, so a trace holds at
     * most one idle lane per thread however many traces interleave. The references are weak: a lane
     * reaches its trace's registry, so a strong one would keep an unreleased trace with all its lanes
     * alive for as long as the thread idles. The registry keeps the lanes of a live trace.
     */
    static final class ThreadSlot {
        Lane current;
        StringBuilder text; // the header encoder's, reused while it stays small (Header.KEEP)
        @SuppressWarnings("unchecked")
        final WeakReference<Lane>[] lanes = new WeakReference[4];
        private int victim;

        /** An idle lane of this thread for the trace: cached, found in the registry, or new; null at the lane cap. */
        Lane lane(Context ctx) {
            int free = -1;
            for (int i = 0; i < lanes.length; i++) {
                WeakReference<Lane> w = lanes[i];
                Lane l = w == null ? null : w.get();
                if (l == null || l.retired()) {
                    lanes[i] = null;
                    if (free < 0) free = i;
                } else if (l.ctx == ctx && l.depth == 0) {
                    return l;
                }
            }
            Lane l = ctx.idleLane(Thread.currentThread());
            if (l == null && !ctx.full()) l = ctx.register(new Lane(ctx, this));
            if (l != null) lanes[free >= 0 ? free : victim++ & 3] = new WeakReference<>(l);
            return l;
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** Row columns for {@link Lane#CHUNK} rows; pooled globally, never referenced by callers. */
    static final class Rows {
        final long[] start = new long[Lane.CHUNK], dur = new long[Lane.CHUNK];
        final int[] name = new int[Lane.CHUNK], parent = new int[Lane.CHUNK];
        final short[] flags = new short[Lane.CHUNK];
    }

    /** Event log columns for {@link Lane#CHUNK} events. */
    static final class Events {
        final int[] row = new int[Lane.CHUNK], key = new int[Lane.CHUNK];
        final long[] value = new long[Lane.CHUNK];
    }

    /**
     * Bounded lock-free pool of recycled chunks; a chunk that does not fit goes to the GC. The slots
     * move between two Treiber stacks of slot indexes, {@code full} and {@code empty}, whose heads pack
     * {@code (version << 32) | (index + 1)}: the version changes with every push and pop, so a pop that
     * read a stale {@code next} fails its CAS instead of corrupting the stack (no ABA). Allocation-free.
     */
    static final class Pool<T> {
        private static final VarHandle FULL, EMPTY;
        private static final long STEP = 1L << 32, INDEX = STEP - 1;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FULL = l.findVarHandle(Pool.class, "full", long.class);
                EMPTY = l.findVarHandle(Pool.class, "empty", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final Object[] items; // items[i] holds a chunk while slot i is on the full stack
        private final int[] next;     // index + 1 of the slot below slot i on its stack, 0 at the bottom
        private final Supplier<T> fresh;
        @SuppressWarnings("unused") private volatile long full, empty;

        Pool(int capacity, Supplier<T> fresh) {
            items = new Object[capacity];
            next = new int[capacity];
            for (int i = 0; i < capacity; i++) next[i] = i;
            empty = capacity;
            this.fresh = fresh;
        }

        @SuppressWarnings("unchecked")
        T take() {
            int i = pop(FULL);
            if (i < 0) return fresh.get();
            T chunk = (T) items[i];
            items[i] = null;
            push(EMPTY, i);
            return chunk;
        }

        void give(Object chunk) {
            int i = pop(EMPTY);
            if (i < 0) return;
            items[i] = chunk;
            push(FULL, i);
        }

        /** Chunks held; for tests, while no thread uses the pool. */
        int size() {
            int n = 0;
            for (long s = (long) FULL.getVolatile(this) & INDEX; s != 0; s = next[(int) s - 1]) n++;
            return n;
        }

        private int pop(VarHandle head) {
            long top;
            int i;
            do {
                top = (long) head.getVolatile(this);
                i = (int) (top & INDEX) - 1;
                if (i < 0) return -1;
            } while (!head.compareAndSet(this, top, (top & ~INDEX) + STEP | next[i]));
            return i;
        }

        private void push(VarHandle head, int i) {
            long top;
            do {
                top = (long) head.getVolatile(this);
                next[i] = (int) (top & INDEX);
            } while (!head.compareAndSet(this, top, (top & ~INDEX) + STEP | (i + 1)));
        }
    }

    static final Pool<Rows> ROW_POOL = new Pool<>(1024, Rows::new);
    static final Pool<Events> EVENT_POOL = new Pool<>(1024, Events::new);

    /**
     * The spans one thread records for one trace. Single writer (the owner thread); readers on other
     * threads see rows and events below the counts packed into {@code commit}.
     */
    static final class Lane {
        static final int CHUNK_BITS = 8, CHUNK = 1 << CHUNK_BITS, MASK = CHUNK - 1, CHUNKS = 64,
                MAX_ROWS = CHUNK * CHUNKS, MAX_EVENTS = CHUNK * CHUNKS, MAX_DEPTH = 35;
        // state = epoch * EPOCH + kind; the epoch counts the owner's IDLE -> ACTIVE moves, so a reader that
        // finds the same IDLE state before and after its reads knows nothing was written in between.
        static final int IDLE = 0, ACTIVE = 1, RETIRED = 2, KIND = 3, EPOCH = 4, FRAME = -1;
        static final long OPEN = Long.MIN_VALUE;

        private static final VarHandle COMMIT, STATE, LABELS, AGGS, DUR = MethodHandles.arrayElementVarHandle(long[].class);

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                COMMIT = l.findVarHandle(Lane.class, "commit", long.class);
                STATE = l.findVarHandle(Lane.class, "state", int.class);
                LABELS = l.findVarHandle(Lane.class, "labels", String[].class);
                AGGS = l.findVarHandle(Lane.class, "aggs", Aggs.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        final Context ctx;
        final Thread owner;
        final ThreadSlot slot;
        int id;
        Lane next; // trace registry link, set before the lane is published

        // Chunk tables never reallocate; a chunk is stored before the commit that exposes its first element.
        // The event table is created with the first event, so a lane without attributes never pays for it.
        final Rows[] rows = new Rows[CHUNKS];
        Events[] events;

        // Open stack: rowAt[d] is the open row at depth d < depth; handles[d] is the reusable handle for depth d.
        // An aggregate-only span is a frame without a row: its handle's row is FRAME and rowAt holds the
        // row of the nearest recorded span below it, which is the parent of anything opened above it.
        final int[] rowAt = new int[MAX_DEPTH];
        final Span[] handles = new Span[MAX_DEPTH];
        int depth, rowCount, eventCount, dropped;
        boolean framed; // a frame was ever opened: the trace's listeners are frozen
        int credit, folded; // detail budget held by this lane; detail spans folded for want of it

        // Folded and aggregate-only spans; replaced by a grown copy via AGGS.setRelease, like labels.
        private Aggs aggs;

        // CPU time at open of the rows at the depths whose bit is set in cpuMask; created on first use.
        long cpuMask;
        long[] cpuAt;

        private Throwable thrown; // strict mode: a listener's throwable, rethrown once the operation is done

        // Phase state of the span at depth d, created with the lane's first phase or lap: the time of its
        // last phase boundary (valid unless lastPhase[d] == NO_PHASE), its last phase or lap row (or
        // NO_PHASE, DROPPED_PHASE) and the number of boundaries so far.
        static final int NO_PHASE = -1, DROPPED_PHASE = -2;
        long[] mark;
        int[] lastPhase, lapOrdinal;
        int[] lapFrom; // aggregates created before the current lap of depth d; the lap's own come after

        // Legacy labels, referenced by LABEL events. Grown by copy and stored via LABELS.setRelease, so a
        // reader that acquires the commit and then the array sees every label that commit exposes, even
        // in a copy stored after that commit. The owner reads the field plainly.
        private String[] labels;
        int labelCount;

        @SuppressWarnings("unused") // via COMMIT: (rowCount << 32) | eventCount
        private long commit;
        private volatile int state;
        private volatile boolean retireRequested;
        private Lane saved; // slot.current when the lane root opened

        Lane(Context ctx, ThreadSlot slot) {
            this.ctx = ctx;
            this.owner = Thread.currentThread();
            this.slot = slot;
        }

        boolean retired() {
            return (state & KIND) == RETIRED;
        }

        int state() {
            return state;
        }

        /** Owner only: IDLE -> ACTIVE in a new epoch; false once retired. */
        private boolean activate() {
            int s = state;
            return (s & KIND) == IDLE && STATE.compareAndSet(this, s, s + EPOCH + ACTIVE);
        }

        /** Writes a closed handoff row under the innermost open span at the submit time. */
        Handoff handoff(Name n, int flags) {
            int r = row(n, flags | HANDOFF, rowAt[depth - 1]);
            if (r < 0) return Handoff.NOOP;
            long now = ctx.now();
            closed(r, now, 0);
            publish();
            return new Handoff(this, r, now);
        }

        /**
         * Records the task of handoff {@code h} on this, the calling thread's lane: a lane root, or, while
         * the thread is inside the trace already (inline), a child of the innermost open span. The row is
         * linked to the handoff row and carries the queue wait. A cancelled task ({@code run} false) is a
         * closed zero-length row. Returns the task's span ({@link #NOOP} if cancelled or retired), or
         * null when a cap leaves no room.
         */
        Span task(Handoff h, boolean run) {
            boolean root = depth == 0;
            if (root && !activate()) return NOOP;
            int id = h.lane.nameAt(h.row);
            if (id < 0) { // the submitter's lane is retired: the trace was released
                if (root) idle();
                return NOOP;
            }
            Name n = Name.byId(id);
            Span s = NOOP;
            int r = -1;
            if (eventCount > MAX_EVENTS - 2) {
                dropped++;
            } else if (run) {
                s = stack(n, TASK);
                r = s == NOOP ? -1 : s.row;
            } else {
                r = row(n, TASK | CANCELLED | UNRUN, root ? -1 : rowAt[depth - 1]);
            }
            if (r < 0) {
                if (root) idle();
                return null;
            }
            if (run && root && ctx.cpu()) sampleCpu(0); // before the start, so the read is not timed
            Rows c = rows[r >>> CHUNK_BITS];
            long now = ctx.now();
            c.start[r & MASK] = now;
            if (!run) DUR.setOpaque(c.dur, r & MASK, 0L);
            append(r, XPARENT, (long) h.lane.id << 32 | h.row);
            append(r, QWAIT, Math.max(0, now - h.submit));
            publish();
            if (run && root) {
                saved = slot.current;
                slot.current = this;
            } else if (root) {
                idle();
            }
            return s;
        }

        Span openRoot(Name n, int flags) {
            if (!activate()) return NOOP;
            Span h = stack(n, flags);
            if (h == NOOP) {
                idle();
                return NOOP;
            }
            if (ctx.cpu()) sampleCpu(0);
            start(h);
            saved = slot.current;
            slot.current = this;
            return h;
        }

        Span push(Name n, int flags) {
            Span h = stack(n, flags);
            if (h != NOOP) start(h);
            return h;
        }

        /**
         * A {@link #DETAIL} or {@link #DEEP} span: a row while the lane holds budget, refilled a block at
         * a time from the trace; once the trace's budget is spent, folded into the lane's aggregates.
         */
        Span budgeted(Name n, int flags) {
            if (credit == 0 && (credit = ctx.credits()) == 0) {
                Span h = frame(n);
                if (h != NOOP && h.agg >= 0) { // past the aggregate cap the frame counted a drop
                    h.folded = true;
                    folded++;
                }
                return h;
            }
            Span h = push(n, flags);
            if (h != NOOP) credit--;
            return h;
        }

        private void start(Span h) {
            rows[h.row >>> CHUNK_BITS].start[h.row & MASK] = ctx.now(); // read last: the bookkeeping is not timed
            publish();
        }

        /**
         * Opens an aggregate-only span above the open one: no row, its start kept in the handle; it ends
         * into the aggregate keyed by its anchor (the nearest recorded span), the anchor's lap, its parent
         * (-1 when that is the anchor, else the name of the enclosing aggregate-only span) and its name.
         */
        Span frame(Name n) {
            int d = depth;
            if (d == MAX_DEPTH) {
                dropped++;
                return NOOP;
            }
            int a = d - 1;
            while (handles[a].row == FRAME) a--;
            int agg = aggregate(rowAt[d - 1], lapOrdinal(a), a == d - 1 ? -1 : handles[d - 1].frame.id, n.id);
            rowAt[d] = rowAt[d - 1];
            depth = d + 1;
            framed = true;
            Span h = handles[d];
            if (h == null) handles[d] = h = new Span(this, ctx, d);
            h.row = FRAME;
            h.frame = n;
            h.agg = agg;
            h.folded = false;
            h.time = ctx.now();
            return h;
        }

        /** The entry of an aggregate key, created if new; -1 past {@link Aggs#MAX} entries (counted as a drop). */
        private int aggregate(int anchor, int lap, int parent, int name) {
            Aggs a = aggs;
            if (a == null) AGGS.setRelease(this, a = new Aggs(8));
            int e = a.find(anchor, lap, parent, name);
            if (e >= 0) return e;
            if (a.size() == Aggs.MAX) {
                dropped++;
                return -1;
            }
            if (a.full()) AGGS.setRelease(this, a = a.grow());
            return a.create(anchor, lap, parent, name);
        }

        int aggregates() {
            Aggs a = aggs;
            return a == null ? 0 : a.size();
        }

        /** The aggregates of the lap of depth {@code d} that just ended as RETRO row {@code row} move under it. */
        private void reanchor(int d, int lap, int row) {
            Aggs a = aggs;
            if (a != null) a.reanchor(lapFrom[d], rowAt[d], lap, row);
        }

        /** Elapsed time of frame handle {@code h}, which holds its start while open, then its duration; best effort off the owner. */
        long frameElapsed(Span h) {
            if (ctx.released || retired()) return 0;
            return h.depth < depth ? Math.max(0, ctx.now() - h.time) : h.time;
        }

        /** CPU time since {@link #sampleCpu} of depth {@code k}, read after the end so the read is not timed; -1 if not measured. */
        private long cpuSince(int k) {
            if (cpuMask == 0 || (cpuMask >>> k & 1) == 0) return -1;
            cpuMask &= ~(1L << k);
            long t = ctx.cpuNow();
            return t < 0 ? -1 : Math.max(0, t - cpuAt[k]);
        }

        /** Starts measuring the CPU time of the span at depth {@code d}, unless it is already or the read is -1. */
        void sampleCpu(int d) {
            if ((cpuMask >>> d & 1) != 0) return;
            long t = ctx.cpuNow();
            if (t < 0) return;
            if (cpuAt == null) cpuAt = new long[MAX_DEPTH];
            cpuAt[d] = t;
            cpuMask |= 1L << d;
        }

        /** Opens a row on top of the stack and hands out the depth's handle; the caller sets the start and publishes. */
        private Span stack(Name n, int flags) {
            int d = depth;
            if (d == MAX_DEPTH) {
                dropped++;
                return NOOP;
            }
            int r = row(n, flags, d == 0 ? -1 : rowAt[d - 1]);
            if (r < 0) return NOOP;
            DUR.setOpaque(rows[r >>> CHUNK_BITS].dur, r & MASK, OPEN);
            rowAt[d] = r;
            depth = d + 1;
            if (lastPhase != null) {
                lastPhase[d] = NO_PHASE;
                lapOrdinal[d] = 0;
                lapFrom[d] = aggregates();
            }
            Span h = handles[d];
            if (h == null) handles[d] = h = new Span(this, ctx, d);
            h.row = r;
            return h;
        }

        /** Writes name, parent and flags of a new row; at the row cap counts a drop and returns -1. */
        private int row(Name n, int flags, int parent) {
            int r = rowCount;
            if (r == MAX_ROWS) {
                dropped++;
                return -1;
            }
            Rows c = rows[r >>> CHUNK_BITS];
            if (c == null) rows[r >>> CHUNK_BITS] = c = ROW_POOL.take();
            int i = r & MASK;
            c.name[i] = n.id;
            c.parent[i] = parent;
            c.flags[i] = (short) flags;
            rowCount = r + 1;
            return r;
        }

        /** Sets start and duration of a row that is closed from the moment it is published. */
        private void closed(int r, long start, long dur) {
            Rows c = rows[r >>> CHUNK_BITS];
            c.start[r & MASK] = start;
            DUR.setOpaque(c.dur, r & MASK, dur < 0 ? 0L : dur);
        }

        /** Closes the row at depth {@code d}, healing everything above it first. */
        void close(int d) {
            long now = ctx.now();
            int top = depth;
            boolean healed = unwind(d, now);
            end(d, now, false);
            depth = d;
            publish();
            if (ctx.listening) fire(d, top); // before exitRoot, which may return the rows to the pool
            if (d == 0 && id == 0 && ctx.printer != null) {
                Throwable t = print(ctx.printer);
                if (t != null && ctx.strict && thrown == null) thrown = t;
            }
            if (d == 0) exitRoot();
            if (thrown != null) rethrow();
            if (healed) ctx.misuse("span closed while a child span was open");
        }

        /** Ends every row above depth {@code d}; returns whether one of them was a span healed (phases end normally). */
        private boolean unwind(int d, long now) {
            boolean healed = false;
            for (int k = depth - 1; k > d; k--) healed |= end(k, now, true);
            depth = d + 1;
            return healed;
        }

        /** Ends the row at depth {@code k}: flags and events first, duration last (the commit protocol). */
        private boolean end(int k, long now, boolean heal) {
            Span h = handles[k];
            if (h.row == FRAME) {
                long start = h.time, dur = Math.max(0, now - start), cpu = cpuSince(k);
                h.time = dur;
                if (h.agg >= 0) aggs.add(h.agg, start, dur, cpu, h.folded);
                return heal;
            }
            int r = h.row;
            Rows c = rows[r >>> CHUNK_BITS];
            int i = r & MASK;
            boolean phase = (c.flags[i] & PHASE) != 0, healed = heal && !phase;
            if (healed) c.flags[i] |= HEALED;
            if (phase) { // however the phase ends, its end is the owner's last boundary
                mark[k - 1] = now;
                lapFrom[k - 1] = aggregates();
            }
            long cpu = cpuSince(k);
            if (cpu >= 0) append(r, CPU, cpu);
            long dur = now - c.start[i];
            DUR.setOpaque(c.dur, i, dur < 0 ? 0L : dur);
            return healed;
        }

        /**
         * Calls the listeners of the spans at depths [d, top) that just ended, innermost first. They run
         * inside the operation that ended the spans, so they must not open or close spans on this thread.
         */
        private void fire(int d, int top) {
            for (int k = top - 1; k >= d; k--) {
                Span h = handles[k];
                if (h.row == FRAME) notify(h.frame, h.time);
                else notify(Name.byId(nameOf(h.row)), rows[h.row >>> CHUNK_BITS].dur[h.row & MASK]);
            }
        }

        private void notify(Name n, long nanos) {
            Throwable t = ctx.notify(n, nanos);
            if (t != null && ctx.strict && thrown == null) thrown = t;
        }

        /** Passes the {@code Runtime} text of this, the root lane, to {@code out}; returns what it threw, counted as misuse. */
        Throwable print(Consumer<String> out) {
            try {
                out.accept(tops(handles[0].row).runtime(-1));
                return null;
            } catch (Throwable t) {
                ctx.misuses.incrementAndGet();
                return t;
            }
        }

        private void rethrow() {
            Throwable t = thrown;
            thrown = null;
            throw Context.listenerThrew(t);
        }

        boolean isPhase(int d) {
            int r = handles[d].row;
            return r != FRAME && (rows[r >>> CHUNK_BITS].flags[r & MASK] & PHASE) != 0;
        }

        // ---- phases and laps (owner thread, the span at depth d is open) ----

        private void laps() {
            mark = new long[MAX_DEPTH];
            lapOrdinal = new int[MAX_DEPTH];
            lapFrom = new int[MAX_DEPTH];
            int[] p = new int[MAX_DEPTH];
            Arrays.fill(p, NO_PHASE);
            lastPhase = p;
        }

        /** Label first: ends whatever is open above depth {@code d} and opens a phase row at one {@code now}. */
        void phase(int d, Name n, int flags) {
            if (lastPhase == null) laps();
            long now = ctx.now();
            int top = depth;
            boolean healed = unwind(d, now);
            if (ctx.listening) fire(d + 1, top); // before the new phase takes the depth of the old one
            mark[d] = now;
            lapOrdinal[d]++;
            lapFrom[d] = aggregates();
            Span h = stack(n, flags | PHASE);
            if (h != NOOP) rows[h.row >>> CHUNK_BITS].start[h.row & MASK] = now;
            lastPhase[d] = h == NOOP ? DROPPED_PHASE : h.row;
            publish();
            if (thrown != null) rethrow();
            if (healed) ctx.misuse("phase started while a child span was open");
        }

        /**
         * Label after. An open label-first phase ends under its own name; a different {@code n} is kept as
         * a zero-length misuse row. Otherwise a closed RETRO row covers {@code [mark, now)}; the render
         * moves the spans opened since {@code mark} under it. {@code n} is null when the caller passed none.
         */
        void done(int d, Name n, int flags, String label) {
            if (lastPhase == null) laps();
            long now = ctx.now(), lap = -1; // the new lap's duration, for its listeners
            int p = lastPhase[d], top = depth;
            boolean healed, mismatch = false;
            if (p >= 0 && depth > d + 1 && rowAt[d + 1] == p) {
                annotate(p, flags, label);
                healed = unwind(d, now); // ends p and sets mark[d]
                if (n != null && nameOf(p) != n.id) {
                    mismatch = true;
                    int m = row(n, RETRO | PHASE | MISUSE, rowAt[d]);
                    if (m >= 0) closed(m, now, 0);
                }
            } else {
                healed = unwind(d, now);
                long from = p == NO_PHASE ? rows[rowAt[d] >>> CHUNK_BITS].start[rowAt[d] & MASK] : mark[d];
                int r = n == null ? row(Name.UNKNOWN, RETRO | PHASE | MISUSE | flags, rowAt[d])
                        : row(n, RETRO | PHASE | flags, rowAt[d]);
                if (r >= 0) {
                    closed(r, from, now - from);
                    annotate(r, 0, label);
                    reanchor(d, lapOrdinal[d], r);
                }
                lastPhase[d] = r >= 0 ? r : DROPPED_PHASE;
                mark[d] = now;
                lapOrdinal[d]++;
                lapFrom[d] = aggregates();
                if (n != null) lap = Math.max(0, now - from);
            }
            publish();
            if (ctx.listening) {
                fire(d + 1, top);
                if (lap >= 0) notify(n, lap);
            }
            if (thrown != null) rethrow();
            if (healed) ctx.misuse("lap ended while a child span was open");
            if (mismatch) ctx.misuse("done(" + n + ") while phase " + Name.byId(nameOf(p)) + " is open");
        }

        void legacy(int d) {
            int p = lastPhase(d, "legacy()");
            if (p < 0) return;
            rows[p >>> CHUNK_BITS].flags[p & MASK] |= LEGACY;
            publish();
        }

        void phaseSet(int d, int key, long value) {
            int p = lastPhase(d, "phaseSet()");
            if (p < 0) return;
            append(p, key, value);
            publish();
        }

        /** The open phase or last lap of the span at depth {@code d}; none is misuse, a dropped one is not. */
        private int lastPhase(int d, String call) {
            int p = lastPhase == null ? NO_PHASE : lastPhase[d];
            if (p == NO_PHASE) ctx.misuse(call + " without a phase or lap");
            return p;
        }

        int lapOrdinal(int d) {
            return lapOrdinal == null ? 0 : lapOrdinal[d];
        }

        int nameOf(int r) {
            return rows[r >>> CHUNK_BITS].name[r & MASK];
        }

        /** {@link #nameOf} from any thread: -1 once the lane is retired and its chunks are gone. */
        int nameAt(int r) {
            Rows c = rows[r >>> CHUNK_BITS];
            return c == null ? -1 : c.name[r & MASK];
        }

        private void annotate(int r, int flags, String label) {
            rows[r >>> CHUNK_BITS].flags[r & MASK] |= flags;
            if (label == null || eventCount == MAX_EVENTS) return;
            String[] t = labels;
            if (t == null) LABELS.setRelease(this, t = new String[8]);
            else if (labelCount == t.length) LABELS.setRelease(this, t = Arrays.copyOf(t, 2 * labelCount));
            t[labelCount] = label;
            append(r, LABEL, labelCount++);
        }

        /**
         * Unlinks this lane from the slot's chain of open lane roots ({@code current}, then each
         * {@code saved}). Roots may close in any order, so the chain holds open lanes only and a lane
         * reopened later never links back into it.
         */
        private void exitRoot() {
            if (slot.current == this) {
                slot.current = saved;
            } else {
                for (Lane l = slot.current; l != null; l = l.saved) {
                    if (l.saved == this) {
                        l.saved = saved;
                        break;
                    }
                }
            }
            saved = null;
            idle();
        }

        void flag(int r, int bits) {
            if (!enter()) return;
            rows[r >>> CHUNK_BITS].flags[r & MASK] |= bits;
            publish();
            leave();
        }

        void event(int r, int key, long value) {
            if (!enter()) return;
            append(r, key, value);
            publish();
            leave();
        }

        /** Writes an event without publishing it; at the event cap counts a drop. */
        private void append(int r, int key, long value) {
            int e = eventCount;
            if (e == MAX_EVENTS) {
                dropped++;
                return;
            }
            Events[] t = events;
            if (t == null) events = t = new Events[CHUNKS];
            Events c = t[e >>> CHUNK_BITS];
            if (c == null) t[e >>> CHUNK_BITS] = c = EVENT_POOL.take();
            int i = e & MASK;
            c.row[i] = r;
            c.key[i] = key;
            c.value[i] = value;
            eventCount = e + 1;
        }

        /** Owner only: makes a lane whose root has closed writable for a late annotation; false once retired. */
        boolean enter() {
            return depth > 0 || activate();
        }

        void leave() {
            if (depth == 0) idle();
        }

        // The owner stores IDLE then reads retireRequested; release() stores retireRequested then CASes
        // IDLE->RETIRED. Both accesses are volatile, so at least one side sees the other and retires.
        // Unspent budget goes back to the trace first, so no idle lane strands credits.
        private void idle() {
            if (credit != 0) {
                ctx.refund(credit);
                credit = 0;
            }
            state = state & ~KIND;
            if (retireRequested) retire();
        }

        void requestRetire() {
            retireRequested = true;
            retire();
        }

        /**
         * IDLE -> RETIRED, once, by whichever of the owner and release() gets there; then the chunks go back
         * to the pool. A table entry is cleared before its chunk is given away, and a reader that still
         * picked one up discards what it read, because it finds the trace released (see Snapshot).
         */
        private void retire() {
            int s = state;
            if ((s & KIND) != IDLE || !STATE.compareAndSet(this, s, s | RETIRED)) return;
            for (int i = 0; i < CHUNKS && rows[i] != null; i++) {
                Rows c = rows[i];
                rows[i] = null;
                ROW_POOL.give(c);
            }
            Events[] t = events;
            for (int i = 0; t != null && i < CHUNKS && t[i] != null; i++) {
                Events c = t[i];
                t[i] = null;
                EVENT_POOL.give(c);
            }
            aggs = null;
            labels = null;
        }

        private void publish() {
            COMMIT.setRelease(this, (long) rowCount << 32 | eventCount);
        }

        long committed() {
            return (long) COMMIT.getAcquire(this);
        }

        /** Duration of row {@code r}, or its age while open; 0 once released. Safe from any thread. */
        long elapsed(int r) {
            if (ctx.released || retired()) return 0; // volatile reads before the commit, as in Shape
            long c = committed();
            if (r < 0 || r >= (int) (c >>> 32)) return 0;
            Rows k = rows[r >>> CHUNK_BITS];
            if (k == null) return 0;
            int i = r & MASK;
            long dur = (long) DUR.getOpaque(k.dur, i), start = k.start[i];
            VarHandle.acquireFence();
            if (ctx.released) return 0; // the chunk may have been recycled while it was read
            return dur != OPEN ? dur : Math.max(0, ctx.now() - start);
        }

        /**
         * The legacy phases under row {@code root} and its wall time, read in place with one {@code now}
         * for whatever is open; empty once released. Safe from any thread. Legacy rows are phases, which
         * a RETRO lap never adopts, so row order is their order in the stitched tree.
         */
        Tops tops(int root) {
            if (ctx.released || retired()) return Tops.EMPTY;
            try {
                long c = committed();
                int n = (int) (c >>> 32), nEvents = (int) c;
                long now = ctx.now();
                Tops t = new Tops();
                for (int r = root; r < n; r++) {
                    Rows k = rows[r >>> CHUNK_BITS];
                    int i = r & MASK;
                    if (r != root && (k.parent[i] != root || (k.flags[i] & LEGACY) == 0)) continue;
                    long d = (long) DUR.getOpaque(k.dur, i), s = k.start[i];
                    d = d != OPEN ? d : Math.max(0, now - s);
                    if (r == root) t.total = d;
                    else t.add(r, k.name[i], null, s, d);
                }
                String[] text = t.n == 0 ? null : (String[]) LABELS.getAcquire(this);
                for (int e = 0; text != null && e < nEvents; e++) {
                    Events k = events[e >>> CHUNK_BITS];
                    int i = e & MASK;
                    if ((k.key[i] & KIND_MASK) == LABEL) t.label(k.row[i], text[(int) k.value[i]]);
                }
                VarHandle.acquireFence();
                return ctx.released ? Tops.EMPTY : t;
            } catch (RuntimeException e) { // a chunk recycled under a read racing release()
                VarHandle.acquireFence();
                if (ctx.released) return Tops.EMPTY;
                throw e;
            }
        }
    }

    /**
     * The aggregates of one lane: its folded and aggregate-only spans, keyed by (anchor row, lap ordinal,
     * parent, name) where the parent is -1 for the anchor itself or the name of an enclosing aggregate-only
     * span. The owner appends entries and updates them in place; a reader takes the size with acquire and
     * reads the entries below it, exact once the lane is idle and best effort while it records.
     */
    static final class Aggs {
        static final int MAX = 4096, KEY = 4, ANCHOR = 0, LAP = 1, PARENT = 2, NAME = 3;
        static final int CELLS = 9, COUNT = 0, SUM = 1, MIN = 2, MAX_DUR = 3, FIRST = 4, LAST = 5, MAX_AT = 6, CPU = 7,
                FOLDED = 8; // occurrences folded for want of budget; the rest were aggregate-only
        private static final VarHandle SIZE;

        static {
            try {
                SIZE = MethodHandles.lookup().findVarHandle(Aggs.class, "size", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        final int[] keys;
        final long[] cells;
        private final int[] index; // entry + 1 by key hash, 0 free; owner only, at most half full
        @SuppressWarnings("unused") private int size; // via SIZE

        Aggs(int capacity) {
            keys = new int[capacity * KEY];
            cells = new long[capacity * CELLS];
            index = new int[capacity * 2];
        }

        /** Entries published so far; any thread. */
        int size() {
            return (int) SIZE.getAcquire(this);
        }

        boolean full() {
            return size == keys.length / KEY;
        }

        int find(int anchor, int lap, int parent, int name) {
            int mask = index.length - 1;
            for (int i = hash(anchor, lap, parent, name) & mask; ; i = (i + 1) & mask) {
                int e = index[i] - 1, k = e * KEY;
                if (e < 0) return -1;
                if (keys[k] == anchor && keys[k + LAP] == lap && keys[k + PARENT] == parent && keys[k + NAME] == name) return e;
            }
        }

        int create(int anchor, int lap, int parent, int name) {
            int e = size, k = e * KEY, b = e * CELLS;
            keys[k] = anchor;
            keys[k + LAP] = lap;
            keys[k + PARENT] = parent;
            keys[k + NAME] = name;
            cells[b + COUNT] = cells[b + SUM] = cells[b + MAX_AT] = cells[b + FOLDED] = 0;
            cells[b + MIN] = cells[b + FIRST] = Long.MAX_VALUE;
            cells[b + MAX_DUR] = cells[b + LAST] = Long.MIN_VALUE;
            cells[b + CPU] = -1;
            index(e);
            SIZE.setRelease(this, e + 1);
            return e;
        }

        private void index(int e) {
            int k = e * KEY, mask = index.length - 1, i = hash(keys[k], keys[k + LAP], keys[k + PARENT], keys[k + NAME]) & mask;
            while (index[i] != 0) i = (i + 1) & mask;
            index[i] = e + 1;
        }

        /** A copy with twice the room; the owner publishes it in place of this one. */
        Aggs grow() {
            Aggs a = new Aggs(2 * keys.length / KEY);
            System.arraycopy(keys, 0, a.keys, 0, keys.length);
            System.arraycopy(cells, 0, a.cells, 0, cells.length);
            for (int e = 0; e < size; e++) a.index(e);
            a.size = size;
            return a;
        }

        void add(int e, long start, long dur, long cpu, boolean folded) {
            long[] c = cells;
            int b = e * CELLS;
            c[b + SUM] += dur;
            if (dur < c[b + MIN]) c[b + MIN] = dur;
            if (dur > c[b + MAX_DUR]) {
                c[b + MAX_DUR] = dur;
                c[b + MAX_AT] = start;
            }
            if (start < c[b + FIRST]) c[b + FIRST] = start;
            if (start + dur > c[b + LAST]) c[b + LAST] = start + dur;
            if (cpu >= 0) c[b + CPU] = c[b + CPU] < 0 ? cpu : c[b + CPU] + cpu;
            if (folded) c[b + FOLDED]++;
            c[b + COUNT]++;
        }

        /**
         * Entries from {@code from} on of a lap that ended as a label-after row move under that row. The
         * index keeps their old keys, which no later lookup asks for: the lap's ordinal has moved on.
         */
        void reanchor(int from, int anchor, int lap, int row) {
            for (int e = from, k = from * KEY; e < size; e++, k += KEY) {
                if (keys[k] == anchor && keys[k + LAP] == lap) keys[k] = row;
            }
        }

        private static int hash(int anchor, int lap, int parent, int name) {
            int h = ((anchor * 31 + lap) * 31 + parent) * 31 + name;
            h *= 0x9E3779B9;
            return h ^ (h >>> 16);
        }
    }

    /**
     * A copy of one lane's committed rows, events and aggregates, taken by a {@link Snapshot}. The lane's
     * state is read before and after the copy: the owner writes only while ACTIVE and moves to a new
     * epoch whenever it leaves IDLE, so a lane found in the same IDLE state both times was copied
     * complete, final durations and flags included. Otherwise the copy is best effort and its spans are
     * marked {@link SpanView#live}.
     */
    static final class Shape {
        final int lane, rows, aggs;
        final String thread;
        boolean live;
        final long[] start, dur;
        final int[] name, parent;
        final short[] flags;
        final int[] from, attrKey;  // attributes of row r: [from[r], from[r + 1]), in append order
        final long[] attrValue;
        String[] label;             // by row; null in a lane without labels
        long[] link, wait;          // XPARENT and QWAIT by row; null in a lane without tasks
        long[] cpu;                 // CPU by row, -1 where not measured; null in a lane without
        final int[] aggKey;         // Aggs.KEY ints per aggregate that has ended at least once
        final long[] aggCell;       // Aggs.CELLS longs per aggregate
        int[] bodyLane, bodyRow;    // by handoff row: the task row linked to it, or -1
        boolean[] hidden;           // not in its parent's child list: a task drawn at its handoff, or a lost handoff
        int[] first, next;          // child lists over the nodes: rows, a virtual parent of the lane roots, aggregates

        /** Null for a lane that is retired or whose trace is released. */
        static Shape of(Lane l) {
            return of(l, l.state());
        }

        /** The copy given the state read before it; separate so a test can write in between. */
        static Shape of(Lane l, int before) {
            if ((before & Lane.KIND) == Lane.RETIRED || l.ctx.released) return null;
            Shape s = new Shape(l, l.committed());
            VarHandle.acquireFence(); // the copy's reads stay before the second read of the state
            s.live = (before & Lane.KIND) != Lane.IDLE || l.state() != before;
            return s;
        }

        private Shape(Lane l, long commit) {
            lane = l.id;
            thread = l.owner.getName();
            rows = (int) (commit >>> 32);
            int nEvents = (int) commit;
            start = new long[rows];
            dur = new long[rows];
            name = new int[rows];
            parent = new int[rows];
            flags = new short[rows];
            for (int o = 0; o < rows; o += Lane.CHUNK) {
                Rows c = l.rows[o >>> Lane.CHUNK_BITS];
                int n = Math.min(Lane.CHUNK, rows - o);
                System.arraycopy(c.start, 0, start, o, n);
                System.arraycopy(c.name, 0, name, o, n);
                System.arraycopy(c.parent, 0, parent, o, n);
                System.arraycopy(c.flags, 0, flags, o, n);
                for (int i = 0; i < n; i++) dur[o + i] = (long) Lane.DUR.getOpaque(c.dur, i);
            }

            Events[] events = l.events;
            int[] labelOf = null;
            from = new int[rows + 1];
            for (int e = 0; e < nEvents; e++) {
                Events k = events[e >>> Lane.CHUNK_BITS];
                int i = e & Lane.MASK, kind = k.key[i] & KIND_MASK, r = k.row[i];
                if (kind == ATTR_LONG || kind == ATTR_DOUBLE) {
                    from[r + 1]++;
                } else if (kind == LABEL) {
                    if (labelOf == null) Arrays.fill(labelOf = new int[rows], -1);
                    labelOf[r] = (int) k.value[i];
                } else if (kind == XPARENT) {
                    if (link == null) Arrays.fill(link = new long[rows], -1);
                    link[r] = k.value[i];
                } else if (kind == QWAIT) {
                    if (wait == null) wait = new long[rows];
                    wait[r] = k.value[i];
                } else if (kind == CPU) {
                    if (cpu == null) Arrays.fill(cpu = new long[rows], -1);
                    cpu[r] = k.value[i];
                }
            }
            for (int r = 0; r < rows; r++) from[r + 1] += from[r];
            attrKey = new int[from[rows]];
            attrValue = new long[from[rows]];
            int[] fill = Arrays.copyOf(from, rows);
            for (int e = 0; e < nEvents; e++) {
                Events k = events[e >>> Lane.CHUNK_BITS];
                int i = e & Lane.MASK, kind = k.key[i] & KIND_MASK;
                if (kind != ATTR_LONG && kind != ATTR_DOUBLE) continue;
                int j = fill[k.row[i]]++;
                attrKey[j] = k.key[i];
                attrValue[j] = k.value[i];
            }
            if (labelOf != null) {
                String[] text = (String[]) Lane.LABELS.getAcquire(l);
                label = new String[rows];
                for (int r = 0; r < rows; r++) if (labelOf[r] >= 0) label[r] = text[labelOf[r]];
            }

            Aggs a = (Aggs) Lane.AGGS.getAcquire(l);
            int n = a == null ? 0 : a.size(), kept = 0;
            aggKey = new int[n * Aggs.KEY];
            aggCell = new long[n * Aggs.CELLS];
            for (int e = 0; e < n; e++) {
                int k = e * Aggs.KEY, b = e * Aggs.CELLS;
                // not ended yet, or (in a live copy) moved under a lap row this copy does not have
                if (a.cells[b + Aggs.COUNT] == 0 || a.keys[k] >= rows) continue;
                System.arraycopy(a.keys, k, aggKey, kept * Aggs.KEY, Aggs.KEY);
                System.arraycopy(a.cells, b, aggCell, kept * Aggs.CELLS, Aggs.CELLS);
                kept++;
            }
            aggs = kept;
        }

        /** Start of a row (of an inline handoff, its task's), or the first start of an aggregate node. */
        long startOf(int x) {
            return x < rows ? start[inline(x) ? x + 1 : x] : aggCell[(x - rows - 1) * Aggs.CELLS + Aggs.FIRST];
        }

        /**
         * Whether handoff row {@code r} ran inline (caller-runs): its task began on the submitting thread
         * under the handoff's parent as the lane's next row. A task cancelled before it began never did.
         */
        boolean inline(int r) {
            return begun(r) && bodyLane[r] == lane && bodyRow[r] == r + 1 && parent[r + 1] == parent[r]
                    && (flags[r + 1] & UNRUN) == 0;
        }

        void hide(int r) {
            if (hidden == null) hidden = new boolean[rows];
            hidden[r] = true;
        }

        void link(int handoffRow, int laneId, int row) {
            if (bodyLane == null) {
                Arrays.fill(bodyLane = new int[rows], -1);
                bodyRow = new int[rows];
            }
            bodyLane[handoffRow] = laneId;
            bodyRow[handoffRow] = row;
        }

        boolean begun(int r) {
            return r < rows && bodyLane != null && bodyLane[r] >= 0;
        }

        /**
         * Child lists in row order. A RETRO lap adopts the run of preceding siblings that started at or
         * after it, back to the previous phase row, and takes the place of the first of them. Aggregates
         * join their parent's list in start order.
         */
        void tree() {
            int top = rows, nodes = rows + 1 + aggs;
            int[] last = new int[rows + 1], prev = new int[rows + 1];
            first = new int[nodes];
            next = new int[nodes];
            Arrays.fill(first, -1);
            Arrays.fill(next, -1);
            Arrays.fill(last, -1);
            for (int r = 0; r < rows; r++) {
                if (hidden != null && hidden[r]) continue;
                int p = parent[r] < 0 ? top : parent[r];
                if ((flags[r] & RETRO) != 0) {
                    int a = last[p], b = -1;
                    while (a >= 0 && (flags[a] & PHASE) == 0 && start[a] >= start[r]) {
                        b = a;
                        a = prev[a];
                    }
                    if (b >= 0) {
                        first[r] = b;
                        last[r] = last[p];
                        prev[b] = -1;
                        last[p] = a;
                        if (a >= 0) next[a] = -1;
                        else first[p] = -1;
                    }
                }
                next[r] = -1;
                prev[r] = last[p];
                if (last[p] >= 0) next[last[p]] = r;
                else first[p] = r;
                last[p] = r;
            }
            for (int e = 0; e < aggs; e++) attach(rows + 1 + e, aggregateParent(e));
        }

        /**
         * The anchor row, or for an aggregate nested in another aggregate-only span the earlier aggregate
         * of that name under the same anchor and lap (created when that span opened, so before this one).
         */
        private int aggregateParent(int e) {
            int k = e * Aggs.KEY, outer = aggKey[k + Aggs.PARENT];
            for (int f = 0; outer >= 0 && f < e; f++) {
                int j = f * Aggs.KEY;
                if (aggKey[j + Aggs.NAME] == outer && aggKey[j] == aggKey[k] && aggKey[j + Aggs.LAP] == aggKey[k + Aggs.LAP]) {
                    return rows + 1 + f;
                }
            }
            return aggKey[k];
        }

        private void attach(int x, int p) {
            long s = startOf(x);
            int a = -1;
            for (int c = first[p]; c >= 0 && startOf(c) <= s; c = next[c]) a = c;
            if (a < 0) {
                next[x] = first[p];
                first[p] = x;
            } else {
                next[x] = next[a];
                next[a] = x;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * An immutable copy of a trace stitched into one tree: its lanes, their aggregates and the lane-less
     * aggregates as they were when {@link Trace#snapshot()} took it. It references no lane, so it stays
     * complete after {@link Trace#release()}, and any thread may read it. Taken after release it is empty.
     *
     * <p>A handoff row is drawn as one record with the task linked to it (submit time, queue wait, the
     * task's duration, thread and children); a handoff without a task is drawn never started.
     */
    public static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot();

        final Shape[] shapes; // by lane id
        private final Totals totals;
        private final long now;
        final long wall;
        final int headerBytes;
        private final int misuses, dropped, folded;

        private Snapshot() {
            shapes = new Shape[0];
            totals = null;
            now = wall = 0;
            misuses = dropped = folded = headerBytes = 0;
        }

        /**
         * The trace now, or {@link #EMPTY} once it is released. A copy racing {@link Trace#release()} may
         * read chunks that went back to the pool; it is thrown away, also when what it read made it fail.
         */
        static Snapshot of(Context ctx) {
            if (ctx.released) return EMPTY;
            try {
                Snapshot s = new Snapshot(ctx);
                VarHandle.acquireFence();
                return ctx.released ? EMPTY : s;
            } catch (RuntimeException e) {
                VarHandle.acquireFence();
                if (ctx.released) return EMPTY;
                throw e;
            }
        }

        private Snapshot(Context ctx) {
            now = ctx.now();
            wall = ctx.wall;
            headerBytes = ctx.headerBytes;
            Lane[] lanes = ctx.lanesById();
            shapes = new Shape[lanes.length];
            for (int i = 0; i < lanes.length; i++) if (lanes[i] != null) shapes[i] = Shape.of(lanes[i]);
            totals = ctx.frozenTotals();
            misuses = ctx.misuses.get();
            dropped = ctx.dropped();
            folded = ctx.folded();
            for (long x : ctx.lost()) {
                Shape s = shape(x);
                if (s != null && (int) x < s.rows) s.hide((int) x);
            }
            // A task whose handoff row is not visible (a live render racing the submit) is left out.
            for (Shape s : shapes) {
                if (s == null || s.link == null) continue;
                for (int r = 0; r < s.rows; r++) {
                    long x = s.link[r];
                    if (x < 0) continue;
                    s.hide(r);
                    Shape t = shape(x);
                    int h = (int) x;
                    if (t != null && h < t.rows && (t.flags[h] & HANDOFF) != 0) t.link(h, s.lane, r);
                }
            }
            for (Shape s : shapes) if (s != null) s.tree();
        }

        /**
         * The shape of the lane in a {@code laneId << 32 | row} link; null if not read. A live render can
         * see a link to a lane registered after it read the registry.
         */
        private Shape shape(long link) {
            int id = (int) (link >>> 32);
            return id < shapes.length ? shapes[id] : null;
        }

        /**
         * Visits every span in pre-order with a reused, read-only view: the lane roots of each lane, by
         * lane id, are the top level. Spans open when the snapshot was taken are clamped to that instant.
         * Aggregates ({@link SpanView#aggregate()}) come among their parent's children in start order.
         */
        public void forEachSpan(SpanVisitor visitor) {
            SpanView v = new SpanView();
            int limit = 0; // a record per node at most, whatever the links say
            for (Shape s : shapes) if (s != null) limit += s.rows + s.aggs;
            int[] lane = new int[16], row = new int[16], id = new int[16]; // the path to the current record
            int n = 0;
            for (Shape top : shapes) {
                if (top == null) continue;
                for (int r = top.first[top.rows]; r >= 0 && n < limit; r = top.next[r]) {
                    int d = 0;
                    lane[0] = top.lane;
                    row[0] = r;
                    id[0] = n;
                    emit(v, top, r, -1, 0, n++, visitor);
                    while (true) {
                        Shape s = shapes[lane[d]];
                        int p = row[d];
                        if (s.begun(p)) { // a handoff's children are its task's
                            int b = s.bodyLane[p];
                            p = s.bodyRow[p];
                            s = shapes[b];
                        }
                        int c = n < limit ? s.first[p] : -1;
                        if (c < 0) { // no child: the next sibling of this record or of its nearest ancestor
                            for (; d > 0; d--) {
                                s = shapes[lane[d]];
                                c = n < limit ? s.next[row[d]] : -1;
                                if (c >= 0) break;
                            }
                            if (c < 0) break;
                            d--;
                        }
                        if (++d == lane.length) {
                            lane = Arrays.copyOf(lane, 2 * d);
                            row = Arrays.copyOf(row, 2 * d);
                            id = Arrays.copyOf(id, 2 * d);
                        }
                        lane[d] = s.lane;
                        row[d] = c;
                        id[d] = n;
                        emit(v, s, c, id[d - 1], d, n++, visitor);
                    }
                }
            }
        }

        private void emit(SpanView v, Shape s, int r, int parent, int depth, int id, SpanVisitor visitor) {
            v.id = id;
            v.parent = parent;
            v.depth = depth;
            v.label = null;
            v.attrFrom = v.attrTo = v.subFrom = v.subTo = 0;
            v.shape = v.subShape = s;
            v.live = s.live;
            v.inline = false;
            v.row = r > s.rows ? -1 : r;
            if (r > s.rows) {
                int k = (r - s.rows - 1) * Aggs.KEY, b = (r - s.rows - 1) * Aggs.CELLS;
                long[] c = s.aggCell;
                v.name = Name.byId(s.aggKey[k + Aggs.NAME]);
                v.start = c[b + Aggs.FIRST];
                v.duration = c[b + Aggs.SUM];
                v.cpu = c[b + Aggs.CPU];
                v.open = v.handoff = v.begun = false;
                v.flags = 0;
                v.wait = 0;
                v.aggregate = true;
                v.count = c[b + Aggs.COUNT];
                v.folded = c[b + Aggs.FOLDED];
                v.min = c[b + Aggs.MIN];
                v.max = c[b + Aggs.MAX_DUR];
                v.extent = c[b + Aggs.LAST] - v.start;
                v.maxAt = c[b + Aggs.MAX_AT] - v.start;
                visitor.visit(v);
                return;
            }
            int flags = s.flags[r];
            boolean handoff = (flags & HANDOFF) != 0, begun = handoff && s.begun(r);
            Shape b = begun ? shapes[s.bodyLane[r]] : s;
            int br = begun ? s.bodyRow[r] : r;
            long dur = b.dur[br];
            v.shape = b;
            v.live = b.live;
            v.name = Name.byId(b.name[br]);
            v.start = s.start[r];
            v.open = dur == Lane.OPEN;
            v.duration = v.open ? Math.max(0, now - b.start[br]) : dur;
            v.flags = begun ? flags | b.flags[br] : flags;
            v.handoff = handoff;
            v.begun = begun;
            v.inline = begun && s.inline(r);
            v.wait = begun ? (b.wait == null ? 0 : b.wait[br]) : handoff ? Math.max(0, now - v.start) : 0;
            v.cpu = b.cpu == null || handoff && !begun ? -1 : b.cpu[br];
            v.label = b.label == null ? null : b.label[br];
            v.attrFrom = b.from[br];
            v.attrTo = b.from[br + 1];
            v.subFrom = begun ? s.from[r] : 0;
            v.subTo = begun ? s.from[r + 1] : 0;
            v.aggregate = false;
            v.count = 1;
            v.folded = 0;
            v.min = v.max = v.extent = v.duration;
            v.maxAt = 0;
            visitor.visit(v);
        }

        /**
         * Count, sum and extremes of the spans of {@code name} that ended, recorded or folded into
         * aggregates in any lane, plus those added without a lane ({@link Trace#add}); and the queue waits
         * of its tasks, begun or cancelled, plus those added without a lane. Handoff entries and cancelled
         * tasks add no occurrence.
         */
        public Aggregate aggregate(Name name) {
            if (name == null) return Aggregate.EMPTY;
            int id = name.id;
            long count = 0, sum = 0, min = Long.MAX_VALUE, max = 0, waits = 0, waitSum = 0, waitMax = 0;
            for (Shape s : shapes) {
                if (s == null) continue;
                for (int r = 0; r < s.rows; r++) {
                    int f = s.flags[r];
                    if (s.name[r] != id || (f & HANDOFF) != 0 || (f & (RETRO | MISUSE)) == (RETRO | MISUSE)) continue;
                    boolean task = s.link != null && s.link[r] >= 0;
                    if (task) {
                        long w = s.wait == null ? 0 : s.wait[r];
                        waits++;
                        waitSum += w;
                        waitMax = Math.max(waitMax, w);
                    }
                    long d = s.dur[r];
                    if (d == Lane.OPEN || task && (f & CANCELLED) != 0) continue;
                    count++;
                    sum += d;
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                }
                for (int e = 0; e < s.aggs; e++) {
                    if (s.aggKey[e * Aggs.KEY + Aggs.NAME] != id) continue;
                    int b = e * Aggs.CELLS;
                    count += s.aggCell[b + Aggs.COUNT];
                    sum += s.aggCell[b + Aggs.SUM];
                    min = Math.min(min, s.aggCell[b + Aggs.MIN]);
                    max = Math.max(max, s.aggCell[b + Aggs.MAX_DUR]);
                }
            }
            return Totals.merge(totals, id, count, sum, min, max, waits, waitSum, waitMax);
        }

        /** Wall time of the root span: its duration, or while it was open its age when the snapshot was taken. */
        public long elapsedNanos() {
            Shape s = shapes.length == 0 ? null : shapes[0];
            if (s == null || s.rows == 0) return 0;
            return s.dur[0] != Lane.OPEN ? s.dur[0] : Math.max(0, now - s.start[0]);
        }

        /**
         * The {@code Tempo-Timing} header value, as specified in {@code FORMAT.md}, at most
         * {@link Options#headerBytes} long (see {@link Trace#toHeader()}); empty if taken after release.
         */
        public String toHeader() {
            return Header.of(this, headerBytes);
        }

        /** Like {@link Trace#forEachTop}, as the snapshot was taken. */
        public void forEachTop(TopVisitor visitor) {
            tops().forEach(visitor);
        }

        /** Like {@link Trace#toRuntime(int)}, as the snapshot was taken. */
        public String toRuntime(int digits) {
            return tops().runtime(digits);
        }

        /** Like {@link Trace#toRuntime(int, long)}. */
        public String toRuntime(int digits, long totalNanos) {
            return tops().runtime(digits, totalNanos);
        }

        /** Like {@link Trace#toServerTiming()}, as the snapshot was taken. */
        public String toServerTiming() {
            return tops().serverTiming();
        }

        private Tops tops() {
            Shape s = shapes.length == 0 ? null : shapes[0];
            if (s == null || s.rows == 0) return Tops.EMPTY;
            Tops t = new Tops();
            t.total = elapsedNanos();
            for (int r = 1; r < s.rows; r++) { // row order is tree order for phases, see Lane.tops
                if (s.parent[r] != 0 || (s.flags[r] & LEGACY) == 0) continue;
                long d = s.dur[r];
                t.add(r, s.name[r], s.label == null ? null : s.label[r], s.start[r], d != Lane.OPEN ? d : Math.max(0, now - s.start[r]));
            }
            return t;
        }

        int misuses() {
            return misuses;
        }

        int dropped() {
            return dropped;
        }

        /** Detail spans folded into aggregates for want of budget, open ones included. */
        int folded() {
            return folded;
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * The root's legacy phases (name, label, start, duration) and the root's wall time, read once for
     * the legacy renderers.
     */
    static final class Tops {
        static final Tops EMPTY = new Tops();

        int n;
        long total;
        int[] row = new int[0], name = new int[0];
        String[] label = new String[0];
        long[] start = new long[0], dur = new long[0];

        void add(int r, int nameId, String text, long startNanos, long durNanos) {
            if (n == row.length) {
                int m = Math.max(8, 2 * n);
                row = Arrays.copyOf(row, m);
                name = Arrays.copyOf(name, m);
                label = Arrays.copyOf(label, m);
                start = Arrays.copyOf(start, m);
                dur = Arrays.copyOf(dur, m);
            }
            row[n] = r;
            name[n] = nameId;
            label[n] = text;
            start[n] = startNanos;
            dur[n++] = durNanos;
        }

        /** Sets the label of row {@code r}, if it is one of these; rows are added in ascending order. */
        void label(int r, String text) {
            int i = Arrays.binarySearch(row, 0, n, r);
            if (i >= 0) label[i] = text;
        }

        void forEach(TopVisitor visitor) {
            for (int i = 0; i < n; i++) visitor.visit(Name.byId(name[i]), label[i], start[i], dur[i]);
        }

        String runtime(int digits) {
            return runtime(digits, total);
        }

        String runtime(int digits, long totalNanos) {
            StringBuilder b = new StringBuilder(16 + 32 * n);
            seconds(b, totalNanos, digits).append('s');
            for (int i = 0; i < n; i++) {
                seconds(b.append(i == 0 ? " = " : " + "), dur[i], digits).append("s (");
                b.append(label[i] != null ? label[i] : Name.byId(name[i]).text).append(')');
            }
            return b.toString();
        }

        String serverTiming() {
            StringBuilder b = new StringBuilder(16 + 24 * n);
            for (int i = 0; i < n; i++) {
                token(b, Name.byId(name[i]).text);
                fixed(b.append(";dur="), dur[i], 6, 3).append(", ");
            }
            return fixed(b.append("total;dur="), total, 6, 3).toString();
        }

        /**
         * Seconds as {@code String.format("%.<digits>f", nanos / 1e9)} would print them in a locale with a
         * decimal point, or for {@code digits < 0} as {@code Double.toString(nanos / 1e9)}. Below 10^15 ns
         * the shortest repr of that double is the exact decimal of the nanoseconds, so rounding the exact
         * value half up is what the formatter does; above, the double's own digits are rounded.
         */
        static StringBuilder seconds(StringBuilder b, long nanos, int digits) {
            if (digits < 0) return b.append(nanos / 1e9);
            if (nanos > -1_000_000_000_000_000L && nanos < 1_000_000_000_000_000L) return fixed(b, nanos, 9, digits);
            return b.append(new BigDecimal(Double.toString(nanos / 1e9)).setScale(digits, RoundingMode.HALF_UP).toPlainString());
        }

        private static final long[] POW10 = {1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L,
                10_000_000L, 100_000_000L, 1_000_000_000L};

        /** {@code value / 10^scale} (scale at most 9) with {@code digits} decimals, rounded half up. */
        static StringBuilder fixed(StringBuilder b, long value, int scale, int digits) {
            if (value < 0) {
                b.append('-');
                value = value == Long.MIN_VALUE ? Long.MAX_VALUE : -value;
            }
            int shown = Math.min(digits, scale);
            long unit = POW10[scale - shown], q = value / unit;
            if (2 * (value % unit) >= unit) q++;
            long p = POW10[shown];
            b.append(q / p);
            if (digits == 0) return b;
            b.append('.');
            for (long f = q % p, m = p / 10; m > 0; m /= 10) b.append((char) ('0' + f / m % 10));
            for (int i = shown; i < digits; i++) b.append('0');
            return b;
        }

        /** The name lower-cased, each run of characters outside {@code [a-z0-9]} replaced by one {@code -}. */
        static void token(StringBuilder b, String text) {
            boolean run = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
                if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                    b.append(c);
                    run = false;
                } else if (!run) {
                    b.append('-');
                    run = true;
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Writes a {@link Snapshot} as a {@code Tempo-Timing} v1 value in the canonical form of FORMAT.md §7.
     * Two walks over the stitched tree: the first numbers the threads and names the records use, the
     * second writes the records into the one builder. Records deeper than {@link #maxDepth}, and the
     * aggregates of aggregate-only spans nested in others (the wire has no children under an aggregate),
     * are left out with their subtrees and counted as dropped, like a non-finite attribute value. Every
     * occurrence in an aggregate on the wire counts as folded (FORMAT.md §4), as do folded spans still open.
     */
    static final class Header implements SpanVisitor {
        static final int MAX_DEPTH = 35, MIN_BYTES = 256;
        static final int KEEP = 8192; // a thread keeps its builder up to this capacity (one output String per render)
        private static final char[] DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray(),
                HEX = "0123456789ABCDEF".toCharArray();
        private static final int SHOWN = FAILED | CANCELLED | WAIT | HEALED | MISUSE;

        private final Snapshot snap;
        private final int maxDepth;
        private final boolean attributes;
        private final int[] nameIndex; // by name id: wire index + 1; -1 for an attribute key not numbered yet
        private final int[] laneIndex; // by lane id: wire thread index + 1, 0 for a lane no record uses
        private int[] order = new int[16], keys = new int[16]; // name ids by wire index; keys by first use
        private int names, nKeys;
        private final long[] cur = new long[MAX_DEPTH + 2];
        private final int[] threadAt = new int[MAX_DEPTH + 1];
        private final boolean[] aggregateAt = new boolean[MAX_DEPTH + 1];
        private StringBuilder out; // null during the first walk
        private int limit, records, skip;
        private long rootStart, rootDur, left, written, nonFinite; // left, written: spans, aggregates by count
        private long aggregated, foldedSeen; // occurrences in the aggregates written; folded ones in all aggregates

        private Header(Snapshot snap, int maxDepth, boolean attributes, int nameBound) {
            this.snap = snap;
            this.maxDepth = maxDepth;
            this.attributes = attributes;
            nameIndex = new int[nameBound];
            laneIndex = new int[snap.shapes.length];
        }

        /**
         * The value of {@code s}, at most {@code maxBytes} long when {@code maxBytes >= 256}. A value that
         * does not fit keeps the root and its children only, without attributes (ladder step 4 of FORMAT.md
         * §7.1); below 256 bytes, or if that does not fit either, it is the fallback. Empty without a root.
         */
        static String of(Snapshot s, int maxBytes) {
            ThreadSlot slot = SLOT.get();
            StringBuilder b = slot.text;
            slot.text = null;
            if (b == null) b = new StringBuilder(Math.max(64, Math.min(maxBytes, KEEP / 2)));
            String value = of(s, maxBytes, b);
            if (b.capacity() <= KEEP) slot.text = b;
            return value;
        }

        private static String of(Snapshot s, int maxBytes, StringBuilder b) {
            int names = nameBound(s);
            Header full = new Header(s, MAX_DEPTH, true, names);
            if (full.write(b, maxBytes)) return b.toString();
            if (full.records == 0) return "";
            if (maxBytes >= MIN_BYTES && new Header(s, 1, false, names).write(b, maxBytes)) return b.toString();
            b.setLength(0);
            b36(b.append("v1;x=0."), s.dropped() + full.left + full.written
                    + Math.max(0, s.folded() - full.foldedSeen)); // folded spans still open are in no aggregate
            b36(b.append(";T=%3F;N=%3F;S=00.0."), us(full.rootDur));
            return b.append(";e=1").toString();
        }

        /** More than the largest name id the snapshot uses: span names, attribute keys, aggregates. */
        private static int nameBound(Snapshot s) {
            int max = -1;
            for (Shape p : s.shapes) {
                if (p == null) continue;
                for (int r = 0; r < p.rows; r++) max = Math.max(max, p.name[r]);
                for (int k : p.attrKey) max = Math.max(max, k >>> KIND_BITS);
                for (int e = 0; e < p.aggs; e++) max = Math.max(max, p.aggKey[e * Aggs.KEY + Aggs.NAME]);
            }
            return max + 1;
        }

        /** Writes the value into {@code b}, giving up once it is longer than {@code max}; whether it fits. */
        private boolean write(StringBuilder b, int max) {
            snap.forEachSpan(this);
            if (records == 0) return false;
            for (int i = 0; i < nKeys; i++) if (nameIndex[keys[i]] < 0) number(keys[i]);
            for (int l = 0, n = 0; l < laneIndex.length; l++) if (laneIndex[l] != 0) laneIndex[l] = ++n;

            b.setLength(0);
            b.append("v1");
            if (snap.wall > 0) b36(b.append(";w="), snap.wall);
            // aggregates left out count as dropped only; spans folded for want of budget that are still
            // open are in no aggregate yet and count as folded
            long dropped = snap.dropped() + left + nonFinite, folded = aggregated + Math.max(0, snap.folded() - foldedSeen);
            if (folded > 0 || dropped > 0) b36(b36(b.append(";x="), folded).append('.'), dropped);
            boolean live = false;
            for (int l = 0; l < laneIndex.length; l++) {
                if (laneIndex[l] == 0 || !snap.shapes[l].live) continue;
                b36(b.append(live ? "." : ";l="), laneIndex[l] - 1);
                live = true;
            }
            b.append(";T=");
            for (int l = 0, n = 0; l < laneIndex.length; l++) {
                if (laneIndex[l] != 0) name(n++ == 0 ? b : b.append(':'), snap.shapes[l].thread);
            }
            b.append(";N=");
            for (int i = 0; i < names; i++) name(i == 0 ? b : b.append(':'), Name.byId(order[i]).text);
            b.append(";S=");
            out = b;
            limit = max;
            records = 0;
            skip = -1;
            snap.forEachSpan(this);
            b36(b.append(";e="), records);
            return b.length() <= max;
        }

        @Override
        public void visit(SpanView v) {
            int d = v.depth;
            if (skip >= 0 && d > skip || d > maxDepth || d == 0 && records > 0 || d > 0 && aggregateAt[d - 1]) {
                if (skip < 0 || d <= skip) skip = d; // left out with its subtree
                if (out == null) {
                    left += v.count;
                    foldedSeen += v.folded;
                }
                return;
            }
            skip = -1;
            aggregateAt[d] = v.aggregate;
            if (out == null) {
                foldedSeen += v.folded;
                if (v.aggregate) aggregated += v.count;
                count(v);
            } else if (out.length() <= limit) record(out, v);
            records++;
        }

        /** First walk: numbers the record's thread, name and attribute keys. */
        private void count(SpanView v) {
            if (records == 0) {
                rootStart = v.start;
                rootDur = v.duration;
            } else {
                written += v.count;
            }
            if (!unrun(v)) laneIndex[v.shape.lane] = 1;
            if (nameIndex[v.name.id] <= 0) number(v.name.id);
            for (int i = 0, n = attributes ? v.attributes() : 0; i < n; i++) {
                if (!first(v, i)) continue;
                int j = last(v, i), key = v.key(j) >>> KIND_BITS;
                if (!finite(v, j)) {
                    nonFinite++;
                } else if (nameIndex[key] == 0) {
                    nameIndex[key] = -1;
                    if (nKeys == keys.length) keys = Arrays.copyOf(keys, 2 * nKeys);
                    keys[nKeys++] = key;
                }
            }
        }

        private void number(int id) {
            if (names == order.length) order = Arrays.copyOf(order, 2 * names);
            order[names++] = id;
            nameIndex[id] = names;
        }

        /** Second walk: {@code depth nameIdx.startDelta.dur [@thread] [~wait] [*cpu] [+agg] [^flags] [:key=value]}. */
        private void record(StringBuilder b, SpanView v) {
            int d = v.depth;
            if (records > 0) b.append('/');
            // an inline handoff is a plain child from when it began (FORMAT.md §6.5)
            long start = us(v.start + (v.inline ? v.wait : 0) - rootStart), delta = Math.max(0, start - cur[d]);
            cur[d] = cur[d + 1] = cur[d] + delta;
            b36(b36(b36(b.append(DIGITS[d]), nameIndex[v.name.id] - 1).append('.'), delta).append('.'), us(v.duration));
            int thread = d == 0 ? 0 : unrun(v) ? threadAt[d - 1] : laneIndex[v.shape.lane] - 1;
            threadAt[d] = thread;
            if (d > 0 && thread != threadAt[d - 1]) b36(b.append('@'), thread);
            if (v.handoff && !v.aggregate && !v.inline) b36(b.append('~'), us(v.wait));
            if (v.cpu >= 0) b36(b.append('*'), us(v.cpu));
            if (v.aggregate) {
                b36(b36(b36(b.append('+'), v.count).append('.'), us(v.min)).append('.'), us(v.max));
                b36(b36(b.append('.'), us(v.extent)).append('.'), us(v.maxAt));
            }
            int f = v.flags;
            if ((f & SHOWN) != 0 || v.open || v.neverStarted()) {
                b.append('^');
                if ((f & FAILED) != 0) b.append('f');
                if ((f & CANCELLED) != 0) b.append('c');
                if (v.open) b.append('o');
                if (v.neverStarted()) b.append('n');
                if ((f & WAIT) != 0) b.append('w');
                if ((f & (HEALED | MISUSE)) != 0) b.append('m');
            }
            for (int i = 0, n = attributes ? v.attributes() : 0; i < n; i++) {
                if (!first(v, i)) continue;
                int j = last(v, i);
                if (!finite(v, j)) continue;
                b36(b.append(':'), nameIndex[v.key(j) >>> KIND_BITS] - 1).append('=');
                if ((v.key(j) & KIND_MASK) == ATTR_DOUBLE) number(b, Double.longBitsToDouble(v.value(j)));
                else b.append(v.value(j));
            }
        }

        /** A task cancelled before it began: no thread ran it, so it sits on its parent's (FORMAT.md §6.5). */
        private static boolean unrun(SpanView v) {
            return (v.flags & UNRUN) != 0;
        }

        /** Whether attribute {@code i} is the first of its key, which is written at its place with the last value. */
        private static boolean first(SpanView v, int i) {
            int key = v.key(i) >>> KIND_BITS;
            for (int k = 0; k < i; k++) if (v.key(k) >>> KIND_BITS == key) return false;
            return true;
        }

        private static int last(SpanView v, int i) {
            int key = v.key(i) >>> KIND_BITS, j = i;
            for (int k = i + 1, n = v.attributes(); k < n; k++) if (v.key(k) >>> KIND_BITS == key) j = k;
            return j;
        }

        private static boolean finite(SpanView v, int j) {
            return (v.key(j) & KIND_MASK) != ATTR_DOUBLE || Double.isFinite(Double.longBitsToDouble(v.value(j)));
        }

        static long us(long nanos) {
            return nanos <= 0 ? 0 : nanos / 1000;
        }

        /** Appends {@code v}, at least 0, in lowercase base 36. */
        static StringBuilder b36(StringBuilder b, long v) {
            if (v <= 0) return b.append('0');
            int from = b.length();
            for (; v > 0; v /= 36) b.append(DIGITS[(int) (v % 36)]);
            for (int i = from, j = b.length() - 1; i < j; i++, j--) {
                char c = b.charAt(i);
                b.setCharAt(i, b.charAt(j));
                b.setCharAt(j, c);
            }
            return b;
        }

        /**
         * Appends a name: {@code A-Z a-z 0-9 . -} as themselves, a space as {@code _}, every other UTF-8 byte
         * as {@code %XX}; an unpaired surrogate as {@code ?}, and a null or empty name as {@code %3F}.
         */
        static void name(StringBuilder b, String s) {
            if (s == null || s.isEmpty()) {
                escape(b, '?');
                return;
            }
            for (int i = 0, n = s.length(); i < n; i++) {
                int c = s.charAt(i);
                if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '-') {
                    b.append((char) c);
                } else if (c == ' ') {
                    b.append('_');
                } else if (c < 0x80) {
                    escape(b, c);
                } else if (c < 0x800) {
                    escape(b, 0xC0 | c >> 6);
                    escape(b, 0x80 | c & 0x3F);
                } else if (!Character.isSurrogate((char) c)) {
                    escape(b, 0xE0 | c >> 12);
                    escape(b, 0x80 | c >> 6 & 0x3F);
                    escape(b, 0x80 | c & 0x3F);
                } else if (Character.isHighSurrogate((char) c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                    int p = Character.toCodePoint((char) c, s.charAt(++i));
                    escape(b, 0xF0 | p >> 18);
                    escape(b, 0x80 | p >> 12 & 0x3F);
                    escape(b, 0x80 | p >> 6 & 0x3F);
                    escape(b, 0x80 | p & 0x3F);
                } else {
                    escape(b, '?');
                }
            }
        }

        private static void escape(StringBuilder b, int octet) {
            b.append('%').append(HEX[octet >> 4]).append(HEX[octet & 15]);
        }

        /**
         * Appends a finite double as {@code printf("%.6f")} would, then without trailing zeros and point,
         * {@code -0} as {@code 0}. The six-digit rounding is exact (half to even on the binary value):
         * one fused multiply-add tells whether the product's rounding moved it across a half, and a value
         * at an exact half, or too large for a long, goes through {@link BigDecimal}.
         */
        static void number(StringBuilder b, double v) {
            if (v == Math.rint(v) && Math.abs(v) < 0x1p62) {
                b.append((long) v); // -0.0 is 0
                return;
            }
            long q;
            double r = Math.rint(v * 1e6), e = Math.fma(v, 1e6, -r); // e: the exact v * 1e6 - r, rounded once
            if (Math.abs(r) >= 0x1p52 || Math.abs(e) == 0.5) {
                BigDecimal d = new BigDecimal(v).setScale(6, RoundingMode.HALF_EVEN);
                b.append(d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString());
                return;
            }
            q = (long) (e > 0.5 ? r + 1 : e < -0.5 ? r - 1 : r);
            if (q == 0) {
                b.append('0');
                return;
            }
            if (q < 0) {
                b.append('-');
                q = -q;
            }
            b.append(q / 1_000_000);
            long f = q % 1_000_000;
            if (f == 0) return;
            b.append('.');
            for (long m = 100_000; f != 0; m /= 10) {
                b.append((char) ('0' + f / m));
                f %= m;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** One request's recording. Not pooled. Reads stay valid after {@link #close()} until {@link #release()}. */
    public static final class Trace implements AutoCloseable {
        final Context ctx;
        private final Lane rootLane;
        private final Span root;

        Trace(Name name, Options options) {
            ctx = new Context(options == null ? Options.DEFAULTS : options);
            int flags = 0;
            if (name == null) {
                ctx.misuse("null name");
                name = Name.UNKNOWN;
                flags = MISUSE;
            }
            rootLane = ctx.register(new Lane(ctx, SLOT.get()));
            root = rootLane.openRoot(name, flags);
        }

        /** The root span, opened at {@link Tempo#start}. */
        public Span root() {
            return root;
        }

        public int level() {
            return ctx.level;
        }

        /**
         * Raises the level, once, before any detail span exists. Asking for the current level or a lower
         * one changes nothing. Returns whether the trace now records at {@code level} or above.
         */
        public boolean raise(int level) {
            int to = clampLevel(level);
            String refused;
            synchronized (ctx) {
                if (to <= ctx.level) return true;
                if (!ctx.raised && !ctx.detailSeen) {
                    ctx.raised = true;
                    ctx.level = to;
                    return true;
                }
                refused = ctx.raised ? "level raised twice" : "level raised after a detail span was recorded";
            }
            ctx.misuse(refused);
            return false;
        }

        /** Wall time of the root span: running time while open, its duration once closed, 0 after release. */
        public long elapsedNanos() {
            return rootLane.elapsed(root.row);
        }

        /** Ends the root span, healing any span still open. Idempotent on the owner thread. */
        @Override
        public void close() {
            if (Thread.currentThread() == rootLane.owner && rootLane.depth == 0) return;
            root.close();
        }

        /**
         * Returns the trace's buffers to the pool. Optional and idempotent; afterwards every read is empty
         * and never throws, so take a {@link #snapshot()} first to render later. A thread still inside a
         * task of this trace keeps its lane until the task ends; handoffs begun after release record nothing.
         */
        public void release() {
            ctx.release();
        }

        /**
         * Adds a listener for this trace, called with the duration in nanoseconds each time a span of
         * {@code name} ends, and for {@link #add}. Like {@link Name#sink} it runs on the ending thread
         * without a lock, and it makes the name always-on in this trace. Only on the starting thread and
         * before the root's first child span; later calls are misuse and ignored.
         */
        public Trace on(Name name, LongConsumer nanos) {
            if (name == null || nanos == null) {
                ctx.misuse("null listener");
            } else if (Thread.currentThread() != rootLane.owner) {
                ctx.misuse("listener added on a foreign thread");
            } else if (rootLane.depth != 1 || rootLane.rowCount != 1 || rootLane.framed) {
                ctx.misuse("listener added after the first child span");
            } else {
                ctx.on = new Listeners(ctx.on, name.id, nanos);
                ctx.listening = true;
            }
            return this;
        }

        /**
         * Adds one occurrence of {@code name} lasting {@code nanos} to {@link #aggregate(Name)}, without a
         * span or a lane, and calls its listeners on the calling thread. Thread-safe, at every level, also
         * after close; allocation-free once the trace has seen the name. Ignored after release.
         */
        public void add(Name name, long nanos) {
            if (ctx.released) return;
            long v = Math.max(0, nanos);
            ctx.totals().add(ctx.key(name), false, v);
            Throwable t = name != null && ctx.listening ? ctx.notify(name, v) : null;
            if (t != null && ctx.strict) throw Context.listenerThrew(t);
        }

        /** Adds a queue wait of {@code nanos} to {@link #aggregate(Name)}, like {@link #add}. */
        public void addWait(Name name, long nanos) {
            total(name, true, nanos);
        }

        /**
         * The trace clock now, as a token for {@link #waited}. Take it where a task is submitted and pass
         * it to the task; one clock read and nothing else.
         */
        public long submitted() {
            return ctx.now();
        }

        /** Adds the queue wait from {@code submitted}, a token of {@link #submitted()}, to now; returns it. */
        public long waited(Name name, long submitted) {
            long wait = Math.max(0, ctx.now() - submitted);
            total(name, true, wait);
            return wait;
        }

        private void total(Name name, boolean wait, long nanos) {
            if (ctx.released) return;
            ctx.totals().add(ctx.key(name), wait, Math.max(0, nanos));
        }

        /**
         * Count, sum and extremes of every span of {@code name} that ended: recorded, folded over the
         * budget, aggregate-only (an always-on name below the level, see {@link Name#aggregate()}) and
         * added through {@link #add}; and the queue waits of its tasks, of tasks left unrecorded at a cap,
         * and those added through {@link #addWait} and {@link #waited}. Exact once the contributing threads
         * are done, best effort while they run; empty after release. Reads the lanes in place: no copy,
         * one allocation, time linear in the trace's rows, events and aggregates.
         */
        public Aggregate aggregate(Name name) {
            return name == null ? Aggregate.EMPTY : ctx.aggregate(name.id);
        }

        /**
         * The {@code Tempo-Timing} header value, as specified in {@code FORMAT.md}: one line of at most
         * {@link Options#headerBytes} bytes. Over that limit only the root and its children are written,
         * without attributes; if that does not fit either (or the limit is below 256 bytes), the
         * fixed fallback {@code v1;x=0.<n>;T=%3F;N=%3F;S=00.0.<dur>;e=1}. Renders a {@link #snapshot()},
         * so spans still open end at one instant; empty after release.
         */
        public String toHeader() {
            return snapshot().toHeader();
        }

        /**
         * Visits every recorded span in pre-order with a reused, read-only view, from a {@link #snapshot()}.
         * Spans still open are clamped to one {@code now} taken for the whole walk. A handoff is one span on
         * the thread that ran its task, see {@link SpanView#handoff()}. Nothing is visited after release.
         */
        public void forEachSpan(SpanVisitor visitor) {
            snapshot().forEachSpan(visitor);
        }

        /**
         * An immutable copy of the trace as it is now, which stays readable after {@link #release()}: take
         * it before releasing when the trace is rendered later. Empty after release.
         */
        public Snapshot snapshot() {
            return Snapshot.of(ctx);
        }

        /**
         * Visits the legacy phases in start order: the root's children marked by {@link Span#done(Name,
         * String)} or {@link Span#legacy()}, with their label ({@code null} if none), start from the trace
         * start and duration; an open phase lasts until now. Reads the root's lane in place.
         */
        public void forEachTop(TopVisitor visitor) {
            rootLane.tops(root.row).forEach(visitor);
        }

        /**
         * The legacy {@code Runtime} text {@code <total>s = <d1>s (<label1>) + ... + <dn>s (<labeln>)}, or
         * {@code <total>s} without legacy phases (see {@link #forEachTop}). The total is the root's wall time,
         * up to now while it is open. Each phase prints its label, or its name without one. Durations are
         * seconds with {@code digits} fixed decimals, rounded half up, or with {@code digits = -1} as
         * {@code Double.toString(nanos / 1e9)} prints them. After release: just the total, {@code 0}.
         */
        public String toRuntime(int digits) {
            return rootLane.tops(root.row).runtime(digits);
        }

        /** {@link #toRuntime(int)} with the total given, so it can match a total read once for several headers. */
        public String toRuntime(int digits, long totalNanos) {
            return rootLane.tops(root.row).runtime(digits, totalNanos);
        }

        /**
         * A W3C {@code Server-Timing} value: one {@code <token>;dur=<ms>} per legacy phase (see
         * {@link #forEachTop}), then {@code total;dur=<ms>}, joined by {@code ", "}. The token is the
         * phase's name lower-cased with each run of characters outside {@code [a-z0-9]} replaced by
         * {@code -}; durations are milliseconds with three decimals, rounded half up.
         */
        public String toServerTiming() {
            return rootLane.tops(root.row).serverTiming();
        }

        /**
         * Passes {@code <label>: } and the {@link #toRuntime(int) toRuntime(-1)} text to {@code out} as one
         * line when the root closes, or at once if it has closed; no prefix for a {@code null} label. Only
         * on the starting thread; a throw from {@code out} is handled like a listener's ({@link #on}).
         */
        public Trace printOnClose(Consumer<String> out, String label) {
            if (out == null) {
                ctx.misuse("null printer");
            } else if (Thread.currentThread() != rootLane.owner) {
                ctx.misuse("printer added on a foreign thread");
            } else {
                Consumer<String> p = label == null ? out : line -> out.accept(label + ": " + line);
                if (rootLane.depth > 0) {
                    ctx.printer = p;
                } else {
                    Throwable t = rootLane.print(p);
                    if (t != null && ctx.strict) throw Context.listenerThrew(t);
                }
            }
            return this;
        }

        int misuses() {
            return ctx.misuses.get();
        }

        int dropped() {
            return ctx.dropped();
        }

        /** Detail spans folded into aggregates for want of budget so far, open ones included. */
        int folded() {
            return ctx.folded();
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Same-thread handle to one open span. Handles are cached per depth and reused, so a handle is
     * valid from its open until the next span opens at the same depth. Calls on a foreign thread or
     * after close are ignored and counted as misuse; methods that open spans then return {@link #NOOP}.
     */
    public static final class Span implements AutoCloseable {
        final Lane lane;
        final Context ctx;
        final Thread owner;
        final int depth;
        int row;    // Lane.FRAME for an aggregate-only span, which has the next three
        Name frame;
        int agg;    // its entry in the lane's aggregates, or -1
        boolean folded; // folded for want of budget, not aggregate-only
        long time;  // start while open, then duration

        Span(Lane lane, Context ctx, int depth) {
            this.lane = lane;
            this.ctx = ctx;
            this.owner = lane == null ? null : lane.owner;
            this.depth = depth;
        }

        /** Opens a child span; always recorded. Its parent is the innermost open span of this lane. */
        public Span span(Name name) {
            return open(name, 0);
        }

        /** Looks the name up in a global table capped at 4096 dynamic names; overflow maps to {@code <other>}. */
        public Span span(String name) {
            if (!live()) return NOOP;
            Name n = Name.intern(name, true);
            if (n.text.equals(name)) return lane.push(n, 0);
            ctx.misuse(n == Name.OTHER ? "dynamic name table full" : "empty name");
            return lane.push(n, MISUSE);
        }

        /**
         * A child span recorded at {@link #DETAIL} and above while the trace's budget lasts
         * ({@link Options#budget}); past it, folded. Below the level, {@link #NOOP}, unless the name is
         * always-on ({@link Name#aggregate()}, {@link Name#sink}, {@link Trace#on}): then aggregate-only.
         * A folded or aggregate-only span writes no row: it times itself into an aggregate of its lane
         * (see {@link SpanView#aggregate()}), {@link Trace#aggregate(Name)} and the listeners. Spans opened
         * inside it are recorded under its nearest recorded ancestor; flags, attributes and phases on it
         * are ignored, {@link #cpu()} adds to the aggregate's CPU time.
         */
        public Span detail(Name name) {
            if (ctx.level < DETAIL) return name != null && (name.marked || ctx.on != null) ? below(name) : NOOP;
            if (!ctx.detailSeen) ctx.detailSeen = true;
            return budgeted(name, DETAIL_ROW);
        }

        /** A child span recorded at {@link #DEEP}; below, like {@link #detail} below {@link #DETAIL}. */
        public Span deep(Name name) {
            if (ctx.level < DEEP) return name != null && (name.marked || ctx.on != null) ? below(name) : NOOP;
            if (!ctx.detailSeen) ctx.detailSeen = true;
            return budgeted(name, DEEP_ROW);
        }

        private Span below(Name name) {
            return (name.marked || ctx.listens(name.id)) && live() ? lane.frame(name) : NOOP;
        }

        private Span open(Name name, int flags) {
            if (!live()) return NOOP;
            if (name == null) {
                ctx.misuse("null name");
                return lane.push(Name.UNKNOWN, flags | MISUSE);
            }
            return lane.push(name, flags);
        }

        /** Counts against the trace's budget; over it the span folds into an aggregate. */
        private Span budgeted(Name name, int flags) {
            if (!live()) return NOOP;
            if (name != null) return lane.budgeted(name, flags);
            ctx.misuse("null name");
            return lane.budgeted(Name.UNKNOWN, flags | MISUSE);
        }

        /** Runs {@code body} inside a child span; a throwable flags the span failed and is rethrown unwrapped. */
        public <E extends Exception> void run(Name name, ThrowingRunnable<E> body) throws E {
            Span s = span(name);
            try {
                body.run();
            } catch (Throwable t) {
                s.fail();
                throw t;
            } finally {
                s.close();
            }
        }

        /** Like {@link #run} for a body with a result. */
        public <T, E extends Exception> T call(Name name, ThrowingSupplier<T, E> body) throws E {
            Span s = span(name);
            try {
                return body.get();
            } catch (Throwable t) {
                s.fail();
                throw t;
            } finally {
                s.close();
            }
        }

        /**
         * Starts a phase of this span, label first: ends the open phase or any span still open below this
         * one (a span left open is healed and counted as misuse), then opens the phase at the same instant.
         * Spans opened until the next boundary nest under the phase. Returns this span.
         */
        public Span phase(Name name) {
            if (recorded()) {
                if (name != null) lane.phase(depth, name, 0);
                else lane.phase(depth, nullName(), MISUSE);
            }
            return this;
        }

        /**
         * Ends a lap of this span, label after. If a {@link #phase} is open, ends that phase under its own
         * name (a different {@code name} is kept as a zero-length misuse entry). Otherwise records a lap
         * from the last phase boundary, or this span's start, to now; the spans opened in that time are
         * shown under the lap. Returns this span.
         */
        public Span done(Name name) {
            return done(name, 0, null);
        }

        /**
         * Like {@link #done(Name)}, and selects the lap for the legacy headers, shown there as
         * {@code legacyLabel} or, if that is {@code null}, as the name.
         */
        public Span done(Name name, String legacyLabel) {
            return done(name, LEGACY, legacyLabel);
        }

        private Span done(Name name, int flags, String label) {
            if (recorded()) {
                if (name == null) nullName();
                lane.done(depth, name, flags, label);
            }
            return this;
        }

        /** Selects the open phase or the last lap of this span for the legacy headers. */
        public Span legacy() {
            if (recorded()) lane.legacy(depth);
            return this;
        }

        /** Attaches a number to the open phase or the last lap of this span. */
        public Span phaseSet(Name key, long value) {
            if (recorded()) lane.phaseSet(depth, key(key) << KIND_BITS | ATTR_LONG, value);
            return this;
        }

        /**
         * Measures the thread's CPU time from now until the span closes, when the trace measures CPU
         * ({@link Options#cpu}); see {@link SpanView#cpuNanos()}. A read costs a system call. On a folded
         * or aggregate-only span it adds to the aggregate's CPU time.
         */
        public Span cpu() {
            if (live() && ctx.cpu()) lane.sampleCpu(depth);
            return this;
        }

        /**
         * Runs {@code body}, then {@link #done(Name) done(name)}. If the body throws, the lap is recorded
         * as failed, never for the legacy headers, and the throwable is rethrown unwrapped.
         */
        public <T, E extends Exception> T lap(Name name, ThrowingSupplier<T, E> body) throws E {
            T result;
            try {
                result = body.get();
            } catch (Throwable t) {
                try {
                    done(name, FAILED, null);
                } catch (AssertionError misuse) { // strict mode must not hide the body's throwable
                    if (misuse != t) t.addSuppressed(misuse);
                }
                throw t;
            }
            done(name);
            return result;
        }

        /** Phase boundaries of this span so far; separates the laps for aggregation. */
        int lapOrdinal() {
            return lane == null ? 0 : lane.lapOrdinal(depth);
        }

        private Name nullName() {
            ctx.misuse("null name");
            return Name.UNKNOWN;
        }

        /** Attaches a number to this span, also after it closed (e.g. in a catch block). */
        public Span set(Name key, long value) {
            if (owned() && row != Lane.FRAME) lane.event(row, key(key) << KIND_BITS | ATTR_LONG, value);
            return this;
        }

        public Span set(Name key, double value) {
            if (owned() && row != Lane.FRAME) lane.event(row, key(key) << KIND_BITS | ATTR_DOUBLE, Double.doubleToRawLongBits(value));
            return this;
        }

        private int key(Name key) {
            return ctx.key(key);
        }

        /**
         * Records the submit of a task to another thread: a handoff entry under the innermost open span,
         * and the handle the task {@link Handoff#begin() begins} with. Allocates that one small object.
         * Always recorded, whatever the level; guard with {@link #detailed()} where handoffs are detail.
         */
        public Handoff handoff(Name name) {
            if (!live()) return Handoff.NOOP;
            return name != null ? lane.handoff(name, 0) : lane.handoff(nullName(), MISUSE);
        }

        /** Marks the span failed. Works after close as well, so a catch block can call it. */
        public Span fail() {
            return flag(FAILED);
        }

        /** Marks the span failed; only the flag is recorded, not the throwable. */
        public Span fail(Throwable cause) {
            return flag(FAILED);
        }

        /** Marks the span cancelled or rejected. */
        public Span cancel() {
            return flag(CANCELLED);
        }

        /** Marks the span as a blocking wait (join, take, get). */
        public Span waiting() {
            return flag(WAIT);
        }

        private Span flag(int bits) {
            if (owned() && row != Lane.FRAME) lane.flag(row, bits);
            return this;
        }

        /** Running time while open, the duration once closed; 0 on {@link #NOOP} and after release. */
        public long elapsedNanos() {
            return lane == null ? 0 : row == Lane.FRAME ? lane.frameElapsed(this) : lane.elapsed(row);
        }

        public boolean recording() {
            return this != NOOP;
        }

        /** Whether the trace records {@link #DETAIL} spans; guards work done only for detail. */
        public boolean detailed() {
            return ctx.level >= DETAIL;
        }

        /** Ends the span and any span still open above it (flagged healed, counted as misuse). */
        @Override
        public void close() {
            if (live()) lane.close(depth);
        }

        /** {@link #live()} and has a row: phases, laps and CPU time do nothing on an aggregate-only span. */
        private boolean recorded() {
            return live() && row != Lane.FRAME;
        }

        /** Owner thread and still open. */
        private boolean live() {
            if (Thread.currentThread() == owner && depth < lane.depth) return true;
            if (this != NOOP) ctx.misuse(Thread.currentThread() == owner ? "span used after close" : "span used on a foreign thread");
            return false;
        }

        /** Owner thread; the span may be closed. */
        private boolean owned() {
            if (Thread.currentThread() == owner) return true;
            if (this != NOOP) ctx.misuse("span used on a foreign thread");
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------

    @FunctionalInterface
    public interface SpanVisitor {
        void visit(SpanView span);
    }

    /** Receives the legacy phases of a trace, see {@link Trace#forEachTop}. */
    @FunctionalInterface
    public interface TopVisitor {
        void visit(Name name, String label, long startNanos, long durationNanos);
    }

    /** Read-only view of one recorded span, reused across {@link SpanVisitor#visit} calls. */
    public static final class SpanView {
        Shape shape, subShape; // the span's lane; for a begun handoff the submitter's, whose attributes come first
        int row; // its row in subShape (for a handoff, the handoff row); -1 for an aggregate
        int id, parent, depth, flags, attrFrom, attrTo, subFrom, subTo;
        Name name;
        String label;
        long start, duration, wait, cpu, count, min, max, extent, maxAt;
        long folded; // of count, the occurrences folded for want of budget
        boolean open, live, handoff, begun, aggregate;
        // A handoff run inline (caller-runs): begun on the submitting thread under the handoff's parent before
        // that thread recorded anything else; never a task cancelled or rejected before it began.
        boolean inline;

        SpanView() {}

        /** Index in visiting order; the root is 0. */
        public int id() { return id; }

        /** Id of the parent span, -1 for the root. */
        public int parent() { return parent; }

        public int depth() { return depth; }

        public Name name() { return name; }

        /** Nanoseconds from the trace start; for a {@link #handoff()} the submit time. */
        public long startNanos() { return start; }

        /**
         * Duration; for an open span the time up to the walk's {@code now}. For a handoff its task's run
         * time; for an {@link #aggregate()} the sum of its occurrences.
         */
        public long durationNanos() { return duration; }

        /**
         * Spans of one name folded into one entry: aggregate-only spans and detail spans past the budget,
         * per lane, anchor and lap. Its start is the first occurrence's; it has no flags and no attributes.
         */
        public boolean aggregate() { return aggregate; }

        /** Occurrences of an {@link #aggregate()}; 1 for a span. */
        public long count() { return count; }

        public long minNanos() { return min; }

        public long maxNanos() { return max; }

        /** Wall time from the first occurrence's start to the last one's end; a span's duration. */
        public long extentNanos() { return extent; }

        /** Start of the slowest occurrence, from {@link #startNanos()}; 0 for a span. */
        public long maxAtNanos() { return maxAt; }

        /**
         * A task handed to another thread ({@link Span#handoff}): it was submitted at {@link #startNanos()},
         * waited {@link #waitNanos()} and ran for {@link #durationNanos()} on {@link #thread()}; its
         * children are the task's spans. A task begun on the submitting thread itself is one too.
         */
        public boolean handoff() { return handoff; }

        /** A handoff whose task has not begun (yet): no thread ran it, nor cancelled it. */
        public boolean neverStarted() { return handoff && !begun; }

        /** Queue wait of a handoff, submit to begin (or cancel); still counting if it never started. 0 for other spans. */
        public long waitNanos() { return wait; }

        public boolean open() { return open; }

        /**
         * CPU time of the thread while the span ran, or -1 if not measured (see {@link Options#cpu}).
         * Wall minus CPU is the time off CPU: waiting, blocked or descheduled. Measured for closed spans
         * only; for an aggregate the sum over its occurrences that measured it.
         */
        public long cpuNanos() { return cpu; }

        /** The span's lane was recording when the walk reached it, so its rows are best effort. */
        boolean live() { return live; }

        public boolean failed() { return (flags & FAILED) != 0; }

        /** Cancelled, or for a handoff: cancelled or rejected before it began. */
        public boolean cancelled() { return (flags & CANCELLED) != 0; }

        public boolean waiting() { return (flags & WAIT) != 0; }

        /** Closed implicitly by an ancestor's close. */
        public boolean healed() { return (flags & HEALED) != 0; }

        /** Recorded through a misused call, e.g. with a null name or a {@code done} that names another phase. */
        public boolean misuse() { return (flags & MISUSE) != 0; }

        /** A phase ({@link Span#phase}) or a lap ({@link Span#done}, {@link Span#lap}) of its parent. */
        public boolean phase() { return (flags & PHASE) != 0; }

        /** A lap recorded label after; its children are the spans opened during it. */
        boolean retro() { return (flags & RETRO) != 0; }

        /** Selected for the legacy headers. */
        public boolean legacy() { return (flags & LEGACY) != 0; }

        /** The legacy label given to {@link Span#done(Name, String)}, or {@code null}. */
        public String label() { return label; }

        /** {@link #COARSE}, {@link #DETAIL} or {@link #DEEP}. */
        public int level() {
            return (flags & DEEP_ROW) != 0 ? DEEP : (flags & DETAIL_ROW) != 0 ? DETAIL : COARSE;
        }

        /** The thread that ran the span; for a handoff that never started, the submitting thread. */
        public String thread() { return shape.thread; }

        /** Attributes; a handoff has those set at submit ({@link Handoff#set}) first, then its task's. */
        public int attributes() { return (subTo - subFrom) + (attrTo - attrFrom); }

        public Name attributeKey(int i) { return Name.byId(key(i) >>> KIND_BITS); }

        public boolean attributeIsDouble(int i) { return (key(i) & KIND_MASK) == ATTR_DOUBLE; }

        /** The value of a long attribute, or a double attribute truncated. */
        public long longAttribute(int i) {
            long v = value(i);
            return attributeIsDouble(i) ? (long) Double.longBitsToDouble(v) : v;
        }

        public double doubleAttribute(int i) {
            long v = value(i);
            return attributeIsDouble(i) ? Double.longBitsToDouble(v) : v;
        }

        private int key(int i) {
            int j = index(i);
            return i < subTo - subFrom ? subShape.attrKey[j] : shape.attrKey[j];
        }

        private long value(int i) {
            int j = index(i);
            return i < subTo - subFrom ? subShape.attrValue[j] : shape.attrValue[j];
        }

        private int index(int i) {
            if (i < 0 || i >= attributes()) throw new IndexOutOfBoundsException("attribute " + i);
            int sub = subTo - subFrom;
            return i < sub ? subFrom + i : attrFrom + i - sub;
        }
    }

    /**
     * A task handed from one thread to another, created by {@link Span#handoff} on the submitting thread.
     * The task calls {@link #begin()} where it starts to run, or is wrapped with {@link #runnable},
     * {@link #callable} or {@link #supplier}; a task that will not run is {@link #cancel() cancelled} or
     * {@link #reject() rejected}. Each handoff is used once and never reused, so a straggler cannot
     * record into another request.
     */
    public static final class Handoff {
        /** The handoff of {@link Tempo#NOOP}: begins {@link Tempo#NOOP} and wraps nothing. */
        public static final Handoff NOOP = new Handoff(null, -1, 0);

        private static final int NEW = 0, BEGUN = 1, ENDED = 2; // ENDED: cancelled or rejected
        private static final VarHandle STATE;

        static {
            try {
                STATE = MethodHandles.lookup().findVarHandle(Handoff.class, "state", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        // Header plus these four fields is 32 B with compressed oops.
        final Lane lane; // the submitter's
        final int row;   // its handoff row
        final long submit;
        private volatile int state;

        Handoff(Lane lane, int row, long submit) {
            this.lane = lane;
            this.row = row;
            this.submit = submit;
        }

        /**
         * Starts the task on the calling thread and returns its span, which the task closes; the queue
         * wait since the submit is recorded with it. On a thread already inside the trace (the task runs
         * inline) the span is a child of that thread's innermost open span, otherwise the root of the
         * thread's lane, and {@link Tempo#current()} returns it until it closes. {@link Tempo#NOOP} after
         * release, past the lane cap, and on a second begin or a begin after cancel (both misuse).
         */
        public Span begin() {
            if (!claim(BEGUN)) return Tempo.NOOP;
            return task(true);
        }

        /** Marks the task cancelled before it began, e.g. by an early exit. A no-op once it has begun. */
        public void cancel() {
            if (claim(ENDED)) task(false);
        }

        /** Marks the task rejected by the executor; recorded like {@link #cancel()}. */
        public void reject() {
            cancel();
        }

        /** Attaches a number to the handoff at submit, e.g. the executor's queue length. Submitting thread only. */
        public Handoff set(Name key, long value) {
            if (lane == null) return this;
            if (Thread.currentThread() == lane.owner) lane.event(row, lane.ctx.key(key) << KIND_BITS | ATTR_LONG, value);
            else lane.ctx.misuse("handoff set on a foreign thread");
            return this;
        }

        /** Wraps {@code task} in {@link #begin()} and close; a throwable marks the span failed and is rethrown. */
        public Runnable runnable(Runnable task) {
            if (lane == null) return task;
            return () -> {
                Span s = begin();
                try {
                    task.run();
                } catch (Throwable t) {
                    s.fail();
                    throw t;
                } finally {
                    s.close();
                }
            };
        }

        /** Like {@link #runnable} for a {@link Callable}. */
        public <V> Callable<V> callable(Callable<V> task) {
            if (lane == null) return task;
            return () -> {
                Span s = begin();
                try {
                    return task.call();
                } catch (Throwable t) {
                    s.fail();
                    throw t;
                } finally {
                    s.close();
                }
            };
        }

        /** Like {@link #runnable} for a {@link Supplier}. */
        public <V> Supplier<V> supplier(Supplier<V> task) {
            if (lane == null) return task;
            return () -> {
                Span s = begin();
                try {
                    return task.get();
                } catch (Throwable t) {
                    s.fail();
                    throw t;
                } finally {
                    s.close();
                }
            };
        }

        private boolean claim(int to) {
            if (lane == null || lane.ctx.released) return false;
            if (STATE.compareAndSet(this, NEW, to)) return true;
            if (to == BEGUN) lane.ctx.misuse(state == BEGUN ? "handoff begun twice" : "handoff begun after cancel");
            return false;
        }

        private Span task(boolean run) {
            Context ctx = lane.ctx;
            Lane l = laneHere(ctx);
            Span s = l == null ? null : l.task(this, run);
            if (s == null) ctx.lost(this, l == null);
            return s == null ? Tempo.NOOP : s;
        }

        /**
         * The calling thread's lane for the task: the current lane if it belongs to this trace (inline),
         * else an idle lane of this thread for the trace. The trace's root lane is never reopened for a
         * task, so the root handle stays the root's. Null at the lane cap.
         */
        private Lane laneHere(Context ctx) {
            ThreadSlot slot = SLOT.get();
            Lane cur = slot.current;
            if (cur != null && cur.ctx == ctx) return cur;
            if (lane.owner == Thread.currentThread() && lane.depth == 0 && lane.id != 0) return lane;
            return slot.lane(ctx);
        }
    }

    /** Count, sum and extremes of a name's occurrences, and of their queue waits. Zero when there were none. */
    public static final class Aggregate {
        static final Aggregate EMPTY = new Aggregate(0, 0, 0, 0, 0, 0, 0);

        private final long count, sum, min, max, waits, waitSum, waitMax;

        Aggregate(long count, long sum, long min, long max, long waits, long waitSum, long waitMax) {
            this.count = count;
            this.sum = sum;
            this.min = min;
            this.max = max;
            this.waits = waits;
            this.waitSum = waitSum;
            this.waitMax = waitMax;
        }

        public long count() { return count; }

        public long sumNanos() { return sum; }

        public long minNanos() { return min; }

        public long maxNanos() { return max; }

        /** Queue waits recorded for the name; independent of {@link #count()}. */
        public long waitCount() { return waits; }

        public long waitSumNanos() { return waitSum; }

        public long waitMaxNanos() { return waitMax; }

        @Override
        public String toString() {
            return "count=" + count + " sum=" + sum + " min=" + min + " max=" + max
                    + " waits=" + waits + " waitSum=" + waitSum + " waitMax=" + waitMax;
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable<E extends Exception> {
        void run() throws E;
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T, E extends Exception> {
        T get() throws E;
    }
}
