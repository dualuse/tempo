package io.github.dualuse.tempo;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Phase profiling for request-shaped work.
 *
 * <p>A {@link Trace} records a tree of {@link Span}s into per-thread primitive buffers. Recording
 * never throws into the caller (unless {@link Options#strict(boolean)} asks for it), allocates
 * nothing per span once warm, and below the trace's level {@link Span#detail(Name)} and
 * {@link Span#deep(Name)} cost one branch.
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
            LEGACY = 1 << 10;

    // Event kinds, in the low KIND_BITS of an event's key; the rest is a Name id for attributes.
    static final int KIND_BITS = 4, KIND_MASK = (1 << KIND_BITS) - 1,
            XPARENT = 1, QWAIT = 2, CPU = 3, ATTR_LONG = 4, ATTR_DOUBLE = 5, LABEL = 6;

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

    static int clampLevel(int level) {
        return Math.max(COARSE, Math.min(DEEP, level));
    }

    // ---------------------------------------------------------------------------------------------

    /** An interned span or attribute name. Names are constants; dynamic data belongs in attributes. */
    public static final class Name {
        static final int DYNAMIC_CAP = 4096;
        private static final ConcurrentHashMap<String, Name> TABLE = new ConcurrentHashMap<>();
        private static volatile Name[] byId = new Name[256];
        private static int size, dynamicSize; // guarded by TABLE
        static final Name UNKNOWN = intern("?", false), OTHER = intern("<other>", false);

        public final int id;
        final String text;

        private Name(int id, String text) {
            this.id = id;
            this.text = text;
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

        int level = COARSE, budget = 512, headerBytes = 3000, lanes = 256;
        boolean cpu, strict;
        LongSupplier clock = SYSTEM_CLOCK;

        /** {@link #COARSE} (default), {@link #DETAIL} or {@link #DEEP}; out-of-range values are clamped. */
        public Options level(int level) {
            this.level = clampLevel(level);
            return this;
        }

        /** Detail spans recorded individually per trace before they fold into aggregates; default 512. */
        public Options budget(int detailSpans) {
            this.budget = Math.max(0, detailSpans);
            return this;
        }

        /** Samples thread CPU time; default off. */
        public Options cpu(boolean on) {
            this.cpu = on;
            return this;
        }

        /** Byte limit of the detailed header; default 3000. */
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
        private static final VarHandle LANES, TOTALS, DROPPED;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                LANES = l.findVarHandle(Context.class, "lanes", Lane.class);
                TOTALS = l.findVarHandle(Context.class, "totals", Totals.class);
                DROPPED = l.findVarHandle(Context.class, "dropped", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static final Context NONE = new Context();

        final long id, epoch;
        final LongSupplier clock;
        final boolean strict, cpu;
        final int budget, headerBytes, maxLanes;
        final AtomicInteger misuses = new AtomicInteger();
        volatile int level;
        volatile boolean detailSeen, released;
        boolean raised; // guarded by this
        private volatile Lane lanes;    // CAS-linked registry, newest first
        private volatile Totals totals; // created with the first lane-less record
        private volatile int dropped;   // tasks left unrecorded at the lane cap
        private long[] lost;            // guarded by this: (laneId << 32 | row) of handoffs whose task has no record
        private int lostCount;

        private Context() {
            id = -1;
            epoch = 0;
            clock = SYSTEM_CLOCK;
            strict = cpu = false;
            budget = headerBytes = maxLanes = 0;
            level = -1; // below COARSE, so NOOP.detail() returns at the level check
        }

        Context(Options o) {
            id = IDS.incrementAndGet();
            clock = o.clock;
            strict = o.strict;
            cpu = o.cpu;
            budget = o.budget;
            headerBytes = o.headerBytes;
            maxLanes = o.lanes;
            level = o.level;
            epoch = clock.getAsLong();
        }

        long now() {
            return clock.getAsLong() - epoch;
        }

        void misuse(String what) {
            misuses.incrementAndGet();
            if (strict) throw new AssertionError("Tempo misuse: " + what);
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
            totals().add(h.lane.nameOf(h.row), true, Math.max(0, now() - h.submit));
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

        Aggregate get(int id) {
            for (Totals t = this; t != null; t = t.next) {
                int s = t.slot(id, false);
                if (s < 0) continue;
                long[] c = t.cells;
                int b = s * CELLS;
                long count = cell(c, b + COUNT);
                return new Aggregate(count, cell(c, b + SUM), count == 0 ? 0 : Long.MAX_VALUE - cell(c, b + MIN),
                        cell(c, b + MAX), cell(c, b + WAITS), cell(c, b + WAIT_SUM), cell(c, b + WAIT_MAX));
            }
            return Aggregate.EMPTY;
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

    /** Bounded stack of recycled chunks; a chunk that does not fit goes to the GC. */
    static final class Pool<T> {
        private final Object[] items;
        private final Supplier<T> fresh;
        private int size;

        Pool(int capacity, Supplier<T> fresh) {
            this.items = new Object[capacity];
            this.fresh = fresh;
        }

        @SuppressWarnings("unchecked")
        T take() {
            synchronized (this) {
                if (size > 0) {
                    T chunk = (T) items[--size];
                    items[size] = null;
                    return chunk;
                }
            }
            return fresh.get();
        }

        synchronized void give(Object chunk) {
            if (size < items.length) items[size++] = chunk;
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
        static final int IDLE = 0, ACTIVE = 1, RETIRED = 2;
        static final long OPEN = Long.MIN_VALUE;

        private static final VarHandle COMMIT, STATE, LABELS, DUR = MethodHandles.arrayElementVarHandle(long[].class);

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                COMMIT = l.findVarHandle(Lane.class, "commit", long.class);
                STATE = l.findVarHandle(Lane.class, "state", int.class);
                LABELS = l.findVarHandle(Lane.class, "labels", String[].class);
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
        final int[] rowAt = new int[MAX_DEPTH];
        final Span[] handles = new Span[MAX_DEPTH];
        int depth, rowCount, eventCount, dropped;

        // Phase state of the span at depth d, created with the lane's first phase or lap: the time of its
        // last phase boundary (valid unless lastPhase[d] == NO_PHASE), its last phase or lap row (or
        // NO_PHASE, DROPPED_PHASE) and the number of boundaries so far.
        static final int NO_PHASE = -1, DROPPED_PHASE = -2;
        long[] mark;
        int[] lastPhase, lapOrdinal;

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
            return state == RETIRED;
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
            if (root && !STATE.compareAndSet(this, IDLE, ACTIVE)) return NOOP;
            Name n = Name.byId(h.lane.nameOf(h.row));
            Span s = NOOP;
            int r = -1;
            if (eventCount > MAX_EVENTS - 2) {
                dropped++;
            } else if (run) {
                s = stack(n, 0);
                r = s == NOOP ? -1 : s.row;
            } else {
                r = row(n, CANCELLED, root ? -1 : rowAt[depth - 1]);
            }
            if (r < 0) {
                if (root) idle();
                return null;
            }
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
            if (!STATE.compareAndSet(this, IDLE, ACTIVE)) return NOOP;
            Span h = push(n, flags);
            if (h == NOOP) {
                idle();
                return NOOP;
            }
            saved = slot.current;
            slot.current = this;
            return h;
        }

        Span push(Name n, int flags) {
            Span h = stack(n, flags);
            if (h != NOOP) {
                rows[h.row >>> CHUNK_BITS].start[h.row & MASK] = ctx.now(); // read last: the bookkeeping is not timed
                publish();
            }
            return h;
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
            boolean healed = unwind(d, now);
            end(d, now, false);
            depth = d;
            publish();
            if (d == 0) exitRoot();
            if (healed) ctx.misuse("span closed while a child span was open");
        }

        /** Ends every row above depth {@code d}; returns whether one of them was a span healed (phases end normally). */
        private boolean unwind(int d, long now) {
            boolean healed = false;
            for (int k = depth - 1; k > d; k--) healed |= end(k, now, true);
            depth = d + 1;
            return healed;
        }

        /** Ends the row at depth {@code k}: flags first, duration last (the commit protocol). */
        private boolean end(int k, long now, boolean heal) {
            int r = rowAt[k];
            Rows c = rows[r >>> CHUNK_BITS];
            int i = r & MASK;
            boolean phase = (c.flags[i] & PHASE) != 0, healed = heal && !phase;
            if (healed) c.flags[i] |= HEALED;
            if (phase) mark[k - 1] = now; // however the phase ends, its end is the owner's last boundary
            long dur = now - c.start[i];
            DUR.setOpaque(c.dur, i, dur < 0 ? 0L : dur);
            return healed;
        }

        boolean isPhase(int d) {
            int r = rowAt[d];
            return (rows[r >>> CHUNK_BITS].flags[r & MASK] & PHASE) != 0;
        }

        // ---- phases and laps (owner thread, the span at depth d is open) ----

        private void laps() {
            mark = new long[MAX_DEPTH];
            lapOrdinal = new int[MAX_DEPTH];
            int[] p = new int[MAX_DEPTH];
            Arrays.fill(p, NO_PHASE);
            lastPhase = p;
        }

        /** Label first: ends whatever is open above depth {@code d} and opens a phase row at one {@code now}. */
        void phase(int d, Name n, int flags) {
            if (lastPhase == null) laps();
            long now = ctx.now();
            boolean healed = unwind(d, now);
            mark[d] = now;
            lapOrdinal[d]++;
            Span h = stack(n, flags | PHASE);
            if (h != NOOP) rows[h.row >>> CHUNK_BITS].start[h.row & MASK] = now;
            lastPhase[d] = h == NOOP ? DROPPED_PHASE : h.row;
            publish();
            if (healed) ctx.misuse("phase started while a child span was open");
        }

        /**
         * Label after. An open label-first phase ends under its own name; a different {@code n} is kept as
         * a zero-length misuse row. Otherwise a closed RETRO row covers {@code [mark, now)}; the render
         * moves the spans opened since {@code mark} under it. {@code n} is null when the caller passed none.
         */
        void done(int d, Name n, int flags, String label) {
            if (lastPhase == null) laps();
            long now = ctx.now();
            int p = lastPhase[d];
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
                int lap = n == null ? row(Name.UNKNOWN, RETRO | PHASE | MISUSE | flags, rowAt[d])
                        : row(n, RETRO | PHASE | flags, rowAt[d]);
                if (lap >= 0) {
                    closed(lap, from, now - from);
                    annotate(lap, 0, label);
                }
                lastPhase[d] = lap >= 0 ? lap : DROPPED_PHASE;
                mark[d] = now;
                lapOrdinal[d]++;
            }
            publish();
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
        private boolean enter() {
            return depth > 0 || STATE.compareAndSet(this, IDLE, ACTIVE);
        }

        private void leave() {
            if (depth == 0) idle();
        }

        // The owner stores IDLE then reads retireRequested; release() stores retireRequested then CASes
        // IDLE->RETIRED. Both accesses are volatile, so at least one side sees the other and retires.
        private void idle() {
            state = IDLE;
            if (retireRequested) retire();
        }

        void requestRetire() {
            retireRequested = true;
            retire();
        }

        private void retire() {
            if (!STATE.compareAndSet(this, IDLE, RETIRED)) return;
            for (Rows c : rows) if (c != null) ROW_POOL.give(c);
            if (events != null) for (Events c : events) if (c != null) EVENT_POOL.give(c);
        }

        private void publish() {
            COMMIT.setRelease(this, (long) rowCount << 32 | eventCount);
        }

        long committed() {
            return (long) COMMIT.getAcquire(this);
        }

        /** Duration of row {@code r}, or its age while open; 0 once released. Safe from any thread. */
        long elapsed(int r) {
            if (ctx.released || state == RETIRED) return 0; // volatile reads before the commit, as in visit()
            long c = committed();
            if (r < 0 || r >= (int) (c >>> 32)) return 0;
            Rows k = rows[r >>> CHUNK_BITS];
            int i = r & MASK;
            long dur = (long) DUR.getOpaque(k.dur, i);
            return dur != OPEN ? dur : Math.max(0, ctx.now() - k.start[i]);
        }
    }

    /**
     * One lane's committed rows as one render reads them: attribute events grouped by row, labels, task
     * links and child lists. Reading {@code state} before the commit matters: the owner stores
     * {@code IDLE} after its last write, so a lane read as {@code IDLE} is seen complete (final durations
     * and flags included). A lane read as {@code ACTIVE} is best effort and its spans are marked
     * {@link SpanView#live}.
     */
    static final class Shape {
        final Lane lane;
        final int rows;
        final boolean live;
        final int[] from, order;  // attribute events of row r: order[from[r] .. from[r + 1]), in append order
        int[] labelOf;
        String[] text;
        long[] link, wait;        // XPARENT and QWAIT by row; null in a lane without tasks
        int[] bodyLane, bodyRow;  // by handoff row: the task row linked to it, or -1
        boolean[] hidden;         // not in its parent's child list: a task drawn at its handoff, or a lost handoff
        int[] first, next;        // child lists; index rows is a virtual parent of the lane roots

        /** Null for a lane that is retired or whose trace is released. */
        static Shape of(Lane l) {
            int s = l.state;
            if (s == Lane.RETIRED || l.ctx.released) return null;
            return new Shape(l, s == Lane.ACTIVE, l.committed());
        }

        private Shape(Lane l, boolean live, long commit) {
            lane = l;
            this.live = live;
            rows = (int) (commit >>> 32);
            int nEvents = (int) commit;
            Events[] events = l.events;
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
                }
            }
            for (int r = 0; r < rows; r++) from[r + 1] += from[r];
            order = new int[from[rows]];
            int[] fill = Arrays.copyOf(from, rows);
            for (int e = 0; e < nEvents; e++) {
                Events k = events[e >>> Lane.CHUNK_BITS];
                int i = e & Lane.MASK, kind = k.key[i] & KIND_MASK;
                if (kind == ATTR_LONG || kind == ATTR_DOUBLE) order[fill[k.row[i]]++] = e;
            }
            if (labelOf != null) text = (String[]) Lane.LABELS.getAcquire(l);
        }

        int flags(int r) {
            return lane.rows[r >>> Lane.CHUNK_BITS].flags[r & Lane.MASK];
        }

        long start(int r) {
            return lane.rows[r >>> Lane.CHUNK_BITS].start[r & Lane.MASK];
        }

        long dur(int r) {
            return (long) Lane.DUR.getOpaque(lane.rows[r >>> Lane.CHUNK_BITS].dur, r & Lane.MASK);
        }

        int name(int r) {
            return lane.rows[r >>> Lane.CHUNK_BITS].name[r & Lane.MASK];
        }

        String label(int r) {
            return labelOf == null || labelOf[r] < 0 ? null : text[labelOf[r]];
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
            return bodyLane != null && bodyLane[r] >= 0;
        }

        /**
         * Child lists in row order. A RETRO lap adopts the run of preceding siblings that started at or
         * after it, back to the previous phase row, and takes the place of the first of them.
         */
        void tree() {
            int top = rows;
            int[] parent = new int[rows + 1], last = new int[rows + 1], prev = new int[rows + 1];
            first = new int[rows + 1];
            next = new int[rows + 1];
            Arrays.fill(first, -1);
            Arrays.fill(last, -1);
            for (int r = 0; r < rows; r++) {
                if (hidden != null && hidden[r]) continue;
                Rows k = lane.rows[r >>> Lane.CHUNK_BITS];
                int i = r & Lane.MASK, p = k.parent[i] < 0 ? top : k.parent[i];
                parent[r] = p;
                if ((k.flags[i] & RETRO) != 0) {
                    int a = last[p], b = -1;
                    while (a >= 0 && (flags(a) & PHASE) == 0 && start(a) >= k.start[i]) {
                        parent[a] = r;
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
        }
    }

    /**
     * The lanes of one trace stitched into one tree. A handoff row is drawn as one record with the task
     * linked to it (submit time, queue wait, the task's duration, thread and children); a handoff without
     * a task is drawn never started.
     */
    static final class Stitch {
        private final Shape[] shapes; // by lane id
        private final long now;

        Stitch(Context ctx, long now) {
            this.now = now;
            Lane[] lanes = ctx.lanesById();
            shapes = new Shape[lanes.length];
            for (int i = 0; i < lanes.length; i++) if (lanes[i] != null) shapes[i] = Shape.of(lanes[i]);
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
                    if (t != null && h < t.rows && (t.flags(h) & HANDOFF) != 0) t.link(h, s.lane.id, r);
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

        /** Visits the records in pre-order; the lane roots of each lane, by lane id, are the top level. */
        void walk(SpanVisitor visitor) {
            SpanView v = new SpanView();
            int limit = 0; // a record per row at most, whatever the links say
            for (Shape s : shapes) if (s != null) limit += s.rows;
            int[] lane = new int[16], row = new int[16], id = new int[16]; // the path to the current record
            int n = 0;
            for (Shape top : shapes) {
                if (top == null) continue;
                for (int r = top.first[top.rows]; r >= 0 && n < limit; r = top.next[r]) {
                    int d = 0;
                    lane[0] = top.lane.id;
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
                        lane[d] = s.lane.id;
                        row[d] = c;
                        id[d] = n;
                        emit(v, s, c, id[d - 1], d, n++, visitor);
                    }
                }
            }
        }

        private void emit(SpanView v, Shape s, int r, int parent, int depth, int id, SpanVisitor visitor) {
            int flags = s.flags(r);
            boolean handoff = (flags & HANDOFF) != 0, begun = handoff && s.begun(r);
            Shape b = begun ? shapes[s.bodyLane[r]] : s;
            int br = begun ? s.bodyRow[r] : r;
            long dur = b.dur(br);
            v.id = id;
            v.parent = parent;
            v.depth = depth;
            v.lane = b.lane;
            v.live = b.live;
            v.name = Name.byId(b.name(br));
            v.start = s.start(r);
            v.open = dur == Lane.OPEN;
            v.duration = v.open ? Math.max(0, now - b.start(br)) : dur;
            v.flags = begun ? flags | b.flags(br) : flags;
            v.handoff = handoff;
            v.begun = begun;
            v.wait = begun ? (b.wait == null ? 0 : b.wait[br]) : handoff ? Math.max(0, now - v.start) : 0;
            v.label = b.label(br);
            v.order = b.order;
            v.attrFrom = b.from[br];
            v.attrTo = b.from[br + 1];
            v.subLane = s.lane;
            v.subOrder = s.order;
            v.subFrom = begun ? s.from[r] : 0;
            v.subTo = begun ? s.from[r + 1] : 0;
            visitor.visit(v);
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

        /** Returns the trace's buffers to the pool. Optional and idempotent; afterwards every read is empty. */
        public void release() {
            ctx.release();
        }

        /**
         * Adds one occurrence of {@code name} lasting {@code nanos} to {@link #aggregate(Name)}, without a
         * span or a lane. Thread-safe, at every level, also after close; allocation-free once the trace
         * has seen the name. Ignored after release.
         */
        public void add(Name name, long nanos) {
            total(name, false, nanos);
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
         * Count, sum, extremes and queue waits of {@code name} recorded through {@link #add},
         * {@link #addWait} and {@link #waited}, and the queue waits of tasks left unrecorded at the lane
         * cap. Exact once the contributing threads are done, best effort while they run; empty after release.
         */
        public Aggregate aggregate(Name name) {
            Totals t = ctx.totals;
            return name == null || t == null || ctx.released ? Aggregate.EMPTY : t.get(name.id);
        }

        /**
         * Visits every recorded span in pre-order with a reused, read-only view. Spans still open are
         * clamped to one {@code now} taken for the whole walk. A handoff is one span on the thread that
         * ran its task, see {@link SpanView#handoff()}. Nothing is visited after release.
         */
        public void forEachSpan(SpanVisitor visitor) {
            if (ctx.released) return;
            new Stitch(ctx, ctx.now()).walk(visitor);
        }

        int misuses() {
            return ctx.misuses.get();
        }

        int dropped() {
            return ctx.dropped();
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
        int row;

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

        /** A child span recorded at {@link #DETAIL} and above; {@link #NOOP} below. */
        public Span detail(Name name) {
            if (ctx.level < DETAIL) return NOOP;
            if (!ctx.detailSeen) ctx.detailSeen = true;
            return open(name, DETAIL_ROW);
        }

        /** A child span recorded at {@link #DEEP}; {@link #NOOP} below. */
        public Span deep(Name name) {
            if (ctx.level < DEEP) return NOOP;
            if (!ctx.detailSeen) ctx.detailSeen = true;
            return open(name, DEEP_ROW);
        }

        private Span open(Name name, int flags) {
            if (!live()) return NOOP;
            if (name == null) {
                ctx.misuse("null name");
                return lane.push(Name.UNKNOWN, flags | MISUSE);
            }
            return lane.push(name, flags);
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
            if (live()) {
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
            if (live()) {
                if (name == null) nullName();
                lane.done(depth, name, flags, label);
            }
            return this;
        }

        /** Selects the open phase or the last lap of this span for the legacy headers. */
        public Span legacy() {
            if (live()) lane.legacy(depth);
            return this;
        }

        /** Attaches a number to the open phase or the last lap of this span. */
        public Span phaseSet(Name key, long value) {
            if (live()) lane.phaseSet(depth, key(key) << KIND_BITS | ATTR_LONG, value);
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
            if (owned()) lane.event(row, key(key) << KIND_BITS | ATTR_LONG, value);
            return this;
        }

        public Span set(Name key, double value) {
            if (owned()) lane.event(row, key(key) << KIND_BITS | ATTR_DOUBLE, Double.doubleToRawLongBits(value));
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
            if (owned()) lane.flag(row, bits);
            return this;
        }

        /** Running time while open, the duration once closed; 0 on {@link #NOOP} and after release. */
        public long elapsedNanos() {
            return lane == null ? 0 : lane.elapsed(row);
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

    /** Read-only view of one recorded span, reused across {@link SpanVisitor#visit} calls. */
    public static final class SpanView {
        Lane lane, subLane; // the span's lane; for a begun handoff the submitter's, whose attributes come first
        int[] order, subOrder;
        int id, parent, depth, flags, attrFrom, attrTo, subFrom, subTo;
        Name name;
        String label;
        long start, duration, wait;
        boolean open, live, handoff, begun;

        SpanView() {}

        /** Index in visiting order; the root is 0. */
        public int id() { return id; }

        /** Id of the parent span, -1 for the root. */
        public int parent() { return parent; }

        public int depth() { return depth; }

        public Name name() { return name; }

        /** Nanoseconds from the trace start; for a {@link #handoff()} the submit time. */
        public long startNanos() { return start; }

        /** Duration; for an open span the time up to the walk's {@code now}. For a handoff its task's run time. */
        public long durationNanos() { return duration; }

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
        public String thread() { return lane.owner.getName(); }

        /** Attributes; a handoff has those set at submit ({@link Handoff#set}) first, then its task's. */
        public int attributes() { return (subTo - subFrom) + (attrTo - attrFrom); }

        public Name attributeKey(int i) { return Name.byId(events(i).key[event(i) & Lane.MASK] >>> KIND_BITS); }

        public boolean attributeIsDouble(int i) { return (events(i).key[event(i) & Lane.MASK] & KIND_MASK) == ATTR_DOUBLE; }

        /** The value of a long attribute, or a double attribute truncated. */
        public long longAttribute(int i) {
            long v = value(i);
            return attributeIsDouble(i) ? (long) Double.longBitsToDouble(v) : v;
        }

        public double doubleAttribute(int i) {
            long v = value(i);
            return attributeIsDouble(i) ? Double.longBitsToDouble(v) : v;
        }

        private long value(int i) {
            return events(i).value[event(i) & Lane.MASK];
        }

        private Events events(int i) {
            return (i < subTo - subFrom ? subLane : lane).events[event(i) >>> Lane.CHUNK_BITS];
        }

        private int event(int i) {
            if (i < 0 || i >= attributes()) throw new IndexOutOfBoundsException("attribute " + i);
            int sub = subTo - subFrom;
            return i < sub ? subOrder[subFrom + i] : order[attrFrom + i - sub];
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
