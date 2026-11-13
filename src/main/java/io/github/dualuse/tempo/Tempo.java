package io.github.dualuse.tempo;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
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
     * The innermost open span of the calling thread, or {@link #NOOP}. Costs one {@code ThreadLocal}
     * lookup, so {@code Tempo.current().detail(n)} is not free even when detail is off.
     */
    public static Span current() {
        Lane l = SLOT.get().current;
        return l == null || l.depth == 0 ? NOOP : l.handles[l.depth - 1];
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

        int level = COARSE, budget = 512, headerBytes = 3000;
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

        /** Throws {@link AssertionError} at the offending call on misuse instead of healing silently. */
        public Options strict(boolean on) {
            this.strict = on;
            return this;
        }
    }

    /** Per-trace settings and counters. Lanes reference this instead of the {@link Trace}, so a lane retains no other lane. */
    static final class Context {
        private static final AtomicLong IDS = new AtomicLong();
        static final Context NONE = new Context();

        final long id, epoch;
        final LongSupplier clock;
        final boolean strict, cpu;
        final int budget, headerBytes;
        final AtomicInteger misuses = new AtomicInteger();
        volatile int level;
        volatile boolean detailSeen;
        boolean raised; // guarded by this

        private Context() {
            id = -1;
            epoch = 0;
            clock = SYSTEM_CLOCK;
            strict = cpu = false;
            budget = headerBytes = 0;
            level = -1; // below COARSE, so NOOP.detail() returns at the level check
        }

        Context(Options o) {
            id = IDS.incrementAndGet();
            clock = o.clock;
            strict = o.strict;
            cpu = o.cpu;
            budget = o.budget;
            headerBytes = o.headerBytes;
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
    }

    /** Per-thread state. Holds no {@link Trace}; only lane-root opens and closes touch it. */
    static final class ThreadSlot {
        Lane current;
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

        private static final VarHandle COMMIT, STATE, DUR = MethodHandles.arrayElementVarHandle(long[].class);

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                COMMIT = l.findVarHandle(Lane.class, "commit", long.class);
                STATE = l.findVarHandle(Lane.class, "state", int.class);
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

        @SuppressWarnings("unused") // via COMMIT: (rowCount << 32) | eventCount
        private long commit;
        private volatile int state;
        private volatile boolean retireRequested;
        private Lane saved; // slot.current when the lane root opened

        Lane(Context ctx) {
            this.ctx = ctx;
            this.owner = Thread.currentThread();
            this.slot = SLOT.get();
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
            int d = depth, r = rowCount;
            if (d == MAX_DEPTH || r == MAX_ROWS) {
                dropped++;
                return NOOP;
            }
            Rows c = rows[r >>> CHUNK_BITS];
            if (c == null) rows[r >>> CHUNK_BITS] = c = ROW_POOL.take();
            int i = r & MASK;
            c.name[i] = n.id;
            c.parent[i] = d == 0 ? -1 : rowAt[d - 1];
            c.flags[i] = (short) flags;
            DUR.setOpaque(c.dur, i, OPEN);
            rowAt[d] = r;
            depth = d + 1;
            rowCount = r + 1;
            Span h = handles[d];
            if (h == null) handles[d] = h = new Span(this, ctx, d);
            h.row = r;
            c.start[i] = ctx.now();
            publish();
            return h;
        }

        /** Closes the row at depth {@code d}, healing everything above it first. */
        void close(int d) {
            long now = ctx.now();
            boolean healed = false;
            for (int k = depth - 1; k > d; k--) healed |= end(rowAt[k], now, true);
            end(rowAt[d], now, false);
            depth = d;
            publish();
            if (d == 0) exitRoot();
            if (healed) ctx.misuse("span closed while a child span was open");
        }

        /** Flags first, duration last (the commit protocol). Returns whether a non-phase row was healed. */
        private boolean end(int r, long now, boolean heal) {
            Rows c = rows[r >>> CHUNK_BITS];
            int i = r & MASK;
            boolean healed = heal && (c.flags[i] & PHASE) == 0;
            if (healed) c.flags[i] |= HEALED;
            long dur = now - c.start[i];
            DUR.setOpaque(c.dur, i, dur < 0 ? 0L : dur);
            return healed;
        }

        /** Restores the slot's previous lane, skipping lanes that closed out of order meanwhile. */
        private void exitRoot() {
            Lane prev = saved;
            while (prev != null && prev.depth == 0) prev = prev.saved;
            if (slot.current == this) {
                slot.current = prev;
                saved = null;
            } else {
                saved = prev; // a lane opened above this one still restores through it
            }
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
            int e = eventCount;
            if (e == MAX_EVENTS) {
                dropped++;
            } else {
                Events[] t = events;
                if (t == null) events = t = new Events[CHUNKS];
                Events c = t[e >>> CHUNK_BITS];
                if (c == null) t[e >>> CHUNK_BITS] = c = EVENT_POOL.take();
                int i = e & MASK;
                c.row[i] = r;
                c.key[i] = key;
                c.value[i] = value;
                eventCount = e + 1;
                publish();
            }
            leave();
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

        /** Duration of row {@code r}, or its age while open; 0 once retired. Safe from any thread. */
        long elapsed(int r) {
            if (state == RETIRED) return 0; // volatile read before the commit, as in visit()
            long c = committed();
            if (r < 0 || r >= (int) (c >>> 32)) return 0;
            Rows k = rows[r >>> CHUNK_BITS];
            int i = r & MASK;
            long dur = (long) DUR.getOpaque(k.dur, i);
            return dur != OPEN ? dur : Math.max(0, ctx.now() - k.start[i]);
        }

        /**
         * Visits the committed rows in row order, which for one lane is pre-order. Returns the next free id.
         * Reading {@code state} first matters: the owner stores {@code IDLE} after its last write, so a lane
         * read as {@code IDLE} is seen complete (final durations and flags included). A lane read as
         * {@code ACTIVE} is best effort and its spans are marked {@link SpanView#live}.
         */
        int visit(int base, long now, SpanView v, SpanVisitor visitor) {
            int s = state;
            if (s == RETIRED) return base;
            long c = committed();
            int nRows = (int) (c >>> 32), nEvents = (int) c;

            // Attribute events grouped by row (counting sort), keeping append order within a row.
            int[] from = new int[nRows + 1];
            for (int e = 0; e < nEvents; e++)
                if (isAttribute(e)) from[events[e >>> CHUNK_BITS].row[e & MASK] + 1]++;
            for (int r = 0; r < nRows; r++) from[r + 1] += from[r];
            int[] order = new int[from[nRows]], fill = Arrays.copyOf(from, nRows);
            for (int e = 0; e < nEvents; e++)
                if (isAttribute(e)) order[fill[events[e >>> CHUNK_BITS].row[e & MASK]]++] = e;

            int[] depthOf = new int[nRows];
            v.lane = this;
            v.order = order;
            v.live = s == ACTIVE;
            for (int r = 0; r < nRows; r++) {
                Rows k = rows[r >>> CHUNK_BITS];
                int i = r & MASK, p = k.parent[i];
                depthOf[r] = p < 0 ? 0 : depthOf[p] + 1;
                long start = k.start[i], dur = (long) DUR.getOpaque(k.dur, i);
                v.id = base + r;
                v.parent = p < 0 ? -1 : base + p;
                v.depth = depthOf[r];
                v.name = Name.byId(k.name[i]);
                v.start = start;
                v.open = dur == OPEN;
                v.duration = v.open ? Math.max(0, now - start) : dur;
                v.flags = k.flags[i];
                v.attrFrom = from[r];
                v.attrTo = from[r + 1];
                visitor.visit(v);
            }
            return base + nRows;
        }

        private boolean isAttribute(int e) {
            int kind = events[e >>> CHUNK_BITS].key[e & MASK] & KIND_MASK;
            return kind == ATTR_LONG || kind == ATTR_DOUBLE;
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** One request's recording. Not pooled. Reads stay valid after {@link #close()} until {@link #release()}. */
    public static final class Trace implements AutoCloseable {
        private static final VarHandle LANES;

        static {
            try {
                LANES = MethodHandles.lookup().findVarHandle(Trace.class, "lanes", Lane.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        final Context ctx;
        private final Lane rootLane;
        private final Span root;
        private volatile Lane lanes; // CAS-linked registry, newest first
        private volatile boolean released;

        Trace(Name name, Options options) {
            ctx = new Context(options == null ? Options.DEFAULTS : options);
            int flags = 0;
            if (name == null) {
                ctx.misuse("null name");
                name = Name.UNKNOWN;
                flags = MISUSE;
            }
            rootLane = register(new Lane(ctx));
            root = rootLane.openRoot(name, flags);
        }

        Lane register(Lane lane) {
            Lane head;
            do {
                head = lanes;
                lane.next = head;
                lane.id = head == null ? 0 : head.id + 1;
            } while (!LANES.compareAndSet(this, head, lane));
            return lane;
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
            return released ? 0 : rootLane.elapsed(root.row);
        }

        /** Ends the root span, healing any span still open. Idempotent on the owner thread. */
        @Override
        public void close() {
            if (Thread.currentThread() == rootLane.owner && rootLane.depth == 0) return;
            root.close();
        }

        /** Returns the trace's buffers to the pool. Optional and idempotent; afterwards every read is empty. */
        public void release() {
            if (released) return;
            released = true;
            for (Lane l = lanes; l != null; l = l.next) l.requestRetire();
        }

        /**
         * Visits every recorded span in pre-order with a reused, read-only view. Spans still open are
         * clamped to one {@code now} taken for the whole walk. Nothing is visited after release.
         */
        public void forEachSpan(SpanVisitor visitor) {
            if (released) return;
            long now = ctx.now();
            SpanView v = new SpanView();
            int base = 0;
            for (Lane l : lanesById()) base = l.visit(base, now, v, visitor);
        }

        private Lane[] lanesById() {
            Lane head = lanes;
            Lane[] out = new Lane[head.id + 1];
            for (Lane l = head; l != null; l = l.next) out[l.id] = l;
            return out;
        }

        int misuses() {
            return ctx.misuses.get();
        }

        int dropped() {
            int n = 0;
            for (Lane l = lanes; l != null; l = l.next) n += l.dropped;
            return n;
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
            if (key != null) return key.id;
            ctx.misuse("null attribute key");
            return Name.UNKNOWN.id;
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
        Lane lane;
        int[] order;
        int id, parent, depth, flags, attrFrom, attrTo;
        Name name;
        long start, duration;
        boolean open, live;

        SpanView() {}

        /** Index in visiting order; the root is 0. */
        public int id() { return id; }

        /** Id of the parent span, -1 for the root. */
        public int parent() { return parent; }

        public int depth() { return depth; }

        public Name name() { return name; }

        /** Nanoseconds from the trace start. */
        public long startNanos() { return start; }

        /** Duration; for an open span the time up to the walk's {@code now}. */
        public long durationNanos() { return duration; }

        public boolean open() { return open; }

        /** The span's lane was recording when the walk reached it, so its rows are best effort. */
        boolean live() { return live; }

        public boolean failed() { return (flags & FAILED) != 0; }

        public boolean cancelled() { return (flags & CANCELLED) != 0; }

        public boolean waiting() { return (flags & WAIT) != 0; }

        /** Closed implicitly by an ancestor's close. */
        public boolean healed() { return (flags & HEALED) != 0; }

        /** Recorded through a misused call, e.g. with a null name. */
        public boolean misuse() { return (flags & MISUSE) != 0; }

        /** {@link #COARSE}, {@link #DETAIL} or {@link #DEEP}. */
        public int level() {
            return (flags & DEEP_ROW) != 0 ? DEEP : (flags & DETAIL_ROW) != 0 ? DETAIL : COARSE;
        }

        public String thread() { return lane.owner.getName(); }

        public int attributes() { return attrTo - attrFrom; }

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
            int e = event(i);
            return lane.events[e >>> Lane.CHUNK_BITS].key[e & Lane.MASK];
        }

        private long value(int i) {
            int e = event(i);
            return lane.events[e >>> Lane.CHUNK_BITS].value[e & Lane.MASK];
        }

        private int event(int i) {
            if (i < 0 || i >= attributes()) throw new IndexOutOfBoundsException("attribute " + i);
            return order[attrFrom + i];
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
