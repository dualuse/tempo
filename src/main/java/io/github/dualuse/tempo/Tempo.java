package io.github.dualuse.tempo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.zip.Deflater;

/**
 * Phase profiling for request-shaped work.
 *
 * <p>A {@link Trace} records a tree of {@link Span}s into per-thread primitive buffers. Code opens spans
 * with static calls, and they assemble themselves under the thread's innermost open span. Recording never
 * throws into the caller (unless {@link Options#strict(boolean)} asks for it) and allocates nothing per
 * span once warm.
 *
 * <pre>{@code
 * static final Tempo.Name DECODE = Tempo.name("decode");
 *
 * Tempo.Trace t = Tempo.trace("GET /search");
 * try (t) {
 *     try (Tempo.Span s = Tempo.span(DECODE)) {
 *         s.set("bytes", image.length);
 *     }
 * }
 * response.header("Tempo-Timing", t.toHeader()); // after the block: the root has closed
 * t.release();
 * }</pre>
 *
 * <p>Three words open a span, and the word says what is measured: a {@link #span} is always timed and
 * drawn; a {@link #step} is always timed (its totals and listeners), and drawn when the trace records
 * steps ({@link Trace#records(int)}); a {@link #tick} is timed and drawn only when the trace records
 * ticks, and costs about a branch otherwise. Outside a trace a span or step is timed only when its name
 * has a global listener ({@link #on}); otherwise it is a no-op.
 *
 * <p>A {@link Span} is a handle that only its lane's owner thread may use. Each recorded span has a
 * handle of its own for the whole trace, so {@code set}, {@code fail} and {@code cancel} after close, as
 * in a catch block right after, reach that span. Once the trace is released, the thread reuses the
 * handle for a span of a later trace; a use of it before then is ignored and counted as misuse ({@link
 * Options#strict} throws, and never reuses a handle). Spans folded past the budget share one handle per
 * depth. Work for another thread is a {@link Task}: {@link #runnable}, {@link #callable} or {@link
 * #supplier} where it is submitted, or a {@link #executor} that makes one of every submit; it records its
 * queue wait, and counts like a step.
 * Build with {@code -Xlint} and a span opened by try-with-resources but not used in its block warns;
 * {@code @SuppressWarnings("try")} silences it, or {@link #run} and {@link #call} are the compact form.
 */
public final class Tempo {

    /**
     * What a trace records ({@link Trace#records(int)}): spans always; steps and ticks drawn on request.
     * Levels are a recorder concept and do not appear on the wire.
     */
    public static final int SPAN = 0, STEP = 1, TICK = 2;

    // Row flag bits (16-bit column). RETRO: a phase named after the fact (done(name) with none open), which
    // the render puts before the spans that started with it. LABELED: a phase with a label, for Runtime.
    static final int PHASE = 1, RETRO = 1 << 1, HANDOFF = 1 << 2, WAIT = 1 << 3, FAILED = 1 << 4,
            CANCELLED = 1 << 5, HEALED = 1 << 6, MISUSE = 1 << 7, STEP_ROW = 1 << 8, TICK_ROW = 1 << 9,
            LABELED = 1 << 10, TASK = 1 << 11, UNRUN = 1 << 12; // UNRUN: a task cancelled before it began
    static final int NEVER = 1 << 13; // in an aggregate's flags only: no handoff of it started

    // Event kinds, in the low KIND_BITS of an event's key; the rest is a Name id for attributes.
    static final int KIND_BITS = 4, KIND_MASK = (1 << KIND_BITS) - 1,
            XPARENT = 1, QWAIT = 2, CPU = 3, ATTR_LONG = 4, ATTR_DOUBLE = 5, LABEL = 6;

    static final int CPU_AUTO = -1, CPU_OFF = 0, CPU_ON = 1;

    static final LongSupplier SYSTEM_CLOCK = System::nanoTime;

    static final ThreadLocal<ThreadSlot> SLOT = ThreadLocal.withInitial(ThreadSlot::new);

    /** The handle that records nothing. Every method on it returns at once; never {@code null}. */
    public static final Span NOOP = new Span(null, Context.NONE, 0);

    private Tempo() {}

    /**
     * Interns a name. Keep the result in a {@code static final}: it is the fast path, with no lookup per
     * span. {@code ""} is the name of an unnamed span, {@code null} maps to {@code ?}. Every method that
     * takes a {@code Name} also takes a {@code String}, which it looks up in the same global table.
     */
    public static Name name(String literal) {
        return Name.intern(literal, false);
    }

    /** Starts a trace whose root span is opened on the calling thread; see {@link #trace(Name, Options)}. */
    public static Trace trace(Name name) {
        return trace(name, null);
    }

    /** Like {@link #trace(Name)}, the name looked up as for {@link #span(String)}. */
    public static Trace trace(String name) {
        return trace(name, null);
    }

    /**
     * Starts a trace whose root span is opened on the calling thread; the {@code Trace} is that span. The
     * clock is read first, before anything else (not even a class Tempo loads on first use): that reading
     * is the trace's zero point and the root's start, so all of Tempo's setup is counted in the root's time.
     *
     * <p>Started on a thread that is inside a trace already (one not released), it joins that trace
     * instead, as a self-contained block: it opens a span under the innermost one, and the returned {@code
     * Trace} works for that span's subtree as if it were top-level. Its {@link Trace#total}, {@link
     * Trace#on} and {@link Trace#records(int)} cover the subtree, its tasks on other threads included; its
     * renders are of the subtree, with the block's {@link Options#headerBytes} and {@link
     * Options#linkBase}; its {@link Options#strict}, {@link Options#cpu} and {@link Options#budget} (a
     * sub-budget, taken from what the outer trace has left) hold in the subtree and its tasks. What its
     * options leave unset it inherits from the outer trace: the header limit, viewer, strictness and CPU
     * setting as the outer trace has them, and without a budget of its own it draws from what the outer
     * trace has left. {@link Options#clock} and {@link Options#lanes} stay the outer trace's, and {@link Trace#release()} leaves
     * the buffers to it. Blocks nest. The call allocates the one {@code Trace}. A trace left open on a pool
     * thread takes in every later trace there: close each one.
     */
    public static Trace trace(Name name, Options options) {
        long zero = options == null ? SYSTEM_CLOCK.getAsLong() : options.clock.getAsLong(); // DEFAULTS.clock is SYSTEM_CLOCK
        return Trace.open(name, null, options, zero);
    }

    /** Like {@link #trace(Name, Options)}, the name looked up as for {@link #span(String)}. */
    public static Trace trace(String name, Options options) {
        long zero = options == null ? SYSTEM_CLOCK.getAsLong() : options.clock.getAsLong();
        return Trace.open(null, name, options, zero);
    }

    /**
     * Opens a span under the calling thread's innermost open span; always timed and drawn. Outside a trace
     * it is timed only for the name's global listeners ({@link #on}), else it is {@link #NOOP}. Costs one
     * {@code ThreadLocal} read more than {@link Span#span(Name)}.
     */
    public static Span span(Name name) {
        ThreadSlot slot = SLOT.get();
        Lane l = slot.current;
        return l != null ? l.openSpan(name, 0) : slot.free(name);
    }

    /**
     * Like {@link #span(Name)}, the name looked up in a global table. A name new to the table is added; past
     * 4,096 such names it maps to {@code <other>}, counted as misuse. Outside a trace it is only looked up.
     */
    public static Span span(String name) {
        ThreadSlot slot = SLOT.get();
        Lane l = slot.current;
        return l != null ? l.openSpan(name) : slot.free(Name.find(name));
    }

    /** An unnamed span, labelled only by its phases: {@link #span(Name)} with the name {@code ""}. */
    public static Span span() {
        return span(Name.EMPTY);
    }

    /**
     * Opens a step under the calling thread's innermost open span: always timed into the trace's totals
     * and listeners, drawn when the trace records steps ({@link Trace#records(int)}), against its budget
     * ({@link Options#budget}). Outside a trace like {@link #span(Name)}.
     */
    public static Span step(Name name) {
        ThreadSlot slot = SLOT.get();
        Lane l = slot.current;
        return l != null ? l.openStep(name, 0) : slot.free(name);
    }

    /** Like {@link #step(Name)}, the name looked up as for {@link #span(String)}. */
    public static Span step(String name) {
        ThreadSlot slot = SLOT.get();
        Lane l = slot.current;
        return l != null ? l.openStep(name) : slot.free(Name.find(name));
    }

    /**
     * Opens a tick under the calling thread's innermost open span: timed and drawn only when the trace
     * records ticks, against its budget; otherwise {@link #NOOP}, which costs the {@code ThreadLocal} read
     * and a branch. Never timed outside a trace.
     */
    public static Span tick(Name name) {
        Lane l = SLOT.get().current;
        return l != null ? l.openTick(name, 0) : NOOP;
    }

    /** Like {@link #tick(Name)}, the name looked up as for {@link #span(String)}. */
    public static Span tick(String name) {
        Lane l = SLOT.get().current;
        return l != null ? l.openTick(name) : NOOP;
    }

    /**
     * Runs {@code body} inside a {@link #span(Name)}; a throwable flags the span failed and is rethrown
     * unwrapped. Outside a trace, with a global listener on the name, this times the body for it.
     */
    public static <E extends Exception> void run(Name name, ThrowingRunnable<E> body) throws E {
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

    /** Like {@link #run(Name, ThrowingRunnable)}, the name looked up as for {@link #span(String)}. */
    public static <E extends Exception> void run(String name, ThrowingRunnable<E> body) throws E {
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

    /** Like {@link #run(Name, ThrowingRunnable)} for a body with a result. */
    public static <T, E extends Exception> T call(Name name, ThrowingSupplier<T, E> body) throws E {
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

    /** Like {@link #call(Name, ThrowingSupplier)}, the name looked up as for {@link #span(String)}. */
    public static <T, E extends Exception> T call(String name, ThrowingSupplier<T, E> body) throws E {
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
     * The innermost open span of the calling thread: the explicit way to annotate it, as in {@code
     * Tempo.current().set("hits", n)}. Outside a trace it is {@link #NOOP}, or, while a span or step timed
     * only for a global listener ({@link #on}) is open, that span, which keeps no attributes. A phase is a
     * section of its span, never a span of its own: inside {@code s.phase(n)} this returns {@code s}.
     * Costs one {@code ThreadLocal} read.
     */
    public static Span current() {
        ThreadSlot slot = SLOT.get();
        Lane l = slot.current;
        if (l == null && ((l = slot.free) == null || l.depth == 0)) return NOOP;
        return l.handles[l.depth - 1];
    }

    /**
     * A task without a body, for a hand-written worker that takes work from a queue of its own and calls
     * {@link Task#tryBegin()} where the work starts. Its parent is the calling thread's innermost open span,
     * and its queue wait starts now; see {@link Task}. Allocates the one task object, also outside a trace,
     * where it still cancels.
     */
    public static Task task(Name name) {
        return new Task(name, null, 0);
    }

    /** Like {@link #task(Name)}, the name looked up as for {@link #span(String)}. */
    public static Task task(String name) {
        return new Task(null, name, 0);
    }

    /**
     * A task that runs {@code body}, and the {@link java.util.concurrent.Future} of it: hand it to an
     * executor ({@code execute}, or {@code submit}, which wraps it) where it runs. Its parent is the
     * calling thread's innermost open span, and its queue wait starts now; see {@link Task}. Allocates the
     * one task object, also outside a trace, where it still cancels. A null body is misuse (strict mode
     * throws) and does nothing.
     */
    public static RunnableTask runnable(Name name, Runnable body) {
        return new RunnableTask(name, null, body, 0);
    }

    /** Like {@link #runnable(Name, Runnable)}, the name looked up as for {@link #span(String)}. */
    public static RunnableTask runnable(String name, Runnable body) {
        return new RunnableTask(null, name, body, 0);
    }

    /** Like {@link #runnable(Name, Runnable)} for a body with a result. */
    public static <V> CallableTask<V> callable(Name name, Callable<V> body) {
        return new CallTask<>(name, null, body, 0);
    }

    /** Like {@link #callable(Name, Callable)}, the name looked up as for {@link #span(String)}. */
    public static <V> CallableTask<V> callable(String name, Callable<V> body) {
        return new CallTask<>(null, name, body, 0);
    }

    /**
     * Like {@link #runnable(Name, Runnable)} for a {@link Supplier}, e.g. for {@code
     * CompletableFuture.supplyAsync}; the task is not a {@code Future} of its own.
     */
    public static <V> SupplierTask<V> supplier(Name name, Supplier<V> body) {
        return new SupplierTask<>(name, null, body);
    }

    /** Like {@link #supplier(Name, Supplier)}, the name looked up as for {@link #span(String)}. */
    public static <V> SupplierTask<V> supplier(String name, Supplier<V> body) {
        return new SupplierTask<>(null, name, body);
    }

    /**
     * An executor over {@code pool} whose every submit is a task of {@code name} ({@link #runnable},
     * {@link #callable}) with its queue wait, under the submitting thread's innermost open span. The task
     * is handed to {@code pool.execute} and is itself the {@link java.util.concurrent.Future} that {@code
     * submit} returns, so a submit allocates one object; so do {@code invokeAll} and {@code invokeAny}, one
     * task per element. {@code Future.cancel} cancels the task first: the body of a task that has not begun
     * never runs. A submit that {@code pool} refuses is recorded as rejected, and the {@link
     * RejectedExecutionException} is thrown on. A task made by {@link #runnable} or {@link #callable} goes
     * in as it is, under its own name, and is the future. Shutting down shuts {@code pool} down. A null
     * {@code pool} is misuse (strict mode throws): the executor then runs every task on the submitting
     * thread.
     *
     * <pre>{@code
     * ExecutorService match = Tempo.executor(pool, "match");
     * Future<Hit> f = match.submit(() -> score(query)); // a "match" task under the current span
     * }</pre>
     */
    public static ExecutorService executor(ExecutorService pool, Name name) {
        return new TaskExecutor(pool(pool), name);
    }

    /** Like {@link #executor(ExecutorService, Name)}; the name is added to the global table if new. */
    public static ExecutorService executor(ExecutorService pool, String name) {
        return new TaskExecutor(pool(pool), name == null ? null : Name.intern(name, false));
    }

    /** {@code pool}, or for null (misuse in the calling thread's trace) one that runs every task at once. */
    private static ExecutorService pool(ExecutorService pool) {
        if (pool != null) return pool;
        Lane l = SLOT.get().current;
        if (l != null) l.scope.misuse("null pool");
        return new TaskExecutor.Inline();
    }

    /**
     * Adds a global listener, called with the duration in nanoseconds of every timed occurrence of
     * {@code name}: spans, steps, recorded ticks, phases, tasks and {@link Span#add}, in every trace
     * started from now on, and outside traces for spans, steps and tasks. Set listeners once at start-up. A listener runs on
     * the thread that ends the occurrence, without a lock, so it must be thread-safe; a throwable it
     * raises is counted as misuse in that trace.
     */
    public static void on(Name name, LongConsumer nanos) {
        if (name != null) name.listen(nanos);
    }

    /** Like {@link #on(Name, LongConsumer)}; the name is added to the global table if new. */
    public static void on(String name, LongConsumer nanos) {
        if (name != null) Name.intern(name, false).listen(nanos);
    }

    static int clampLevel(int level) {
        return Math.max(SPAN, Math.min(TICK, level));
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

    /**
     * An interned span or attribute name. Names are constants; dynamic data belongs in attributes. A name
     * carries no settings; its global listeners are added with {@link Tempo#on}.
     */
    public static final class Name {
        static final int DYNAMIC_CAP = 4096;
        static int dynamicCap = DYNAMIC_CAP; // guarded by TABLE; lowered by tests only
        private static final ConcurrentHashMap<String, Name> TABLE = new ConcurrentHashMap<>();
        private static volatile Name[] byId = new Name[256];
        private static int size, dynamicSize; // guarded by TABLE
        static volatile boolean anySink;    // some name has a listener; read once per trace
        private static final LongConsumer[] NO_SINKS = {};
        static final Name UNKNOWN = intern("?", false), OTHER = intern("<other>", false), EMPTY = create("");

        public final int id;
        final String text;
        volatile LongConsumer[] sinks = NO_SINKS; // replaced whole on add, so a racy read sees it whole

        private Name(int id, String text) {
            this.id = id;
            this.text = text;
        }

        void listen(LongConsumer nanos) {
            if (nanos == null) return;
            synchronized (this) {
                LongConsumer[] s = Arrays.copyOf(sinks, sinks.length + 1);
                s[s.length - 1] = nanos;
                sinks = s;
            }
            anySink = true;
        }

        /** Whether the name has a global listener. */
        boolean listened() {
            return sinks.length != 0;
        }

        /**
         * Dynamic names (the {@code String} overloads) stop growing the table at the cap and map to {@code
         * <other>}. {@code null} maps to {@code ?}, {@code ""} to the name of unnamed spans.
         */
        static Name intern(String text, boolean dynamic) {
            if (text == null) return UNKNOWN;
            Name n = TABLE.get(text);
            if (n != null) return n;
            synchronized (TABLE) {
                n = TABLE.get(text);
                if (n != null) return n;
                if (dynamic && dynamicSize >= dynamicCap) return OTHER;
                if (dynamic) dynamicSize++;
                return create(text);
            }
        }

        private static Name create(String text) {
            synchronized (TABLE) {
                Name[] t = byId;
                if (size == t.length) t = Arrays.copyOf(t, size * 2);
                Name n = t[size] = new Name(size, text);
                size++;
                byId = t;
                TABLE.put(text, n);
                return n;
            }
        }

        /** The name if it is in the table, without adding it; null otherwise and for {@code null}. */
        static Name find(String text) {
            return text == null ? null : TABLE.get(text);
        }

        /** Lets {@code more} new dynamic names in from now on; returns the cap to restore. Tests only. */
        static int capDynamic(int more) {
            synchronized (TABLE) {
                int old = dynamicCap;
                dynamicCap = Math.min(old, dynamicSize + more);
                return old;
            }
        }

        static void restoreCap(int cap) {
            synchronized (TABLE) {
                dynamicCap = cap;
            }
        }

        /** Whether {@code n}, interned from {@code text} by a {@code String} overload, stands for another name. */
        static boolean overflowed(Name n, String text) {
            return n == OTHER && !OTHER.text.equals(text);
        }

        static Name byId(int id) {
            return byId[id];
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * Settings for one trace; read once at {@link Tempo#trace(Name, Options)}. Immutable: every setter
     * returns a new {@code Options} with that one setting changed and leaves this one as it was, so a
     * constant configured once is safe to share between threads and traces, and a setting derived from it
     * never leaks back:
     *
     * <pre>{@code
     * static final Tempo.Options BLOCK = new Tempo.Options().budget(64).strict(true);
     * static final Tempo.Options DEBUG = BLOCK.cpu(true); // BLOCK still measures as the trace decides
     * }</pre>
     *
     * A trace started inside another one, a self-contained block (see {@link Tempo#trace(Name, Options)}),
     * takes {@code clock} and {@code lanes} from the outer trace and honours the rest for its own subtree.
     * What its {@code Options} leave unset it inherits from the outer trace: {@code headerBytes}, {@code
     * linkBase}, {@code cpu} and {@code strict} as the outer trace has them, and without a {@code budget}
     * of its own it has no sub-budget and draws from what the outer trace has left. What is set applies as
     * each setter says. A top-level trace takes the defaults for what is unset.
     */
    public static final class Options {
        static final Options DEFAULTS = new Options();
        // Bits of `set`: the settings given by a setter, which a block takes instead of the outer trace's.
        static final int BUDGET = 1, HEADER_BYTES = 1 << 1, LINK_BASE = 1 << 2;

        final int budget, headerBytes, lanes, cpu;
        final boolean strict;
        final LongSupplier clock, cpuClock; // cpuClock: tests only; null reads the thread's CPU time
        final LongSupplier wallClock;       // tests only
        final String linkBase;              // null: Link.base()
        final int set;                      // BUDGET, HEADER_BYTES, LINK_BASE: given by a setter (cpu: not CPU_AUTO)

        /** The defaults: a budget of 512, 3,000 header bytes, 256 lanes, CPU time as the level decides, lenient. */
        public Options() {
            this(512, 3000, 256, CPU_AUTO, false, SYSTEM_CLOCK, null, System::currentTimeMillis, null, 0);
        }

        private Options(int budget, int headerBytes, int lanes, int cpu, boolean strict, LongSupplier clock,
                LongSupplier cpuClock, LongSupplier wallClock, String linkBase, int set) {
            this.budget = budget;
            this.headerBytes = headerBytes;
            this.lanes = lanes;
            this.cpu = cpu;
            this.strict = strict;
            this.clock = clock;
            this.cpuClock = cpuClock;
            this.wallClock = wallClock;
            this.linkBase = linkBase;
            this.set = set;
        }

        /** Whether setting {@code bit} was given by a setter. */
        boolean isSet(int bit) {
            return (set & bit) != 0;
        }

        /**
         * Steps, ticks and tasks drawn individually per trace, default 512; past it they fold into
         * aggregates ({@link SpanView#aggregate()}), tasks with their queue waits.
         * Lanes take the budget in blocks of {@code max(1, budget / 64)} and give back what they did not use
         * when their task ends. In a self-contained block it is a sub-budget: the block draws at most this
         * many in its subtree, taken from what the outer trace has left, never more; a block without one
         * draws from what the outer trace has left, in the outer trace's blocks. Returns a copy.
         */
        public Options budget(int spans) {
            return new Options(Math.max(0, spans), headerBytes, lanes, cpu, strict, clock, cpuClock, wallClock, linkBase,
                    set | BUDGET);
        }

        /**
         * Thread CPU time of each lane root (the trace's root and every task begun on another thread) and
         * of the spans that ask with {@link Span#cpu()}; see {@link SpanView#cpuNanos()}. By default it
         * is measured whenever the trace records steps ({@link Trace#records(int)}) when the span opens, never
         * while it records spans only; the root's then only with {@code true}. {@code true} measures at every
         * level, {@code false} never. The first read in a JVM sets the clock up (it loads the management bean:
         * milliseconds); a trace that measures CPU pays it on the thread that starts it, or widens it to
         * measuring ({@link Trace#records(int)}), in the root's time, so no task's queue wait or run time ever
         * includes it. A task reads the clock as soon as it
         * wins its begin, before it sets up its thread's lane or reads its CPU time: both are run time,
         * not queue wait. A self-contained block that leaves it unset follows the outer trace's setting.
         * Returns a copy.
         */
        public Options cpu(boolean on) {
            return new Options(budget, headerBytes, lanes, on ? CPU_ON : CPU_OFF, strict, clock, cpuClock, wallClock, linkBase, set);
        }

        /** A fake CPU clock for tests, read on the calling thread like the real one; -1 is "not measured". A copy. */
        Options cpuClock(LongSupplier cpuNanos) {
            return new Options(budget, headerBytes, lanes, cpu, strict, clock, cpuNanos, wallClock, linkBase, set);
        }

        /** A fake wall clock in epoch milliseconds for tests, read once when the trace starts. A copy. */
        Options wallClock(LongSupplier millis) {
            return new Options(budget, headerBytes, lanes, cpu, strict, clock, cpuClock, millis, linkBase, set);
        }

        /**
         * Byte limit of the detailed header ({@link Trace#toHeader()}) and of the value a {@link Trace#toLink()}
         * carries; default 3000. {@link Trace#toHeader(int)} takes a limit per call. A self-contained block
         * that leaves it unset writes with the outer trace's limit. Returns a copy.
         */
        public Options headerBytes(int max) {
            return new Options(budget, Math.max(0, max), lanes, cpu, strict, clock, cpuClock, wallClock, linkBase,
                    set | HEADER_BYTES);
        }

        /**
         * The viewer that {@link Trace#toLink()} points at. By default the system property {@code
         * tempo.linkBase}, else the base the library was built with: {@code -PlinkBase}, else the GitHub
         * Pages site of the repository it was built from ({@code GITHUB_REPOSITORY} in GitHub Actions,
         * else the git remote {@code origin}), {@code https://dualuse.github.io/tempo/} for this one. A
         * copy of this file built elsewhere uses that address. Null restores the default. A self-contained
         * block that leaves it unset links to the outer trace's viewer; one given null, to the default.
         * Returns a copy.
         *
         * @throws IllegalArgumentException if {@code base} is not an absolute URL, or has a {@code #},
         *                                  a {@code ,}, whitespace or a non-ASCII character
         */
        public Options linkBase(String base) {
            if (base != null && !Link.valid(base)) throw new IllegalArgumentException("not a Tempo-Link base: " + base);
            return new Options(budget, headerBytes, lanes, cpu, strict, clock, cpuClock, wallClock, base, set | LINK_BASE);
        }

        /**
         * Nanosecond clock for every timestamp of the trace; default {@code System::nanoTime}. For tests. A
         * self-contained block keeps the outer trace's: one trace, one timeline. Returns a copy.
         */
        public Options clock(LongSupplier nanoClock) {
            return new Options(budget, headerBytes, lanes, cpu, strict, nanoClock == null ? SYSTEM_CLOCK : nanoClock,
                    cpuClock, wallClock, linkBase, set);
        }

        /**
         * Lanes of one trace, the starting thread's root lane included; default 256, at least 1. A lane
         * is one thread's record of the trace: a thread records its tasks in one lane, and opens another
         * only for a task that begins while that lane is open under another trace's task. A pool
         * of platform threads stays far below the cap; with a thread per task (virtual threads) the tasks
         * begun past it are not recorded. Such a task's record at the submit is left out of the tree, its
         * queue wait is added to {@link Trace#total(Name)} of its name, and it counts as dropped. A
         * self-contained block keeps the outer trace's lanes. Returns a copy.
         */
        public Options lanes(int max) {
            return new Options(budget, headerBytes, Math.max(1, max), cpu, strict, clock, cpuClock, wallClock, linkBase, set);
        }

        /**
         * Throws {@link AssertionError} at the offending call on misuse instead of healing silently. In a
         * self-contained block it holds for the block's subtree; strictness only widens there, so a strict
         * outer trace stays strict inside a lenient block, and a block that leaves it unset is as strict as
         * the outer trace. Returns a copy.
         */
        public Options strict(boolean on) {
            return new Options(budget, headerBytes, lanes, cpu, on, clock, cpuClock, wallClock, linkBase, set);
        }
    }

    /**
     * Per-trace settings, counters, the lane registry and the lane-less aggregates. Lanes and tasks
     * reference this instead of the {@link Trace}.
     */
    static final class Context {
        private static final AtomicLong IDS = new AtomicLong();
        private static final VarHandle LANES, DROPPED, UNFOLDS;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                LANES = l.findVarHandle(Context.class, "lanes", Lane.class);
                DROPPED = l.findVarHandle(Context.class, "dropped", int.class);
                UNFOLDS = l.findVarHandle(Context.class, "unfolds", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static final Context NONE = new Context(-1, false); // below SPAN and never seen: a tick through NOOP or a pooled handle takes Span.unrecorded
        /** Of the spans timed outside a trace for global listeners: records nothing, never released. */
        static final Context FREE = new Context(SPAN, true);

        final long id, epoch, wall; // wall: epoch milliseconds at the start
        final LongSupplier clock, cpuClock;
        final int maxLanes;
        final int unfoldCap;            // folded tasks a block may unfold, past the budget (Lane#unfold)
        @SuppressWarnings("unused") private volatile int unfolds; // via UNFOLDS
        final AtomicInteger misuses = new AtomicInteger();
        // The level and whether a step, tick or task was seen, in one word of each scope (Trace#mode), so a
        // step reads the level it is opened at in the same atomic step that fixes it, and records(level)
        // cannot widen in between: (level + 1) << 1 | SEEN, which keeps the levels in order (below SPAN is
        // Context.NONE's).
        static final int SEEN = 1, STEP_MODE = (STEP + 1) << 1, TICK_MODE = (TICK + 1) << 1;
        /**
         * The trace's own scope: its root {@link Trace}, which holds the level, budget, strictness, CPU
         * setting, listeners and lane-less totals of the whole trace. A self-contained block nested in it
         * is a scope of its own whose chain ({@link Trace#up}) ends here.
         */
        final Trace root;
        volatile boolean released;
        // Written under this lock; a thread sees a listener added elsewhere once it synchronizes with that
        // thread, e.g. through a task submitted after it. Listeners is immutable, so even a racy read
        // sees it whole.
        boolean listening; // a global listener existed at start, or a listener of the trace or a block was added
        private volatile Lane lanes;    // CAS-linked registry, newest first
        private volatile int dropped;   // tasks left unrecorded at the lane cap
        private long[] lost;            // guarded by this: (laneId << 32 | row, submit) of handoffs whose task has no record
        private int lostCount;

        private Context(int level, boolean listening) {
            id = -1;
            epoch = wall = 0;
            // Not Tempo.SYSTEM_CLOCK: this runs in Context's class initializer, which must not start Tempo's,
            // or Tempo.NOOP would be built while Context.NONE is still null.
            clock = System::nanoTime;
            cpuClock = null;
            maxLanes = unfoldCap = 0;
            this.listening = listening;
            root = new Trace(this, level);
        }

        /**
         * {@code zero}: the clock read as {@link Tempo#trace} was entered, the trace's zero point and the
         * root's start, so the setup that follows (the classes Tempo loads on first use, the lane, the first
         * CPU read of a JVM) is counted in the root's time, not hidden.
         */
        Context(Options o) { // tests: a context without a trace, its zero point now
            this(o, o.clock.getAsLong());
        }

        Context(Options o, long zero) {
            epoch = zero;
            wall = o.wallClock.getAsLong();
            id = IDS.incrementAndGet();
            clock = o.clock;
            cpuClock = o.cpuClock;
            maxLanes = o.lanes;
            unfoldCap = Math.max(8, o.budget / 8);
            listening = Name.anySink;
            root = new Trace(this, o);
        }

        long now() {
            return clock.getAsLong() - epoch;
        }

        /** Whether a span opening now at the trace's own level has its CPU time measured. */
        boolean cpu() {
            return root.measuresCpu();
        }

        /** What the trace records: {@link #SPAN}, {@link #STEP} or {@link #TICK}. */
        int level() {
            return root.level();
        }

        /** Tests: sets the level as if widened, without the checks. */
        void level(int level) {
            root.level(level);
        }

        long cpuNow() {
            return cpuClock == null ? Cpu.now() : cpuClock.getAsLong();
        }

        /** A misuse at the trace's own level: counted, and thrown in strict mode. */
        void misuse(String what) {
            root.misuse(what);
        }

        /** Tests: {@link Trace#sawStep()} of the trace's own scope. */
        int sawStep() {
            return root.sawStep();
        }

        /** Tests: {@link Trace#widen(int)} of the trace's own scope. */
        boolean widen(int level) {
            return root.widen(level);
        }

        /** {@link Trace#nameOf(String)} at the trace's own level. */
        Name name(String text) {
            return root.nameOf(text);
        }

        /**
         * Calls the global listeners of {@code n}, then the listeners of {@code scope} and of every scope
         * enclosing it up to the trace's own, each on its own: a throwable is caught and counted as
         * misuse, so the others still run and the recording stays consistent; the first one is returned
         * for strict mode. The walk is as long as the nesting of blocks, whatever their listeners.
         */
        Throwable notify(Trace scope, Name n, long nanos) {
            Throwable first = null;
            for (LongConsumer s : n.sinks) {
                Throwable t = call(s, nanos);
                if (first == null) first = t;
            }
            for (Trace sc = scope; sc != null; sc = sc.up) {
                Listeners l = sc.on;
                if (l == null) continue;
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

        boolean full() {
            Lane head = lanes;
            return head != null && head.id + 1 >= maxLanes;
        }

        /** Takes one of the {@link #unfoldCap} unfolds of the trace; false once they are spent. */
        boolean unfold() {
            int n;
            do {
                if ((n = unfolds) >= unfoldCap) return false;
            } while (!UNFOLDS.compareAndSet(this, n, n + 1));
            return true;
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
            Lane[] out = new Lane[head == null ? 0 : head.id + 1];
            for (Lane l = head; l != null; l = l.next) out[l.id] = l;
            return out;
        }

        /** The lane with id {@code id}, or null. */
        Lane lane(int id) {
            for (Lane l = lanes; l != null; l = l.next) if (l.id == id) return l;
            return null;
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

        /** Occurrences in blocks kept in an enclosing scope's aggregate entry, for want of room; see {@link Lane#aggregate}. */
        int unattributed() {
            int n = 0;
            for (Lane l = lanes; l != null; l = l.next) n += l.unattributed;
            return n;
        }

        /**
         * {@link Snapshot#total} read in place: the same rows, task waits and lane aggregates, summed
         * without a copy. A read racing {@link #release()} may see recycled chunks; like a Snapshot's, it
         * is thrown away, also when what it read made it fail.
         */
        Total total(int id) {
            if (released) return Total.EMPTY;
            long count = 0, sum = 0, min = Long.MAX_VALUE, max = 0, waits = 0, waitSum = 0, waitMax = 0;
            try {
                for (Lane l = lanes; l != null; l = l.next) {
                    if (l.retired()) continue;
                    long commit = l.committed();
                    int rows = (int) (commit >>> 32), events = (int) commit;
                    for (int r = 0; r < rows; r++) {
                        Rows c = l.rows[r >>> Lane.CHUNK_BITS];
                        int i = r & Lane.MASK, f = c.flags[i];
                        if (c.name[i] != id || (f & (HANDOFF | UNRUN)) != 0) continue;
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
                        int k = e * Aggs.KEY, b = e * Aggs.CELLS, kind = a.keys[k + Aggs.KIND];
                        // as in Shape: not ended yet, or anchored at a row past the rows read
                        if (a.keys[k + Aggs.NAME] != id || a.cells[b + Aggs.COUNT] == 0 || kind == Aggs.TASKS
                                || kind == Aggs.SPANS && a.keys[k] >= rows) continue;
                        if (kind != Aggs.UNRUN) { // a folded task that ran, a folded or undrawn span
                            count += a.cells[b + Aggs.COUNT];
                            sum += a.cells[b + Aggs.SUM];
                            min = Math.min(min, a.cells[b + Aggs.MIN]);
                            max = Math.max(max, a.cells[b + Aggs.MAX_DUR]);
                        }
                        if (kind == Aggs.RAN || kind == Aggs.UNRUN) { // folded tasks, run or cancelled, waited
                            waits += a.cells[b + Aggs.COUNT];
                            waitSum += a.cells[b + Aggs.WAIT_SUM];
                            waitMax = Math.max(waitMax, a.cells[b + Aggs.WAIT_MAX]);
                        }
                    }
                }
            } catch (RuntimeException e) {
                VarHandle.acquireFence();
                if (released) return Total.EMPTY;
                throw e;
            }
            VarHandle.acquireFence();
            return released ? Total.EMPTY : Totals.merge(root.totals, id, count, sum, min, max, waits, waitSum, waitMax);
        }

        /**
         * The totals of {@code id} in the subtree of self-contained block {@code block}, computed on read:
         * the rows below the block's row (on its lane, and on the lanes of its tasks through their links),
         * the queue waits of the task rows among them, the aggregate entries of the block's scope and of the
         * blocks nested in it (folded spans and tasks, undrawn steps and tasks), and the lane-less adds and
         * waits of that scope. Recording pays nothing for it. A read racing {@link #release()} is thrown
         * away, as in {@link #total(int)}.
         */
        Total total(int id, Trace block) {
            if (released) return Total.EMPTY;
            long count = 0, sum = 0, min = Long.MAX_VALUE, max = 0, waits = 0, waitSum = 0, waitMax = 0;
            try {
                Lane[] lanes = lanesById();
                int n = lanes.length;
                int[] rows = new int[n], events = new int[n];
                int[][] parent = new int[n][];
                long[][] link = new long[n][];
                for (int i = 0; i < n; i++) {
                    Lane l = lanes[i];
                    if (l == null || l.retired()) {
                        parent[i] = new int[0];
                        continue;
                    }
                    long commit = l.committed();
                    rows[i] = (int) (commit >>> 32);
                    events[i] = (int) commit;
                    parent[i] = new int[rows[i]];
                    for (int r = 0; r < rows[i]; r++) parent[i][r] = l.rows[r >>> Lane.CHUNK_BITS].parent[r & Lane.MASK];
                    for (int e = 0; e < events[i]; e++) {
                        Events c = l.events[e >>> Lane.CHUNK_BITS];
                        int k = e & Lane.MASK;
                        if ((c.key[k] & KIND_MASK) != XPARENT) continue;
                        if (link[i] == null) Arrays.fill(link[i] = new long[rows[i]], -1);
                        if (c.row[k] < rows[i]) link[i][c.row[k]] = c.value[k];
                    }
                }
                Lane bl = block.lane;
                boolean[][] in = subtree(bl == null ? -1 : bl.id, block.row, rows, parent, link);
                for (int i = 0; i < n; i++) {
                    Lane l = lanes[i];
                    if (l == null || l.retired()) continue;
                    boolean[] mine = in[i];
                    for (int r = 0; r < rows[i]; r++) {
                        if (!mine[r]) continue;
                        Rows c = l.rows[r >>> Lane.CHUNK_BITS];
                        int k = r & Lane.MASK, f = c.flags[k];
                        if (c.name[k] != id || (f & (HANDOFF | UNRUN)) != 0) continue;
                        long d = (long) Lane.DUR.getOpaque(c.dur, k);
                        if (d == Lane.OPEN) continue;
                        count++;
                        sum += d;
                        min = Math.min(min, d);
                        max = Math.max(max, d);
                    }
                    for (int e = 0; e < events[i]; e++) {
                        Events c = l.events[e >>> Lane.CHUNK_BITS];
                        int k = e & Lane.MASK, r = c.row[k];
                        if ((c.key[k] & KIND_MASK) != QWAIT || r >= rows[i] || !mine[r] || l.nameOf(r) != id) continue;
                        long w = c.value[k];
                        waits++;
                        waitSum += w;
                        waitMax = Math.max(waitMax, w);
                    }
                    Aggs a = (Aggs) Lane.AGGS.getAcquire(l);
                    for (int e = 0, m = a == null ? 0 : a.size(); e < m; e++) {
                        int k = e * Aggs.KEY, b = e * Aggs.CELLS, kind = a.keys[k + Aggs.KIND];
                        if (a.keys[k + Aggs.NAME] != id || a.cells[b + Aggs.COUNT] == 0 || kind == Aggs.TASKS
                                || kind == Aggs.SPANS && a.keys[k] >= rows[i] || !Trace.within(a.scopes[e], block)) continue;
                        if (kind != Aggs.UNRUN) {
                            count += a.cells[b + Aggs.COUNT];
                            sum += a.cells[b + Aggs.SUM];
                            min = Math.min(min, a.cells[b + Aggs.MIN]);
                            max = Math.max(max, a.cells[b + Aggs.MAX_DUR]);
                        }
                        if (kind == Aggs.RAN || kind == Aggs.UNRUN) {
                            waits += a.cells[b + Aggs.COUNT];
                            waitSum += a.cells[b + Aggs.WAIT_SUM];
                            waitMax = Math.max(waitMax, a.cells[b + Aggs.WAIT_MAX]);
                        }
                    }
                }
            } catch (RuntimeException e) {
                VarHandle.acquireFence();
                if (released) return Total.EMPTY;
                throw e;
            }
            VarHandle.acquireFence();
            return released ? Total.EMPTY : Totals.merge(block.totals, id, count, sum, min, max, waits, waitSum, waitMax);
        }

        /**
         * The rows of each lane in the subtree of row {@code rootRow} of lane {@code rootLane}: that row, the
         * rows linked into it ({@code laneId << 32 | row}, -1 for none), and the rows without a link whose
         * parent is in it. A link decides first, so a task belongs with everything in it to where it was
         * submitted, wherever it ran: a task's run or cancel links to its record at the submit, a span of an
         * undrawn task or of a task begun inline in another scope to the span it was submitted from, an
         * unfolded task to that span. Iterated until nothing changes, as a link may point to a lane of any id.
         * By lane id, empty for a lane not read or a root that is not a row.
         */
        static boolean[][] subtree(int rootLane, int rootRow, int[] rows, int[][] parent, long[][] link) {
            int n = rows.length;
            boolean[][] in = new boolean[n][];
            for (int i = 0; i < n; i++) in[i] = new boolean[rows[i]];
            if (rootLane < 0 || rootLane >= n || rootRow < 0 || rootRow >= rows[rootLane]) return in;
            in[rootLane][rootRow] = true;
            for (boolean changed = true; changed; ) {
                changed = false;
                for (int i = 0; i < n; i++) {
                    boolean[] b = in[i];
                    int[] p = parent[i];
                    long[] k = link[i];
                    for (int r = 0; r < rows[i]; r++) {
                        if (b[r]) continue;
                        boolean y;
                        if (k != null && k[r] >= 0) { // a linked row is where its link points, wherever it ran
                            int x = (int) (k[r] >>> 32), xr = (int) k[r];
                            y = x < n && xr >= 0 && xr < rows[x] && in[x][xr];
                        } else {
                            int q = p[r];
                            y = q >= 0 && q < r && b[q];
                        }
                        if (y) {
                            b[r] = true;
                            changed = true;
                        }
                    }
                }
            }
            return in;
        }

        /** Tests: the trace's own step and tick budget not handed out to a lane. */
        int unspent() {
            return root.unspent();
        }

        Totals frozenTotals() {
            return root.frozenTotals();
        }

        /**
         * A task begun or cancelled without a record: at the lane cap ({@code count}), or at a lane's row,
         * depth, event or aggregate cap (counted there). Its record at the submit is left out of the tree, a
         * folded one out of its aggregate, and its queue wait goes to the trace's total of its name.
         */
        void lost(Task h, boolean count) {
            if (count) DROPPED.getAndAdd(this, 1);
            if (released) return;
            h.scope().addTotal(h.name.id, true, h.wait);
            if (h.undrawn()) return;
            int row = h.row();
            synchronized (this) {
                if (lost == null) lost = new long[8];
                else if (lostCount == lost.length) lost = Arrays.copyOf(lost, 2 * lostCount);
                lost[lostCount++] = (long) h.lane.id << 32 | row & 0xFFFF_FFFFL;
                lost[lostCount++] = h.submit;
            }
        }

        /** Pairs of {@code (laneId << 32 | row, submit)}; a folded task's row is {@code ~entry}. */
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
     * Lane-less totals of one trace, keyed by name: thread-safe, lock-free, allocation-free once a
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
        static Total merge(Totals totals, int id, long count, long sum, long min, long max,
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
            return new Total(count, sum, count == 0 ? 0 : min, max, waitSum, waitMax);
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
     *
     * <p>The slot also pools the handles of the thread's recorded spans and phases. A lane takes one per
     * span or phase and gives them all back when it retires, that is when its trace is released, so a
     * thread holds as many handles as its largest traces needed at once. Pooled handles reach no lane and
     * no trace.
     */
    static final class ThreadSlot {
        private static final VarHandle RETURNED, RETURNED_PHASES, RETURNED_KITS;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                RETURNED = l.findVarHandle(ThreadSlot.class, "returned", Span.class);
                RETURNED_PHASES = l.findVarHandle(ThreadSlot.class, "returnedPhases", Phase.class);
                RETURNED_KITS = l.findVarHandle(ThreadSlot.class, "returnedKits", Kit.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        Lane current;
        Lane free; // the spans timed outside a trace for global listeners; created on first use
        StringBuilder text; // the header encoder's, reused while it stays small (Header.KEEP)
        // TaskExecutor: the task its newTaskFor just made, until the execute that follows takes it
        TaskFuture<?> made;
        @SuppressWarnings("unchecked")
        final WeakReference<Lane>[] lanes = new WeakReference[4];
        private int victim;
        // Pooled handles, linked by Span.link: spare is the owner's own list; returned is a stack that a
        // retiring lane pushes its handles onto from any thread, and that the owner takes whole.
        private Span spare;
        @SuppressWarnings("unused") private volatile Span returned;
        // The same for the phase handles, linked by Phase.link, and for the lanes' arrays, linked by Kit.link.
        private Phase sparePhase;
        @SuppressWarnings("unused") private volatile Phase returnedPhases;
        private Kit spareKit;
        @SuppressWarnings("unused") private volatile Kit returnedKits;

        /** Owner only: a pooled handle, or null if there is none. */
        Span take() {
            Span h = spare;
            if (h == null) {
                if (RETURNED.getAcquire(this) == null) return null;
                h = (Span) RETURNED.getAndSet(this, null);
            }
            spare = h.link;
            return h;
        }

        /** Any thread: pools the handles from {@code head} to {@code tail}, linked by {@link Span#link}. */
        void give(Span head, Span tail) {
            Span top;
            do {
                top = (Span) RETURNED.getVolatile(this);
                tail.link = top;
            } while (!RETURNED.compareAndSet(this, top, head));
        }

        /** Owner only: a pooled phase handle, or null if there is none. */
        Phase takePhase() {
            Phase p = sparePhase;
            if (p == null) {
                if (RETURNED_PHASES.getAcquire(this) == null) return null;
                p = (Phase) RETURNED_PHASES.getAndSet(this, null);
            }
            sparePhase = p.link;
            return p;
        }

        /** Any thread: pools the phase handles from {@code head} to {@code tail}, linked by {@link Phase#link}. */
        void givePhases(Phase head, Phase tail) {
            Phase top;
            do {
                top = (Phase) RETURNED_PHASES.getVolatile(this);
                tail.link = top;
            } while (!RETURNED_PHASES.compareAndSet(this, top, head));
        }

        /** Owner only: the arrays of a lane this thread had before, or null if there are none. */
        Kit takeKit() {
            Kit k = spareKit;
            if (k == null) {
                if (RETURNED_KITS.getAcquire(this) == null) return null;
                k = (Kit) RETURNED_KITS.getAndSet(this, null);
            }
            spareKit = k.link;
            k.link = null;
            return k;
        }

        /** Any thread: pools the arrays of a retired lane of this thread. */
        void giveKit(Kit k) {
            Kit top;
            do {
                top = (Kit) RETURNED_KITS.getVolatile(this);
                k.link = top;
            } while (!RETURNED_KITS.compareAndSet(this, top, k));
        }

        /** Kits pooled; for tests, while no thread uses the slot. */
        int kits() {
            int n = 0;
            for (Kit k = spareKit; k != null; k = k.link) n++;
            for (Kit k = returnedKits; k != null; k = k.link) n++;
            return n;
        }

        /** Handles pooled, span and phase handles; for tests, while no thread uses the slot. */
        int pooled() {
            int n = 0;
            for (Span h = spare; h != null; h = h.link) n++;
            for (Span h = returned; h != null; h = h.link) n++;
            for (Phase p = sparePhase; p != null; p = p.link) n++;
            for (Phase p = returnedPhases; p != null; p = p.link) n++;
            return n;
        }

        /**
         * A span or step of {@code n} opened outside a trace: timed for the name's global listeners if it has
         * any, on this thread's free lane, which records nothing; else {@link #NOOP}.
         */
        Span free(Name n) {
            if (n == null || !n.listened()) return NOOP;
            Lane l = free;
            if (l == null) free = l = new Lane(Context.FREE, this);
            return l.freeFrame(n);
        }

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
            if (l == null && !ctx.full()) {
                Lane fresh = new Lane(ctx, this);
                if ((l = ctx.register(fresh)) == null) fresh.giveBack(); // the cap was reached in between
            }
            if (l != null) lanes[free >= 0 ? free : victim++ & 3] = l.ref();
            return l;
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * The arrays of a lane: its chunk tables, open stack, frame and phase state and aggregates. A thread's
     * slot pools them from one of its lanes to the next, so a thread's lane in its next trace allocates only
     * itself (see {@link Lane#retire}). A lane retired races readers on other threads as its chunks do: a
     * reader of a released trace may see the arrays in use by a later lane, and discards what it read.
     */
    static final class Kit {
        /**
         * Aggregates up to this many entries are pooled with the kit, so a thread's next trace reuses the table
         * however many entries the last one grew it to: 512 entries take 152 B each, 76 KB per kit (one per
         * lane a thread has open at once). It covers a trace that calls a block a few hundred times per lane,
         * each with an undrawn task or step, whose entries are kept per block. A larger table goes to the GC.
         */
        static final int KEEP_AGGS = 512;

        final Rows[] rows = new Rows[Lane.CHUNKS];
        final int[] rowAt = new int[Lane.MAX_DEPTH];
        final Span[] handles = new Span[Lane.MAX_DEPTH];
        Events[] events;
        Span[] frames;
        Aggs aggs;
        long[] times, cpuAt;
        int[] phaseAt;
        Phase[] rowless;
        Trace[] outer;
        int[] outerAt;
        Lane[] crossLane;
        int[] crossRow;
        Kit link; // the next kit in its slot's pool
    }

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
        // rowAt values without a row: NO_ROW in a folded task begun outside the trace; CROSS in a task whose
        // spans hang from row crossRow[d] of lane crossLane[d], where it was submitted: an undrawn task begun
        // outside the trace, or an undrawn or folded task begun inline in another scope than its own.
        static final int NO_ROW = -1, CROSS = -2;
        static final long OPEN = Long.MIN_VALUE;

        private static final VarHandle COMMIT, STATE, LABELS, AGGS, WAITING, DUR = MethodHandles.arrayElementVarHandle(long[].class);

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                COMMIT = l.findVarHandle(Lane.class, "commit", long.class);
                STATE = l.findVarHandle(Lane.class, "state", int.class);
                LABELS = l.findVarHandle(Lane.class, "labels", String[].class);
                AGGS = l.findVarHandle(Lane.class, "aggs", Aggs.class);
                WAITING = l.findVarHandle(Lane.class, "waiting", Task.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        /** The lane of a pooled handle: retired from the start, so every use of such a handle is stale. */
        static final Lane POOLED = new Lane(Context.NONE, null);

        static {
            POOLED.state = RETIRED;
        }

        final Context ctx;
        final Thread owner;
        final ThreadSlot slot;
        final boolean free; // a thread's free lane: frames for global listeners only, never registered
        int id;
        Lane next; // trace registry link, set before the lane is published

        // Chunk tables never reallocate; a chunk is stored before the commit that exposes its first element.
        // The event table is created with the first event, so a lane without attributes never pays for it.
        // The tables and the arrays below come from the kit, the owner thread's arrays of an earlier lane.
        private final Kit kit;
        private WeakReference<Lane> ref; // this lane in its owner's slot, made once
        final Rows[] rows;
        Events[] events;

        // Open stack: rowAt[d] is the open row at depth d < depth (or NO_ROW, CROSS), handles[d] its handle. A recorded span has
        // a handle of its own, taken from the slot's pool (issued, linked by Span.link, newest first, until
        // the lane retires); a trace's root and a nested trace have their Trace. A folded or undrawn span is
        // a frame without a row: its handle is the one of its depth in frames, its row is FRAME, and rowAt
        // holds the row of the nearest recorded span below it, which is the parent of anything opened above.
        final int[] rowAt;
        final Span[] handles;
        private Span[] frames; // created with the first frame
        private Span issued, issuedTail;
        int depth, rowCount, eventCount, dropped;
        int unattributed; // occurrences of a block kept in an enclosing scope's entry for want of room (Aggs#SCOPED)
        int credit, folded; // step and tick budget held by this lane; those folded for want of it

        // Folded and undrawn spans; replaced by a grown copy via AGGS.setRelease, like labels.
        private Aggs aggs;

        // The folded tasks submitted here that may not have begun, newest first and linked by Task.next, so
        // a render knows when those that never started were submitted (Shape#settle). The owner pushes via
        // WAITING.setRelease and, once the list has doubled, unlinks the begun and cancelled ones; an
        // unlinked task still points down the list, so a reader walking it is not lost.
        private Task waiting;
        private int waitingRoom; // pushes until the list has doubled

        // While rowAt[d] is CROSS: the lane and row that spans opened at d + 1 hang from (linked by XPARENT),
        // so they count where the task was submitted. From the kit, created with the lane's first.
        private Lane[] crossLane;
        private int[] crossRow;

        // CPU time at open of the rows at the depths whose bit is set in cpuMask; created on first use.
        long cpuMask;
        long[] cpuAt;

        private Throwable thrown; // strict mode: a listener's throwable, rethrown once the operation is done

        // The scope: the innermost self-contained block open here (a nested Trace), or the trace's own root
        // when none is, which the spans at depths >= scopeAt belong to. Its level, budget, strictness and CPU
        // setting are what the lane records by, and its listeners and those of its enclosing blocks hear
        // what ends there. A block switches it at its depth, and so does a task submitted in another scope
        // than the one it begins in: outer[d] and outerAt[d] keep the scope and scopeAt from before the
        // switch at depth d, until the span there closes. Without blocks scopeAt stays -1 and nothing here
        // is touched. The arrays come with the kit, created with the lane's first switch.
        Trace scope;
        int scopeAt = -1;
        private Trace[] outer;
        private int[] outerAt;

        // Phases and hand timing of the open span at depth d, valid while bit d of timed is set: opening a
        // span at d clears the bit, and its first phase, lap, start or stop sets the state up (the arrays
        // come with the lane's first). times, at d * 4: MARK the last boundary, the span's start before the
        // first one; FIRST the first boundary, while BOUNDS > 0; START the open phase's start; STOP the end
        // that stop() gave. phaseAt, at d * 4: ROW the open phase's row (NO_PHASE: none open; ROWLESS: one
        // without a row, of a folded or undrawn span or past the row cap), NAME its name, BOUNDS the
        // boundaries so far, MODE: MANUAL timed by hand (a phase starts at the last boundary), STOPPED.
        static final int NO_PHASE = -1, ROWLESS = -2, MARK = 0, FIRST = 1, START = 2, STOP = 3,
                ROW = 0, NAME = 1, BOUNDS = 2, MODE = 3, MANUAL = 1, STOPPED = 2;
        long timed;
        private long[] times;
        private int[] phaseAt;
        private Phase[] rowless;                       // the handle the rowless phases at depth d share
        private Phase issuedPhases, issuedPhasesTail; // phase handles taken from the slot, as issued above

        // Phase labels, referenced by LABEL events. Grown by copy and stored via LABELS.setRelease, so a
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
            free = ctx == Context.FREE;
            if (free) id = -1;
            Kit k = slot == null || free ? null : slot.takeKit();
            kit = k = k == null ? new Kit() : k;
            rows = k.rows;
            rowAt = k.rowAt;
            handles = k.handles;
            events = k.events;
            frames = k.frames;
            times = k.times;
            cpuAt = k.cpuAt;
            phaseAt = k.phaseAt;
            rowless = k.rowless;
            outer = k.outer;
            outerAt = k.outerAt;
            crossLane = k.crossLane;
            crossRow = k.crossRow;
            scope = ctx.root;
        }

        /**
         * Makes {@code s} the scope of the span opening at depth {@code d} and of everything above it, until
         * that span closes ({@link #leaveScopes}). Budget held for the scope before goes back to it.
         */
        private void enterScope(Trace s, int d) {
            if (outer == null) {
                outer = new Trace[MAX_DEPTH];
                outerAt = new int[MAX_DEPTH];
            }
            if (credit != 0) {
                scope.refund(credit);
                credit = 0;
            }
            outer[d] = scope;
            outerAt[d] = scopeAt;
            scope = s;
            scopeAt = d;
        }

        /** Restores the scope from before every switch at depth {@code d} or above; the spans there have ended. */
        private void leaveScopes(int d) {
            while (scopeAt >= d) {
                if (credit != 0) {
                    scope.refund(credit);
                    credit = 0;
                }
                int at = scopeAt;
                scope = outer[at];
                scopeAt = outerAt[at];
                outer[at] = null;
            }
        }

        /** The scope of the open span at depth {@code d}. */
        Trace scopeOf(int d) {
            Trace s = scope;
            for (int at = scopeAt; d < at; at = outerAt[at]) s = outer[at];
            return s;
        }

        /** This lane, weakly, for its owner's slot; made once, so a lane found again costs nothing. Owner only. */
        WeakReference<Lane> ref() {
            WeakReference<Lane> r = ref;
            return r != null ? r : (ref = new WeakReference<>(this));
        }

        /** A lane that was never registered (at the lane cap): its kit goes back to the slot. */
        void giveBack() {
            if (slot != null && !free) slot.giveKit(kit);
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

        /**
         * The submit of task {@code t} under the innermost open span, named {@code n} or, if given, {@code
         * text}. When the trace records steps it is drawn, against the budget: a closed record at the submit
         * time; past the budget, or in a folded task (no recorded span to hang from), no row but a submit
         * counted in a TASKS aggregate, which the task folds into. Otherwise, or at a cap, it is undrawn:
         * nothing is written, and the task keeps the row its spans will hang from.
         */
        void submit(Task t, Name n, String text, int bits) {
            int flags = 0;
            Trace sc = scope;
            if (text != null) {
                n = sc.nameOf(text);
                if (Name.overflowed(n, text)) flags = MISUSE;
            }
            if (n == null) {
                sc.misuse("null name");
                n = Name.UNKNOWN;
                flags = MISUSE;
            }
            int mode = sc.sawStep();
            t.name = n;
            int p = rowAt[depth - 1];
            if (mode >= Context.STEP_MODE) {
                if (p >= 0 && (credit != 0 || (credit = sc.credits()) != 0)) {
                    int r = row(n, flags | HANDOFF, p);
                    if (r >= 0) {
                        credit--;
                        long now = ctx.now();
                        closed(r, now, 0);
                        publish();
                        t.init(this, r, bits, now, sc);
                        return;
                    }
                } else {
                    int e = entry(n, Aggs.TASKS);
                    if (e >= 0) {
                        long now = ctx.now();
                        aggs.submit(e, now);
                        folded++;
                        t.init(this, ~e, bits, now, sc);
                        queue(t); // the waiting list takes its link: its scope is its TASKS entry's
                        return;
                    }
                }
            }
            Lane at = this;
            if (p == CROSS) {
                at = crossLane[depth - 1];
                p = crossRow[depth - 1];
            }
            t.init(at, p < 0 ? NO_ROW : p, bits | Task.UNDRAWN, ctx.now(), sc);
        }

        /** Pushes folded task {@code h} on the waiting list, first unlinking those that left it if it has doubled. */
        private void queue(Task h) {
            if (--waitingRoom < 0) {
                int n = 0;
                for (Task x = waiting, prev = null; x != null; x = (Task) x.next) {
                    if (x.waiting()) {
                        n++;
                        prev = x;
                    } else if (prev == null) {
                        waiting = (Task) x.next;
                    } else {
                        prev.next = x.next;
                    }
                }
                waitingRoom = Math.max(16, 2 * n) - n - 1;
            }
            h.next = waiting;
            WAITING.setRelease(this, h);
        }

        /** The earliest submit by TASKS entry of the folded tasks submitted here that still wait, or null. */
        long[] waitingSince(int entries) {
            Task h = (Task) WAITING.getAcquire(this);
            if (h == null) return null;
            long[] since = new long[entries];
            Arrays.fill(since, Long.MAX_VALUE);
            for (; h != null; h = (Task) h.next) {
                int e = ~h.row();
                if (e < entries && h.waiting() && h.submit < since[e]) since[e] = h.submit;
            }
            return since;
        }

        /**
         * Records task {@code h} on this, the calling thread's lane: a lane root, or, while the thread is
         * inside the trace already (inline), a child of the innermost open span. The row is linked to the
         * task's record at the submit and carries the queue wait, which ends at {@code now}: the clock read
         * right after the begin or cancel won the task, before this lane was looked up or set up. A
         * cancelled task ({@code run} false) is a closed zero-length row. Returns the task's span ({@link
         * #NOOP} if cancelled or retired), or null when a cap leaves no room.
         */
        Span task(Task h, boolean run, long now) {
            if (h.undrawn()) return undrawnTask(h, run, now);
            int hr = h.row();
            if (hr < 0) return foldedTask(h, run, now);
            boolean root = depth == 0;
            if (root && !activate()) return NOOP;
            if (h.lane.retired() || h.lane.nameAt(hr) < 0) { // the submitter's lane is retired: the trace was released
                if (root) idle();
                return NOOP;
            }
            Name n = h.name;
            Span s = NOOP;
            int r = -1, d = depth;
            if (eventCount > MAX_EVENTS - 2) {
                dropped++;
            } else if (run) {
                Trace sc = h.scope();
                if (sc != scope) enterScope(sc, d); // a task carries the scope it was submitted in
                s = stack(n, TASK);
                r = s == NOOP ? -1 : s.row;
                if (r < 0 && scopeAt >= d) leaveScopes(d);
            } else {
                r = row(n, TASK | CANCELLED | UNRUN, root ? -1 : Math.max(NO_ROW, rowAt[depth - 1]));
            }
            if (r < 0) {
                if (root) idle();
                return null;
            }
            if (run && root && scope.measuresCpu()) sampleCpu(0); // after the start: the read is run time, not queue wait
            Rows c = rows[r >>> CHUNK_BITS];
            c.start[r & MASK] = now;
            if (!run) DUR.setOpaque(c.dur, r & MASK, 0L);
            append(r, XPARENT, (long) h.lane.id << 32 | hr);
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

        /**
         * The task of a folded handoff, whose TASKS entry is {@code ~h.row()} in the submitter's lane. It writes
         * no row: begun, it is a frame whose occurrence (with its queue wait) ends into this lane's RAN entry
         * of that TASKS entry; cancelled, a zero-length UNRUN occurrence. Only the owner writes either, and a
         * render merges them into the TASKS entry. Begun inline in another scope than the one it was
         * submitted in, its spans hang from the span it was submitted from (CROSS), as an undrawn task's do.
         * {@link #NOOP} when cancelled or retired, null at a cap. The queue wait ends at {@code now}, as in
         * {@link #task}.
         */
        private Span foldedTask(Task h, boolean run, long now) {
            int d = depth, target = ~h.row();
            if (d == MAX_DEPTH) {
                dropped++;
                return null;
            }
            boolean root = d == 0;
            if (root && !activate()) return NOOP;
            Aggs t = (Aggs) AGGS.getAcquire(h.lane); // read once: the name and the scope come from one table
            int id = t == null ? -1 : t.keys[target * Aggs.KEY + Aggs.NAME];
            Trace sc = id < 0 ? scope : h.lane.scopeIn(t, target);
            int e = id < 0 ? -1 : aggregate(target, h.lane.id, -1, id, run ? Aggs.RAN : Aggs.UNRUN, sc);
            if (e < 0) {
                if (root) idle();
                return id < 0 ? NOOP : null; // released, or past the aggregate cap (counted)
            }
            long wait = Math.max(0, now - h.submit);
            if (run && root && sc.measuresCpu()) sampleCpu(0);
            aggs.submit(e, h.submit);
            if (!run) {
                aggs.add(e, h.submit, 0, wait, -1, CANCELLED | UNRUN, true);
                if (root) idle();
                return NOOP;
            }
            boolean foreign = sc != scope;
            if (foreign) enterScope(sc, d);
            Span s = openFrame(Name.byId(id), e, wait, now, null);
            s.folded = true;
            int anchor = t.keys[target * Aggs.KEY + Aggs.ANCHOR];
            if (foreign && !root && anchor >= 0) cross(d, h.lane, anchor); // inline in another scope: under its submit
            if (root) {
                saved = slot.current;
                slot.current = this;
            }
            return s;
        }

        /**
         * An undrawn task: like a folded one it writes no row, but its occurrence goes to a RAN or UNRUN
         * entry of no TASKS entry (lane -1), which only the totals read. Begun outside the trace, its spans
         * hang from the row the task kept at its submit (CROSS), on this thread; inline, from the innermost
         * recorded span here, unless it was submitted in another scope (a block, or outside the block open
         * here): then from the row kept at its submit too, so they count where it was submitted. {@link
         * #NOOP} when cancelled or retired, null at a cap.
         */
        private Span undrawnTask(Task h, boolean run, long now) {
            int d = depth;
            if (d == MAX_DEPTH) {
                dropped++;
                return null;
            }
            boolean root = d == 0;
            if (root && !activate()) return NOOP;
            Trace sc = h.scope();
            int e = aggregate(-1, -1, -1, h.name.id, run ? Aggs.RAN : Aggs.UNRUN, sc);
            if (e < 0) {
                if (root) idle();
                return null;
            }
            long wait = Math.max(0, now - h.submit);
            if (!run) {
                aggs.add(e, h.submit, 0, wait, -1, CANCELLED | UNRUN, false);
                if (root) idle();
                return NOOP;
            }
            if (root && sc.measuresCpu()) sampleCpu(0);
            boolean foreign = sc != scope;
            if (foreign) enterScope(sc, d);
            Span s = openFrame(h.name, e, wait, now, null);
            s.hidden = true;
            int r = h.row();
            if (r >= 0 && (root || foreign)) cross(d, h.lane, r);
            if (root) {
                saved = slot.current;
                slot.current = this;
            }
            return s;
        }

        /** The scope of TASKS entry {@code e} of this lane, from any thread; see {@link #scopeIn}. */
        Trace taskScope(int e) {
            return scopeIn((Aggs) AGGS.getAcquire(this), e);
        }

        /**
         * The scope of TASKS entry {@code e} in {@code a}, a table of this lane read by any thread; the trace's
         * own root once the lane is retired. A reader racing {@link Context#release()} may hold the table
         * after the retired lane gave it to its thread's next lane, of another trace: a scope of that trace
         * is never handed out, so a late task never counts, credits or throws there.
         */
        Trace scopeIn(Aggs a, int e) {
            Trace s = a == null ? null : a.scopes[e];
            return s != null && s.ctx == ctx ? s : ctx.root;
        }

        /** The anchor row of TASKS entry {@code e} of this lane, from any thread; -1 for none or once retired. */
        int taskAnchor(int e) {
            Aggs a = (Aggs) AGGS.getAcquire(this);
            return a == null ? -1 : a.keys[e * Aggs.KEY + Aggs.ANCHOR];
        }

        /**
         * Opens the trace's root, which starts at 0: the clock reading the {@link Context} took first. {@code
         * cpu} is the CPU time read right after it, or -1 if not measured. So the setup in between, this
         * lane's included, is the root's own time.
         */
        Span openRoot(Name n, int flags, long cpu) {
            return openRoot(null, n, flags, cpu);
        }

        /** {@link #openRoot(Name, int, long)} with {@code t} as the root's handle; null for a plain one. */
        Span openRoot(Trace t, Name n, int flags, long cpu) {
            if (!activate()) return NOOP;
            Span h = stack(n, flags, t);
            if (h == NOOP) {
                idle();
                return NOOP;
            }
            startCpu(0, cpu);
            rows[h.row >>> CHUNK_BITS].start[h.row & MASK] = 0;
            publish();
            saved = slot.current;
            slot.current = this;
            return h;
        }

        // ---- opening at the top of the stack (owner thread; the free lane times for listeners only) ----

        /** A span; {@code null} is misuse, recorded as {@code ?}. */
        Span openSpan(Name n, int flags) {
            if (free) return n != null && n.listened() ? freeFrame(n) : NOOP;
            if (timed != 0 && underStop()) flags |= afterStop();
            if (n == null) {
                scope.misuse("null name");
                return push(Name.UNKNOWN, flags | MISUSE);
            }
            return push(n, flags);
        }

        Span openSpan(String text) {
            if (free) return openSpan(Name.find(text), 0);
            Name n = scope.nameOf(text);
            return openSpan(n, Name.overflowed(n, text) ? MISUSE : 0);
        }

        /** A step: drawn when the trace records steps, against the budget; else an undrawn frame. */
        Span openStep(Name n, int flags) {
            if (free) return n != null && n.listened() ? freeFrame(n) : NOOP;
            int mode = scope.sawStep();
            if (timed != 0 && underStop()) flags |= afterStop();
            if (n == null) {
                scope.misuse("null name");
                n = Name.UNKNOWN;
                flags |= MISUSE;
            }
            return mode < Context.STEP_MODE ? undrawn(n) : budgeted(n, flags | STEP_ROW);
        }

        Span openStep(String text) {
            if (free) return openStep(Name.find(text), 0);
            Name n = scope.nameOf(text);
            return openStep(n, Name.overflowed(n, text) ? MISUSE : 0);
        }

        /** A tick: drawn when the trace records ticks, against the budget; else {@link #NOOP}. */
        Span openTick(Name n, int flags) {
            Trace sc = scope;
            if (sc.mode < Context.TICK_MODE && (free || sc.sawStep() < Context.TICK_MODE)) return NOOP;
            if (timed != 0 && underStop()) flags |= afterStop();
            if (n == null) {
                sc.misuse("null name");
                n = Name.UNKNOWN;
                flags |= MISUSE;
            }
            return budgeted(n, flags | TICK_ROW);
        }

        Span openTick(String text) {
            Trace sc = scope;
            if (sc.mode < Context.TICK_MODE && (free || sc.sawStep() < Context.TICK_MODE)) return NOOP;
            Name n = sc.nameOf(text);
            return openTick(n, Name.overflowed(n, text) ? MISUSE : 0);
        }

        /**
         * A nested trace, a self-contained block: a span at the top of this lane, with {@code t} as its handle,
         * which renders its subtree, and {@code t} the lane's scope until it closes. In a folded task, which
         * has no row to hang it from, the task is unfolded first ({@link #unfold}); once the trace's unfolds
         * are spent the task stays folded and the block's row hangs, linked, from the span the task was
         * submitted from (a lane root if it has none), so the block's own report is still complete. At the
         * row cap the block is a frame.
         * Its CPU time is measured from its start when its scope measures CPU. Null at a cap.
         */
        Span nest(Trace t, Name n, int flags) {
            int d = depth;
            if (d == MAX_DEPTH) {
                dropped++;
                return null;
            }
            boolean hang = rowAt[d - 1] == NO_ROW && !unfold() && rowCount < MAX_ROWS; // a row, the task still folded
            long under = hang ? submitter() : -1;
            boolean frame = rowAt[d - 1] == NO_ROW && !hang;
            if (frame) t.row = FRAME; // before it is a scope: its entries hash by it (Aggs#scopeKey)
            enterScope(t, d);
            if (frame) {
                Span h = openFrame(n, entry(n, Aggs.SPANS), 0, 0, t);
                if (t.measuresCpu()) sampleCpu(d);
                h.time = ctx.now();
                return h;
            }
            Span h = stack(n, flags, t);
            if (h == NOOP) {
                leaveScopes(d);
                return null;
            }
            if (under >= 0) append(h.row, XPARENT, under);
            if (t.measuresCpu()) sampleCpu(d);
            start(h);
            return h;
        }

        /**
         * The folded task at the root of this lane, which has no row, becomes a drawn task: a row past the
         * budget, with its start, queue wait, flags and CPU time so far, linked to the span it was submitted
         * from, so a block opening in it has a row to hang from and the trace shows where it ran. Its
         * occurrence leaves its aggregate, which keeps it as begun. The folded spans open above it stay
         * folded; what opens from now on hangs from its row. A trace unfolds at most {@link
         * Context#unfoldCap} tasks, {@code max(8, budget / 8)}, so the overrun stays bounded however many
         * folded tasks open blocks; past that, and at the row cap, the task stays folded. Returns whether
         * it was unfolded.
         */
        private boolean unfold() {
            Span h = handles[0];
            Aggs a = aggs;
            if (h.row != FRAME || h.agg < 0 || a.keys[h.agg * Aggs.KEY + Aggs.KIND] != Aggs.RAN) return false;
            int k = h.agg * Aggs.KEY, target = a.keys[k + Aggs.ANCHOR], from = a.keys[k + Aggs.LANE];
            if (rowCount == MAX_ROWS || !ctx.unfold()) return false;
            int r = row(h.frame, TASK | h.flags, NO_ROW);
            if (r < 0) return false;
            Rows c = rows[r >>> CHUNK_BITS];
            c.start[r & MASK] = h.time;
            DUR.setOpaque(c.dur, r & MASK, OPEN);
            Lane sub = ctx.lane(from);
            int anchor = sub == null ? -1 : sub.taskAnchor(target);
            if (anchor >= 0) append(r, XPARENT, (long) from << 32 | anchor);
            append(r, QWAIT, h.wait);
            h.row = r;
            h.agg = -1;
            h.folded = false;
            for (int x = 0; x < depth && rowAt[x] == NO_ROW; x++) rowAt[x] = r;
            publish();
            return true;
        }

        /**
         * The span the folded task at the root of this lane was submitted from, as {@code laneId << 32 | row},
         * or -1 if it has none (submitted in a folded task) or is not a folded task.
         */
        private long submitter() {
            Span h = handles[0];
            Aggs a = aggs;
            if (h.row != FRAME || h.agg < 0 || a.keys[h.agg * Aggs.KEY + Aggs.KIND] != Aggs.RAN) return -1;
            int k = h.agg * Aggs.KEY, from = a.keys[k + Aggs.LANE];
            Lane sub = from < 0 ? null : ctx.lane(from);
            int anchor = sub == null ? -1 : sub.taskAnchor(a.keys[k + Aggs.ANCHOR]);
            return anchor < 0 ? -1 : (long) from << 32 | anchor;
        }

        /** Closes everything above depth {@code d}, healed, so a span can open on top of {@code d}: misuse. */
        void healAbove(int d) {
            long now = ctx.now();
            int top = depth;
            boolean healed = unwind(d, now);
            publish();
            if (ctx.listening) fire(d + 1, top);
            if (scopeAt > d) leaveScopes(d + 1);
            if (thrown != null) rethrow();
            if (healed) scope.misuse("span opened under a span that is not the innermost");
        }

        /** A frame of the free lane: no row and no aggregate, timed for its name's global listeners. */
        Span freeFrame(Name n) {
            if (depth == MAX_DEPTH) return NOOP;
            Span h = openFrame(n, -1, 0, 0, null);
            h.time = ctx.now(); // read last: the bookkeeping is not timed
            return h;
        }

        /**
         * A step the trace does not draw: a frame timed into the lane's undrawn entry of its name, which
         * {@link Trace#total} reads and no render shows. Spans opened inside it are recorded under its
         * nearest recorded ancestor.
         */
        private Span undrawn(Name n) {
            if (depth == MAX_DEPTH) {
                dropped++;
                return NOOP;
            }
            Span h = openFrame(n, aggregate(-1, 0, -1, n.id, Aggs.HIDDEN), 0, 0, null);
            h.hidden = true;
            h.time = ctx.now(); // read last: the bookkeeping is not timed
            return h;
        }

        /** A child span; inside a folded task, which has no row to hang it from, folded. */
        Span push(Name n, int flags) {
            if (rowAt[depth - 1] == NO_ROW) return frame(n);
            Span h = stack(n, flags);
            if (h != NOOP) start(h);
            return h;
        }

        /**
         * A drawn step or tick: a row while the lane holds budget, refilled a block at a time from the
         * trace; once the trace's budget is spent, folded into the lane's aggregates.
         */
        Span budgeted(Name n, int flags) {
            if (rowAt[depth - 1] == NO_ROW) return frame(n);
            if (credit == 0 && (credit = scope.credits()) == 0) {
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
         * Opens a folded span above the open one: no row, its start kept in the handle; it ends into the
         * aggregate keyed by its anchor (the nearest recorded span), its parent (-1 when that is the anchor,
         * else the name of the enclosing folded span) and its name.
         */
        Span frame(Name n) {
            if (depth == MAX_DEPTH) {
                dropped++;
                return NOOP;
            }
            Span h = openFrame(n, entry(n, Aggs.SPANS), 0, 0, null);
            h.time = ctx.now(); // read last: the bookkeeping is not timed
            return h;
        }

        /**
         * Pushes frame {@code n} into aggregate {@code agg} (-1: none) with handle {@code t}, or the depth's
         * frame handle if null; it started at {@code now}, after a queue wait of {@code wait}.
         */
        private Span openFrame(Name n, int agg, long wait, long now, Trace t) {
            int d = depth;
            int p = rowAt[d] = d == 0 ? NO_ROW : rowAt[d - 1]; // NO_ROW: a folded task's root, no row below
            if (p == CROSS) cross(d, crossLane[d - 1], crossRow[d - 1]);
            depth = d + 1;
            timed &= ~(1L << d);
            Span h = t != null ? t : frameHandle(d);
            handles[d] = h;
            if (t == null) h.scope = scope;
            h.row = FRAME;
            h.frame = n;
            h.agg = agg;
            h.folded = h.hidden = false;
            h.flags = 0;
            h.wait = wait;
            h.time = now;
            return h;
        }

        /** Spans opened above depth {@code d} hang from row {@code r} of lane {@code l}, linked; see {@link #CROSS}. */
        private void cross(int d, Lane l, int r) {
            if (crossLane == null) {
                crossLane = new Lane[MAX_DEPTH];
                crossRow = new int[MAX_DEPTH];
            }
            rowAt[d] = CROSS;
            crossLane[d] = l;
            crossRow[d] = r;
        }

        /**
         * The aggregate entry for {@code n} folded on top of the stack: anchored at the nearest recorded span
         * (-1 in a folded task without one), parented by the enclosing folded span, if any (an undrawn step
         * is passed over, as it is not drawn). Phases do not split it: they are sections, not parents.
         */
        private int entry(Name n, int kind) {
            int d = depth, a = d - 1;
            while (a >= 0 && handles[a].row == FRAME) a--;
            int p = d - 1;
            while (p > a && handles[p].hidden) p--;
            return aggregate(rowAt[d - 1], 0, p == a ? -1 : handles[p].frame.id, n.id, kind);
        }

        /** {@link #aggregate(int, int, int, int, int, Trace)} in the lane's scope. */
        private int aggregate(int anchor, int lane, int parent, int name, int kind) {
            return aggregate(anchor, lane, parent, name, kind, scope);
        }

        /**
         * The entry of an aggregate key in scope {@code sc}, created if new. Entries of the same key in other
         * scopes stay apart, so the totals of a block read its own; without blocks every entry is in the
         * trace's own scope. A block's entries take at most {@link Aggs#SCOPED} of the table; past that its
         * occurrence goes to the entry of the key in the nearest enclosing scope that has one, ultimately the
         * trace's own, which may fill the rest: the trace's totals stay exact, the block's undercount, and the
         * occurrence counts as unattributed. -1 past {@link Aggs#MAX} entries (counted as a drop).
         */
        private int aggregate(int anchor, int lane, int parent, int name, int kind, Trace sc) {
            Aggs a = aggs;
            if (a == null) {
                a = kit.aggs;
                if (a == null) a = new Aggs(8);
                else a.reset();
                kit.aggs = null;
                AGGS.setRelease(this, a);
            }
            int e = a.find(anchor, lane, parent, name, kind, sc);
            if (e >= 0) return e;
            if (a.size() < (sc.up == null ? Aggs.MAX : Aggs.SCOPED)) return create(anchor, lane, parent, name, kind, sc);
            for (Trace s = sc.up; s != null; s = s.up) {
                if ((e = a.find(anchor, lane, parent, name, kind, s)) < 0 && s.up == null && a.size() < Aggs.MAX) {
                    e = create(anchor, lane, parent, name, kind, s);
                }
                if (e >= 0) {
                    unattributed++;
                    return e;
                }
            }
            dropped++;
            return -1;
        }

        /** A new entry, the table grown first if it is full. */
        private int create(int anchor, int lane, int parent, int name, int kind, Trace sc) {
            Aggs a = aggs;
            if (a.full()) AGGS.setRelease(this, a = a.grow());
            return a.create(anchor, lane, parent, name, kind, sc);
        }

        int aggregates() {
            Aggs a = aggs;
            return a == null ? 0 : a.size();
        }

        /** Tests: the aggregates, or null before the first. */
        Aggs table() {
            return aggs;
        }

        /**
         * Elapsed time of frame handle {@code h}, which holds its start in {@code time} and, once it ended,
         * its duration in {@code wait}; best effort off the owner.
         */
        long frameElapsed(Span h) {
            if (ctx.released || retired()) return 0;
            return open(h) ? Math.max(0, ctx.now() - h.time) : h.wait;
        }

        /** Start of frame handle {@code h} while it is open; 0 once it ended. Best effort off the owner. */
        long frameStart(Span h) {
            if (ctx.released || retired()) return 0;
            return open(h) ? h.time : 0;
        }

        private boolean open(Span h) {
            int d = h.depth;
            return d < depth && handles[d] == h;
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
            if ((cpuMask >>> d & 1) == 0) startCpu(d, ctx.cpuNow());
        }

        /** Measures the CPU time of the span at depth {@code d} from {@code t}, a reading of it; -1 is none. */
        void startCpu(int d, long t) {
            if (t < 0) return;
            if (cpuAt == null) cpuAt = new long[MAX_DEPTH];
            cpuAt[d] = t;
            cpuMask |= 1L << d;
        }

        /** Opens a row on top of the stack and hands out the depth's handle; the caller sets the start and publishes. */
        private Span stack(Name n, int flags) {
            return stack(n, flags, null);
        }

        /** {@link #stack(Name, int)} with {@code t} as the handle; null for the depth's own. */
        private Span stack(Name n, int flags, Trace t) {
            int d = depth;
            if (d == MAX_DEPTH) {
                dropped++;
                return NOOP;
            }
            int p = d == 0 ? NO_ROW : rowAt[d - 1];
            int r = row(n, flags, Math.max(NO_ROW, p));
            if (r < 0) return NOOP;
            if (p == CROSS) append(r, XPARENT, (long) crossLane[d - 1].id << 32 | crossRow[d - 1]); // under where its task was submitted
            DUR.setOpaque(rows[r >>> CHUNK_BITS].dur, r & MASK, OPEN);
            rowAt[d] = r;
            depth = d + 1;
            timed &= ~(1L << d);
            Span h = t != null ? t : issue(d);
            handles[d] = h;
            h.row = r;
            return h;
        }

        /**
         * A handle of its own for a recorded span opening at depth {@code d}: a pooled one of this thread, or
         * new. Strict mode never reuses a handle, so a stale one always stays stale.
         */
        private Span issue(int d) {
            if (scope.strict) return new Span(this, ctx, d);
            Span h = slot.take();
            if (h == null) h = new Span(this, ctx, d);
            else h.bind(this, d);
            if (issued == null) issuedTail = h;
            h.link = issued;
            issued = h;
            return h;
        }

        /**
         * The handle the folded and undrawn spans at depth {@code d} share; the free lane's are its own.
         * Strict mode gives each frame a handle of its own, so a use after close is always caught.
         */
        private Span frameHandle(int d) {
            if (scope.strict) return new Span(this, ctx, d);
            Span[] f = frames;
            if (f == null) frames = f = new Span[MAX_DEPTH];
            Span h = f[d];
            if (h == null) f[d] = h = free ? new Span(this, ctx, d) : issue(d);
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
            Span h = handles[d];
            if (h instanceof Trace && ((Trace) h).printer != null && h.row != FRAME) {
                Throwable t = print(((Trace) h).printer, h.row);
                if (t != null && ((Trace) h).strict && thrown == null) thrown = t;
            }
            Trace sc = scope;
            if (scopeAt >= d) {
                sc = scopeOf(d);
                leaveScopes(d);
            }
            if (d == 0 && !free) exitRoot();
            if (thrown != null) rethrow();
            if (healed) sc.misuse("span closed while a child span was open");
        }

        /** Ends every row above depth {@code d}; returns whether one of them was healed. */
        private boolean unwind(int d, long now) {
            boolean healed = false;
            for (int k = depth - 1; k > d; k--) healed |= end(k, now, true);
            depth = d + 1;
            return healed;
        }

        /**
         * Ends the row at depth {@code k} at {@code now}, or where {@link #stop} put its end: its open phase
         * first, then flags and events, duration last (the commit protocol).
         */
        private boolean end(int k, long now, boolean heal) {
            Span h = handles[k];
            if ((timed >>> k & 1) != 0) {
                if ((phaseAt[k << 2 | MODE] & STOPPED) != 0) {
                    now = times[k << 2 | STOP];
                    // a child open at the stop or opened after it (misuse, counted then) ends inside the span
                    long last = h.row == FRAME ? Long.MIN_VALUE : lastChild(h.row);
                    if (last > now) {
                        now = last;
                        flagRow(k);
                    }
                }
                if (phaseAt[k << 2 | ROW] != NO_PHASE) endPhase(k, now, 0);
            }
            if (h.row == FRAME) {
                long start = h.time, dur = Math.max(0, now - start), cpu = cpuSince(k);
                if (h.agg >= 0) aggs.add(h.agg, start - h.wait, dur, h.wait, cpu, h.flags, h.folded);
                h.wait = dur; // the queue wait is spent: from now on the duration
                return heal;
            }
            int r = h.row;
            Rows c = rows[r >>> CHUNK_BITS];
            int i = r & MASK;
            if (heal) c.flags[i] |= HEALED;
            long cpu = cpuSince(k);
            if (cpu >= 0) append(r, CPU, cpu);
            long dur = now - c.start[i];
            DUR.setOpaque(c.dur, i, dur < 0 ? 0L : dur);
            return heal;
        }

        /**
         * Calls the listeners of the spans at depths [d, top) that just ended, innermost first. They run
         * inside the operation that ended the spans, so they must not open or close spans on this thread.
         */
        private void fire(int d, int top) {
            Trace sc = scope;
            int at = scopeAt;
            for (int k = top - 1; k >= d; k--) {
                for (; k < at; at = outerAt[at]) sc = outer[at];
                Span h = handles[k];
                if (h.row == FRAME) notify(sc, h.frame, h.wait);
                else notify(sc, Name.byId(nameOf(h.row)), rows[h.row >>> CHUNK_BITS].dur[h.row & MASK]);
            }
        }

        /** Calls the listeners of an occurrence of {@code n} in scope {@code sc}; see {@link Context#notify}. */
        private void notify(Trace sc, Name n, long nanos) {
            Throwable t = ctx.notify(sc, n, nanos);
            if (t != null && sc.strict && thrown == null) thrown = t;
        }

        /** Passes the {@code Runtime} text of the trace at {@code row} to {@code out}; returns what it threw, counted as misuse. */
        Throwable print(Consumer<String> out, int row) {
            try {
                out.accept(tops(row).runtime(-1));
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

        // ---- phases and hand timing (owner thread, the span at depth d is open) ----
        //
        // A phase is a section of its span's timeline, a row under the span that is never on the stack: the
        // span's children stay its children and may cross phase boundaries. The boundaries of a span only
        // move forward: its start, then each phase start and end, lap and named stretch, up to its stop.

        /** Sets up the phase state of the span at depth {@code d}, unless it has one already. */
        private void timing(int d) {
            if ((timed >>> d & 1) != 0) return;
            if (times == null) {
                times = new long[4 * MAX_DEPTH];
                phaseAt = new int[4 * MAX_DEPTH];
            }
            times[d << 2 | MARK] = startAt(d);
            phaseAt[d << 2 | BOUNDS] = 0;
            phaseAt[d << 2 | ROW] = NO_PHASE;
            phaseAt[d << 2 | MODE] = 0;
            timed |= 1L << d;
        }

        /** The start of the open span at depth {@code d}: its row's, or a frame's, kept in its handle while open. */
        private long startAt(int d) {
            Span h = handles[d];
            return h.row == FRAME ? h.time : rows[h.row >>> CHUNK_BITS].start[h.row & MASK];
        }

        /** Whether the span at depth {@code d} was stopped; misuse if so, for a call that must come before. */
        private boolean stoppedAlready(int d, String call) {
            if ((phaseAt[d << 2 | MODE] & STOPPED) == 0) return false;
            scopeOf(d).misuse(call + " after stop");
            return true;
        }

        /** A boundary of the span at depth {@code d} at {@code at}. */
        private void boundary(int d, long at) {
            if (phaseAt[d << 2 | BOUNDS]++ == 0) times[d << 2 | FIRST] = at;
            times[d << 2 | MARK] = at;
        }

        /**
         * Starts phase {@code n} of the span at depth {@code d}, ending the open one at the same instant: now,
         * or for a span timed by hand with none open, its last boundary.
         */
        Phase phase(int d, Name n, int flags) {
            timing(d);
            if (stoppedAlready(d, "phase()")) return Phase.NOOP;
            boolean open = phaseAt[d << 2 | ROW] != NO_PHASE;
            long at = open || (phaseAt[d << 2 | MODE] & MANUAL) == 0 ? ctx.now() : times[d << 2 | MARK];
            if (open) endPhase(d, at, 0);
            Phase p = startPhase(d, n, flags, at);
            publish();
            if (thrown != null) rethrow();
            return p;
        }

        private Phase startPhase(int d, Name n, int flags, long at) {
            boundary(d, at);
            phaseAt[d << 2 | NAME] = n.id;
            times[d << 2 | START] = at;
            Span h = handles[d];
            int r = h.row == FRAME ? -1 : row(n, flags | PHASE, h.row);
            if (r < 0) {
                phaseAt[d << 2 | ROW] = ROWLESS;
                return rowless(d);
            }
            Rows c = rows[r >>> CHUNK_BITS];
            c.start[r & MASK] = at;
            DUR.setOpaque(c.dur, r & MASK, OPEN);
            phaseAt[d << 2 | ROW] = r;
            return issuePhase(d, r);
        }

        /**
         * Ends the open phase of the span at depth {@code d} at {@code at}, {@code flags} added to its row.
         * Its duration goes to the totals and listeners of its name: on its row, or for a phase of a folded
         * or undrawn span into the lane's undrawn entry of the name. A phase past the row cap counts nowhere,
         * like a span there.
         */
        private void endPhase(int d, long at, int flags) {
            int r = phaseAt[d << 2 | ROW];
            long dur = Math.max(0, at - times[d << 2 | START]);
            phaseAt[d << 2 | ROW] = NO_PHASE;
            boundary(d, at);
            if (r >= 0) {
                Rows c = rows[r >>> CHUNK_BITS];
                c.flags[r & MASK] |= flags;
                DUR.setOpaque(c.dur, r & MASK, dur);
            } else if (handles[d].row == FRAME) {
                hidden(d, phaseAt[d << 2 | NAME], times[d << 2 | START], dur);
            } else {
                return;
            }
            if (ctx.listening) notify(scopeOf(d), Name.byId(phaseAt[d << 2 | NAME]), dur);
        }

        /**
         * {@link Span#add} of name {@code id} in block {@code sc} of {@code ctx}, on the calling thread. On the
         * thread's current lane, while that lane records inside the block (the block's own lane while it is
         * open, or the lane of a task of it), it goes to the lane's undrawn entry of the name in the block's
         * scope, like an undrawn step: no allocation once the entry exists, and the block's totals and those
         * of the scopes enclosing it read it. Elsewhere, e.g. after the block closed, it goes to the
         * lane-less totals of the block and of every scope enclosing it.
         */
        static void add(Context ctx, Trace sc, int id, long nanos) {
            Lane l = SLOT.get().current;
            if (l != null && l.ctx == ctx && Trace.within(l.scope, sc)) {
                int e = l.aggregate(-1, 0, -1, id, Aggs.HIDDEN, sc);
                if (e >= 0) l.aggs.add(e, 0, nanos, 0, -1, 0, false);
            } else {
                sc.addTotal(id, false, nanos);
            }
        }

        /**
         * An occurrence of name {@code id} that no render shows, for the totals: a phase of the folded or
         * undrawn span at depth {@code d}, in that span's scope.
         */
        private void hidden(int d, int id, long start, long dur) {
            if (free) return;
            int e = aggregate(-1, 0, -1, id, Aggs.HIDDEN, scopeOf(d));
            if (e >= 0) aggs.add(e, start, dur, 0, -1, 0, false);
        }

        /**
         * Ends the open phase of the span at depth {@code d}; {@code n} null for none named. A name must be the
         * open phase's, else that phase ends anyway under its own name, flagged and counted as misuse. With
         * no phase open, a name goes to the time since the last boundary, a phase named after the fact.
         */
        void done(int d, Name n, int flags) {
            if (n == null && (timed >>> d & 1) == 0) return; // nothing open, nothing to name
            timing(d);
            if (stoppedAlready(d, "done()")) return;
            long now = ctx.now();
            if (phaseAt[d << 2 | ROW] != NO_PHASE) {
                boolean mismatch = n != null && (flags & MISUSE) == 0 && n.id != phaseAt[d << 2 | NAME];
                if (mismatch) scopeOf(d).misuse("done(" + n + ") while phase " + Name.byId(phaseAt[d << 2 | NAME]) + " is open");
                endPhase(d, now, mismatch ? MISUSE : 0);
            } else if (n != null) {
                long from = times[d << 2 | MARK], dur = Math.max(0, now - from);
                if (phaseAt[d << 2 | BOUNDS]++ == 0) times[d << 2 | FIRST] = from;
                boundary(d, now);
                Span h = handles[d];
                int r = h.row == FRAME ? -1 : row(n, flags | PHASE | RETRO, h.row);
                if (r >= 0) closed(r, from, dur);
                else if (h.row == FRAME) hidden(d, n.id, from, dur);
                if ((r >= 0 || h.row == FRAME) && ctx.listening) notify(scopeOf(d), n, dur);
            }
            publish();
            if (thrown != null) rethrow();
        }

        /** Ends the open phase of the span at depth {@code d} at clock reading {@code t}: a boundary there. */
        void lap(int d, long t) {
            timing(d);
            if (stoppedAlready(d, "lap(nanos)")) return;
            long now = ctx.now(), want = relative(t), at = clamp(want, times[d << 2 | MARK], now);
            phaseAt[d << 2 | MODE] |= MANUAL;
            boolean clamped = at != want;
            if (phaseAt[d << 2 | ROW] != NO_PHASE) endPhase(d, at, clamped ? MISUSE : 0);
            else boundary(d, at);
            if (clamped) flagRow(d);
            publish();
            if (thrown != null) rethrow();
            if (clamped) scopeOf(d).misuse("lap(nanos) before the last boundary or after now");
        }

        /**
         * Moves the start of the span at depth {@code d} to clock reading {@code t}, within its parent's window
         * (from the parent's start to now), before its first boundary and its children; while that boundary
         * is the start of its open first phase, the phase moves with it. The trace's root (its zero point)
         * and a task (where its queue wait ends) keep their start.
         */
        void start(int d, long t) {
            timing(d);
            if (stoppedAlready(d, "start(nanos)")) return;
            Span h = handles[d];
            long now = ctx.now(), want = relative(t), lo, hi = now;
            boolean task = h.row != FRAME ? (rows[h.row >>> CHUNK_BITS].flags[h.row & MASK] & TASK) != 0
                    : h.agg >= 0 && aggs.keys[h.agg * Aggs.KEY + Aggs.KIND] == Aggs.RAN;
            if (task || d == 0 && !free) lo = hi = startAt(d);
            else lo = d > 0 ? startAt(d - 1) : Long.MIN_VALUE;
            int b = phaseAt[d << 2 | BOUNDS];
            boolean fresh = b == 0 || b == 1 && phaseAt[d << 2 | ROW] != NO_PHASE;
            if (!fresh) hi = Math.min(hi, times[d << 2 | FIRST]);
            if (h.row != FRAME) hi = Math.min(hi, firstChild(d, h.row));
            long at = lo <= hi ? clamp(want, lo, hi) : startAt(d); // no room (a folded parent moved by hand): it stays
            if (h.row == FRAME) h.time = at;
            else rows[h.row >>> CHUNK_BITS].start[h.row & MASK] = at;
            if (b == 0) {
                times[d << 2 | MARK] = at;
            } else if (fresh) {
                times[d << 2 | START] = times[d << 2 | FIRST] = times[d << 2 | MARK] = at;
                int r = phaseAt[d << 2 | ROW];
                if (r >= 0) rows[r >>> CHUNK_BITS].start[r & MASK] = at;
            }
            phaseAt[d << 2 | MODE] |= MANUAL;
            boolean clamped = at != want;
            if (clamped) flagRow(d);
            publish();
            if (clamped) scopeOf(d).misuse("start(nanos) outside the parent's time window");
        }

        /**
         * Ends the span at depth {@code d} at clock reading {@code t}, its open phase with it; it stays on the
         * stack until it closes. Within its last boundary, the end of its last child, and now. A child still
         * open ends no earlier than now: the stop is now, and misuse.
         */
        void stop(int d, long t) {
            timing(d);
            if (stoppedAlready(d, "stop(nanos)")) return;
            int r = handles[d].row;
            boolean open = depth - 1 > d;
            long now = ctx.now(), want = relative(t), lo = open ? now : times[d << 2 | MARK];
            if (r != FRAME && !open) lo = Math.max(lo, lastChild(r));
            long at = clamp(want, lo, now);
            boolean clamped = open || at != want;
            if (phaseAt[d << 2 | ROW] != NO_PHASE) endPhase(d, at, clamped ? MISUSE : 0);
            else boundary(d, at);
            times[d << 2 | STOP] = at;
            phaseAt[d << 2 | MODE] |= STOPPED;
            phaseAt[d << 2 | MODE] |= MANUAL;
            if (clamped) flagRow(d);
            publish();
            if (thrown != null) rethrow();
            if (open) scopeOf(d).misuse("stop(nanos) while a child span is open");
            else if (clamped) scopeOf(d).misuse("stop(nanos) before the last boundary or a child's end, or after now");
        }

        /**
         * The latest end of what ended under row {@code r}: its child rows (not its phases, nor the records
         * of tasks submitted from it, which run elsewhere) and the folded spans anchored at it. {@link
         * Long#MIN_VALUE} for none.
         */
        private long lastChild(int r) {
            long max = Long.MIN_VALUE;
            for (int x = r + 1; x < rowCount; x++) {
                Rows c = rows[x >>> CHUNK_BITS];
                int i = x & MASK;
                long dur = c.dur[i];
                if (c.parent[i] == r && (c.flags[i] & (PHASE | HANDOFF)) == 0 && dur != OPEN) max = Math.max(max, c.start[i] + dur);
            }
            Aggs a = aggs;
            for (int e = 0, n = a == null ? 0 : a.size(); e < n; e++) {
                int k = e * Aggs.KEY, b = e * Aggs.CELLS;
                if (a.keys[k] == r && a.keys[k + Aggs.KIND] == Aggs.SPANS && a.cells[b + Aggs.COUNT] > 0) {
                    max = Math.max(max, a.cells[b + Aggs.LAST]);
                }
            }
            return max;
        }

        /** Whether the innermost open span was stopped by hand ({@link #stop}): a child opened now is misuse. */
        private boolean underStop() {
            int d = depth - 1;
            return (timed >>> d & 1) != 0 && (phaseAt[d << 2 | MODE] & STOPPED) != 0;
        }

        /** Counts a child opened under a stopped span (strict mode throws first); its row is flagged. */
        private int afterStop() {
            scope.misuse("span opened under a span stopped by hand");
            return MISUSE;
        }

        /**
         * The earliest start of what is drawn under row {@code r}, open at depth {@code d}, its phases aside:
         * child rows, which come after it, folded spans open above it, and the aggregates anchored at it
         * (a folded handoff by its submit).
         */
        private long firstChild(int d, int r) {
            long min = Long.MAX_VALUE;
            for (int x = r + 1; x < rowCount; x++) {
                Rows c = rows[x >>> CHUNK_BITS];
                int i = x & MASK;
                if (c.parent[i] == r && (c.flags[i] & PHASE) == 0) min = Math.min(min, c.start[i]);
            }
            for (int k = d + 1; k < depth; k++) if (handles[k].row == FRAME) min = Math.min(min, handles[k].time);
            Aggs a = aggs;
            for (int e = 0, n = a == null ? 0 : a.size(); e < n; e++) {
                int k = e * Aggs.KEY, b = e * Aggs.CELLS;
                if (a.keys[k] != r || a.keys[k + Aggs.KIND] > Aggs.TASKS) continue;
                min = Math.min(min, Math.min(a.cells[b + Aggs.FIRST], a.cells[b + Aggs.SUBMIT_FIRST]));
            }
            return min;
        }

        /** A clock reading as a time of this lane's trace; past the range of a long, the nearest end of it. */
        private long relative(long t) {
            long e = ctx.epoch, r = t - e;
            return ((t ^ e) & (t ^ r)) < 0 ? t < e ? Long.MIN_VALUE : Long.MAX_VALUE : r;
        }

        private static long clamp(long t, long lo, long hi) {
            return t < lo ? lo : t > hi ? Math.max(lo, hi) : t;
        }

        /** Flags the row of the span at depth {@code d} as misused, if it has one. */
        private void flagRow(int d) {
            int r = handles[d].row;
            if (r != FRAME) rows[r >>> CHUNK_BITS].flags[r & MASK] |= MISUSE;
        }

        /** A handle of its own for phase row {@code r} of the span at depth {@code d}: pooled, or new in strict mode. */
        private Phase issuePhase(int d, int r) {
            boolean strict = scopeOf(d).strict;
            Phase p = strict ? null : slot.takePhase();
            if (p == null) p = new Phase();
            p.bind(this, handles[d], d, r);
            if (!strict) {
                if (issuedPhases == null) issuedPhasesTail = p;
                p.link = issuedPhases;
                issuedPhases = p;
            }
            return p;
        }

        /**
         * The handle the rowless phases at depth {@code d} share, bound to the span open there; in strict
         * mode one of its own.
         */
        private Phase rowless(int d) {
            if (scopeOf(d).strict) {
                Phase p = new Phase();
                p.bind(this, handles[d], d, FRAME);
                return p;
            }
            Phase[] f = rowless;
            if (f == null) rowless = f = new Phase[MAX_DEPTH];
            Phase p = f[d];
            if (p == null) {
                if (free) p = new Phase();
                else if ((p = slot.takePhase()) == null) p = new Phase();
                if (!free) {
                    if (issuedPhases == null) issuedPhasesTail = p;
                    p.link = issuedPhases;
                    issuedPhases = p;
                }
                f[d] = p;
            }
            p.bind(this, handles[d], d, FRAME);
            return p;
        }

        int nameOf(int r) {
            return rows[r >>> CHUNK_BITS].name[r & MASK];
        }

        /** {@link #nameOf} from any thread: -1 once the lane is retired and its chunks are gone. */
        int nameAt(int r) {
            Rows c = rows[r >>> CHUNK_BITS];
            return c == null ? -1 : c.name[r & MASK];
        }

        /** Labels phase row {@code r}, also after the lane went idle; false once the lane is retired. */
        boolean label(int r, String text) {
            if (!enter()) return false;
            rows[r >>> CHUNK_BITS].flags[r & MASK] |= LABELED;
            if (eventCount < MAX_EVENTS) {
                String[] t = labels;
                if (t == null) LABELS.setRelease(this, t = new String[8]);
                else if (labelCount == t.length) LABELS.setRelease(this, t = Arrays.copyOf(t, 2 * labelCount));
                t[labelCount] = text;
                append(r, LABEL, labelCount++);
            } else {
                dropped++;
            }
            publish();
            leave();
            return true;
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

        /** Flags row {@code r}, also after the lane went idle; false once the lane is retired. */
        boolean flag(int r, int bits) {
            if (!enter()) return false;
            rows[r >>> CHUNK_BITS].flags[r & MASK] |= bits;
            publish();
            leave();
            return true;
        }

        /** Attaches an event to row {@code r}, also after the lane went idle; false once the lane is retired. */
        boolean event(int r, int key, long value) {
            if (!enter()) return false;
            append(r, key, value);
            publish();
            leave();
            return true;
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
                scope.refund(credit);
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
         * picked one up discards what it read, because it finds the trace released (see Snapshot). The
         * span and phase handles go back to the owner's slot, each detached first (see Span#detach), so a
         * use of one is stale from now until the owner issues it again. Last the lane's arrays go back to
         * the slot as its kit, for the owner's next lane; a reader of this lane may then see them in use
         * there, which it discards in the same way.
         */
        private void retire() {
            int s = state;
            if ((s & KIND) != IDLE || !STATE.compareAndSet(this, s, s | RETIRED)) return;
            Arrays.fill(handles, null);
            if (frames != null) Arrays.fill(frames, null);
            if (rowless != null) Arrays.fill(rowless, null);
            Span h = issued;
            if (h != null) {
                for (Span x = h; x != null; x = x.link) x.detach();
                slot.give(h, issuedTail);
                issued = issuedTail = null;
            }
            Phase p = issuedPhases;
            if (p != null) {
                for (Phase x = p; x != null; x = x.link) x.detach();
                slot.givePhases(p, issuedPhasesTail);
                issuedPhases = issuedPhasesTail = null;
            }
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
            Aggs a = aggs;
            aggs = null;
            waiting = null;
            if (crossLane != null) Arrays.fill(crossLane, null);
            labels = null;
            if (slot == null || free) return;
            Kit k = kit;
            k.events = events;
            k.frames = frames;
            k.rowless = rowless;
            k.times = times;
            k.cpuAt = cpuAt;
            k.phaseAt = phaseAt;
            k.outer = outer;
            k.outerAt = outerAt;
            k.crossLane = crossLane;
            k.crossRow = crossRow;
            if (a != null && a.keys.length <= Kit.KEEP_AGGS * Aggs.KEY) {
                Arrays.fill(a.scopes, null); // a pooled table reaches no trace
                k.aggs = a;
            }
            slot.giveKit(k);
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

        /** Name id of row {@code r}, or -1 once released or past the commit. Safe from any thread. */
        int nameIn(int r) {
            if (ctx.released || retired()) return -1;
            long c = committed();
            if (r < 0 || r >= (int) (c >>> 32)) return -1;
            Rows k = rows[r >>> CHUNK_BITS];
            if (k == null) return -1;
            int id = k.name[r & MASK];
            VarHandle.acquireFence();
            return ctx.released ? -1 : id;
        }

        /** Start of row {@code r} from the trace's start, or 0 once released or past the commit. Safe from any thread. */
        long startIn(int r) {
            if (ctx.released || retired()) return 0;
            long c = committed();
            if (r < 0 || r >= (int) (c >>> 32)) return 0;
            Rows k = rows[r >>> CHUNK_BITS];
            if (k == null) return 0;
            long start = k.start[r & MASK];
            VarHandle.acquireFence();
            return ctx.released ? 0 : start;
        }

        /**
         * The labelled phases of row {@code root} and its wall time, read in place with one {@code now} for
         * whatever is open, their starts from the root's; empty once released. Safe from any thread. The
         * boundaries of a span only move forward, so its phase rows are in start order.
         */
        Tops tops(int root) {
            if (ctx.released || retired()) return Tops.EMPTY;
            try {
                long c = committed();
                int n = (int) (c >>> 32), nEvents = (int) c;
                long now = ctx.now(), base = 0; // the root's start: 0 for a trace's, a nested trace's own
                Tops t = new Tops();
                for (int r = root; r < n; r++) {
                    Rows k = rows[r >>> CHUNK_BITS];
                    int i = r & MASK;
                    if (r != root && (k.parent[i] != root || (k.flags[i] & LABELED) == 0)) continue;
                    long d = (long) DUR.getOpaque(k.dur, i), s = k.start[i];
                    d = d != OPEN ? d : Math.max(0, now - s);
                    if (r == root) {
                        t.total = d;
                        base = s;
                    } else {
                        t.add(r, k.name[i], null, s - base, d);
                    }
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
     * The aggregates of one lane, keyed by (anchor row, lane, parent, name, kind) where the parent is -1 for
     * the anchor itself or the name of an enclosing folded span, and the lane 0 but for RAN and UNRUN.
     * {@code SPANS} hold folded spans, {@code HIDDEN} the undrawn steps and the phases of folded or undrawn
     * spans of a name, which only the totals read (anchor -1, parent -1).
     * A handoff past the budget is a {@code TASKS} entry where it was submitted,
     * which counts the submits; its tasks are occurrences in the lane that runs ({@code RAN}) or cancels
     * ({@code UNRUN}) them, in an entry keyed by the {@code TASKS} entry instead: ANCHOR is its index, LANE
     * its lane. So every entry has one writer, the owner, and a render merges the occurrences into their
     * {@code TASKS} entry. The owner appends entries and updates them in place; a reader takes the size
     * with acquire and reads the entries below it, exact once the lane is idle and best effort while it records.
     * Each entry also belongs to a scope ({@link Lane#scope}), the innermost self-contained block it was
     * recorded in or the trace's own root: entries of one key in two scopes stay apart, so a block's totals
     * read its own. Without blocks every entry is in the trace's own scope.
     */
    static final class Aggs {
        // MAX entries in all; the entries of blocks (scopes other than the trace's root) take at most SCOPED,
        // so the trace's own entries, where a block's occurrences go past it, always have room.
        static final int MAX = 4096, SCOPED = MAX / 4 * 3, KEY = 5, ANCHOR = 0, LANE = 1, PARENT = 2, NAME = 3, KIND = 4;
        static final int SPANS = 0, TASKS = 1, RAN = 2, UNRUN = 3, HIDDEN = 4;
        // Over the ended occurrences; a handoff's starts at its submit and ends at submit + wait + duration.
        static final int CELLS = 15, COUNT = 0, SUM = 1, MIN = 2, MAX_DUR = 3, FIRST = 4, LAST = 5, MAX_AT = 6, CPU = 7,
                FOLDED = 8,     // occurrences folded for want of budget; the rest were in a folded task
                WAIT_SUM = 9, WAIT_MAX = 10,
                FLAGS = 11,     // the flags every occurrence has
                SUBMITS = 12, SUBMIT_SUM = 13, SUBMIT_FIRST = 14; // handoffs submitted (TASKS), begun (RAN) or cancelled (UNRUN)
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
        final Trace[] scopes;      // the scope of each entry
        private final int[] index; // entry + 1 by key hash, 0 free; owner only, at most half full
        @SuppressWarnings("unused") private int size; // via SIZE

        Aggs(int capacity) {
            keys = new int[capacity * KEY];
            cells = new long[capacity * CELLS];
            scopes = new Trace[capacity];
            index = new int[capacity * 2];
        }

        /** Entries published so far; any thread. */
        int size() {
            return (int) SIZE.getAcquire(this);
        }

        /** Owner of a new lane: empties a pooled table for reuse. */
        void reset() {
            SIZE.setRelease(this, 0);
            Arrays.fill(index, 0);
        }

        boolean full() {
            return size == keys.length / KEY;
        }

        int find(int anchor, int lane, int parent, int name, int kind, Trace scope) {
            int mask = index.length - 1;
            for (int i = hash(anchor, lane, parent, name, kind, scope) & mask; ; i = (i + 1) & mask) {
                int e = index[i] - 1, k = e * KEY;
                if (e < 0) return -1;
                if (keys[k] == anchor && keys[k + LANE] == lane && keys[k + PARENT] == parent && keys[k + NAME] == name
                        && keys[k + KIND] == kind && scopes[e] == scope) return e;
            }
        }

        int create(int anchor, int lane, int parent, int name, int kind, Trace scope) {
            int e = size, k = e * KEY;
            keys[k] = anchor;
            keys[k + LANE] = lane;
            keys[k + PARENT] = parent;
            keys[k + NAME] = name;
            keys[k + KIND] = kind;
            scopes[e] = scope;
            int b = e * CELLS;
            Arrays.fill(cells, b, b + CELLS, 0L);
            cells[b + MIN] = cells[b + FIRST] = cells[b + SUBMIT_FIRST] = Long.MAX_VALUE;
            cells[b + MAX_DUR] = cells[b + LAST] = Long.MIN_VALUE;
            cells[b + CPU] = -1;
            index(e);
            SIZE.setRelease(this, e + 1);
            return e;
        }

        private void index(int e) {
            int k = e * KEY, mask = index.length - 1;
            int i = hash(keys[k], keys[k + LANE], keys[k + PARENT], keys[k + NAME], keys[k + KIND], scopes[e]) & mask;
            while (index[i] != 0) i = (i + 1) & mask;
            index[i] = e + 1;
        }

        /** A copy with twice the room; the owner publishes it in place of this one. */
        Aggs grow() {
            Aggs a = new Aggs(2 * keys.length / KEY);
            System.arraycopy(keys, 0, a.keys, 0, keys.length);
            System.arraycopy(cells, 0, a.cells, 0, cells.length);
            System.arraycopy(scopes, 0, a.scopes, 0, scopes.length);
            for (int e = 0; e < size; e++) a.index(e);
            a.size = size;
            return a;
        }

        /** One occurrence: from {@code start} (a handoff's submit), it waited {@code wait} and ran {@code dur}. */
        void add(int e, long start, long dur, long wait, long cpu, int flags, boolean folded) {
            long[] c = cells;
            int b = e * CELLS;
            c[b + FLAGS] = c[b + COUNT] == 0 ? flags : c[b + FLAGS] & flags;
            c[b + SUM] += dur;
            if (dur < c[b + MIN]) c[b + MIN] = dur;
            if (dur > c[b + MAX_DUR]) {
                c[b + MAX_DUR] = dur;
                c[b + MAX_AT] = start;
            }
            if (start < c[b + FIRST]) c[b + FIRST] = start;
            if (start + wait + dur > c[b + LAST]) c[b + LAST] = start + wait + dur;
            if (cpu >= 0) c[b + CPU] = c[b + CPU] < 0 ? cpu : c[b + CPU] + cpu;
            if (folded) c[b + FOLDED]++;
            c[b + WAIT_SUM] += wait;
            if (wait > c[b + WAIT_MAX]) c[b + WAIT_MAX] = wait;
            c[b + COUNT]++;
        }

        /** A handoff submitted (TASKS), begun (RAN) or cancelled (UNRUN) at {@code submit}. */
        void submit(int e, long submit) {
            long[] c = cells;
            int b = e * CELLS;
            c[b + SUBMITS]++;
            c[b + SUBMIT_SUM] += submit;
            if (submit < c[b + SUBMIT_FIRST]) c[b + SUBMIT_FIRST] = submit;
        }

        /** Adds the occurrences of the entry at {@code a} in {@code q} to the one at {@code b} in {@code c}. */
        static void combine(long[] c, int b, long[] q, int a) {
            if (q[a + COUNT] == 0) return;
            c[b + FLAGS] = c[b + COUNT] == 0 ? q[a + FLAGS] : c[b + FLAGS] & q[a + FLAGS];
            c[b + COUNT] += q[a + COUNT];
            c[b + SUM] += q[a + SUM];
            c[b + MIN] = Math.min(c[b + MIN], q[a + MIN]);
            if (q[a + MAX_DUR] > c[b + MAX_DUR] || q[a + MAX_DUR] == c[b + MAX_DUR] && q[a + MAX_AT] < c[b + MAX_AT]) {
                c[b + MAX_DUR] = q[a + MAX_DUR];
                c[b + MAX_AT] = q[a + MAX_AT];
            }
            c[b + FIRST] = Math.min(c[b + FIRST], q[a + FIRST]);
            c[b + LAST] = Math.max(c[b + LAST], q[a + LAST]);
            if (q[a + CPU] >= 0) c[b + CPU] = c[b + CPU] < 0 ? q[a + CPU] : c[b + CPU] + q[a + CPU];
            c[b + FOLDED] += q[a + FOLDED];
            c[b + WAIT_SUM] += q[a + WAIT_SUM];
            c[b + WAIT_MAX] = Math.max(c[b + WAIT_MAX], q[a + WAIT_MAX]);
        }

        /**
         * The hash of a key with its scope, so the entries of one name in many blocks (a block called many
         * times, each with an undrawn step or task) spread over the index instead of sharing one probe chain:
         * a find costs the same however many blocks the lane has seen.
         */
        private static int hash(int anchor, int lane, int parent, int name, int kind, Trace scope) {
            int h = ((((anchor * 31 + lane) * 31 + parent) * 31 + name) * 31 + kind) * 31 + scopeKey(scope);
            h *= 0x9E3779B9;
            return h ^ (h >>> 16);
        }

        /**
         * A stable number for a scope: the row of its span, unique in its lane and set before it is a scope
         * anywhere (the trace's root is row 0); a block that is a frame, which has no row, its identity hash.
         */
        static int scopeKey(Trace s) {
            int r = s.row;
            return r != Lane.FRAME ? r : System.identityHashCode(s);
        }

        /** Tests: the index slots a find of the key probes, the one that answers included. */
        int probes(int anchor, int lane, int parent, int name, int kind, Trace scope) {
            int mask = index.length - 1, n = 1;
            for (int i = hash(anchor, lane, parent, name, kind, scope) & mask; ; i = (i + 1) & mask, n++) {
                int e = index[i] - 1, k = e * KEY;
                if (e < 0 || keys[k] == anchor && keys[k + LANE] == lane && keys[k + PARENT] == parent
                        && keys[k + NAME] == name && keys[k + KIND] == kind && scopes[e] == scope) return n;
            }
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
        String[] label;             // phase labels by row; null in a lane without
        long[] link, wait;          // XPARENT and QWAIT by row; null in a lane without tasks
        long[] cpu;                 // CPU by row, -1 where not measured; null in a lane without
        static final int NONE = -1, MIXED = -2;
        final int[] aggKey;         // Aggs.KEY ints per aggregate entry of the lane
        final long[] aggCell;       // Aggs.CELLS longs per entry; a TASKS entry's with its occurrences merged
        int[] aggThread;            // by TASKS entry: the lane that ran its occurrences, NONE or MIXED; null if none ran
        final long[] waitingSince;  // by TASKS entry: the earliest submit of a task not begun nor cancelled; null if none
        int[] bodyLane, bodyRow;    // by handoff row: the task row linked to it, or -1
        boolean[] hidden;           // not in its parent's child list: a task drawn at its handoff, or a lost handoff
        boolean[] unfolded;         // a task row linked to the span it was submitted from (Lane#unfold): drawn as a handoff
        boolean[] aggIn;            // of a block's snapshot: the aggregate entries in the block's scope or one nested in it
        // Child lists over the nodes: rows, a virtual parent of the lane roots, aggregates. Entries are nodes
        // of any lane, numbered across the snapshot from each lane's base (Snapshot#nodeOf), -1 for none: a
        // span in an undrawn task begun on another thread is a child of the span the task was submitted from.
        int base;
        int[] first, next;

        /** Null for a lane that is retired or whose trace is released. */
        static Shape of(Lane l) {
            return of(l, l.state(), null);
        }

        /** {@link #of(Lane)} for the snapshot of self-contained block {@code block}, null for a whole trace. */
        static Shape of(Lane l, Trace block) {
            return of(l, l.state(), block);
        }

        /** The copy given the state read before it; separate so a test can write in between. */
        static Shape of(Lane l, int before) {
            return of(l, before, null);
        }

        private static Shape of(Lane l, int before, Trace block) {
            if ((before & Lane.KIND) == Lane.RETIRED || l.ctx.released) return null;
            Shape s = new Shape(l, l.committed(), block);
            VarHandle.acquireFence(); // the copy's reads stay before the second read of the state
            s.live = (before & Lane.KIND) != Lane.IDLE || l.state() != before;
            return s;
        }

        private Shape(Lane l, long commit, Trace block) {
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
            aggs = a == null ? 0 : a.size(); // all of them, so a RAN or UNRUN entry finds its TASKS entry by index
            aggKey = new int[aggs * Aggs.KEY];
            aggCell = new long[aggs * Aggs.CELLS];
            if (aggs > 0) {
                System.arraycopy(a.keys, 0, aggKey, 0, aggKey.length);
                System.arraycopy(a.cells, 0, aggCell, 0, aggCell.length);
                if (block != null) {
                    aggIn = new boolean[aggs];
                    for (int e = 0; e < aggs; e++) aggIn[e] = Trace.within(a.scopes[e], block);
                }
            }
            waitingSince = aggs > 0 ? l.waitingSince(aggs) : null;
        }

        /**
         * Whether aggregate {@code e} is a node of the tree: a SPANS or TASKS entry with an occurrence, not
         * (in a live copy) anchored at a row this copy does not have. RAN and UNRUN entries are merged.
         */
        boolean shown(int e) {
            int k = e * Aggs.KEY;
            return aggKey[k + Aggs.KIND] <= Aggs.TASKS && aggCell[e * Aggs.CELLS + Aggs.COUNT] > 0 && aggKey[k] < rows;
        }

        /** The lane that ran the occurrences of aggregate {@code e}: its own for spans, else NONE or MIXED. */
        int threadOf(int e) {
            return aggKey[e * Aggs.KEY + Aggs.KIND] != Aggs.TASKS ? lane : aggThread == null ? NONE : aggThread[e];
        }

        /** Takes a handoff submitted at {@code submit} whose task has no record out of TASKS entry {@code t}. */
        void unsubmit(int t, long submit) {
            aggCell[t * Aggs.CELLS + Aggs.SUBMITS]--;
            aggCell[t * Aggs.CELLS + Aggs.SUBMIT_SUM] -= submit;
        }

        /** Adds the occurrences of RAN or UNRUN entry {@code e} of {@code from} to TASKS entry {@code t} of this. */
        void merge(int t, Shape from, int e) {
            int b = t * Aggs.CELLS, a = e * Aggs.CELLS;
            long[] c = aggCell, q = from.aggCell;
            c[b + Aggs.SUBMITS] -= q[a + Aggs.SUBMITS]; // what is left never started
            c[b + Aggs.SUBMIT_SUM] -= q[a + Aggs.SUBMIT_SUM];
            if (q[a + Aggs.COUNT] == 0) return; // begun, still open
            Aggs.combine(c, b, q, a);
            if (from.aggKey[e * Aggs.KEY + Aggs.KIND] != Aggs.RAN) return;
            if (aggThread == null) Arrays.fill(aggThread = new int[aggs], NONE);
            aggThread[t] = aggThread[t] == NONE || aggThread[t] == from.lane ? from.lane : MIXED;
        }

        /**
         * Adds the handoffs of each TASKS entry that never started: no duration, waiting from their submit
         * until {@code now}. Their count and submit sum are what the merge left of the entry's; the first of
         * them comes from the lane's waiting list. A render racing a begin can find the two
         * apart; the longest wait is then kept within the wait sum, and the first submit falls back to the
         * entry's.
         */
        void settle(long now) {
            for (int t = 0; t < aggs; t++) {
                int b = t * Aggs.CELLS;
                long[] c = aggCell;
                long n = c[b + Aggs.SUBMITS];
                if (aggKey[t * Aggs.KEY + Aggs.KIND] != Aggs.TASKS || n <= 0) continue;
                long first = waitingSince == null || waitingSince[t] == Long.MAX_VALUE ? c[b + Aggs.SUBMIT_FIRST] : waitingSince[t];
                long waits = n * now - c[b + Aggs.SUBMIT_SUM];
                c[b + Aggs.FLAGS] = c[b + Aggs.COUNT] == 0 ? UNRUN | NEVER : c[b + Aggs.FLAGS] & (UNRUN | NEVER);
                c[b + Aggs.COUNT] += n;
                c[b + Aggs.FOLDED] += n;
                c[b + Aggs.MIN] = 0;
                if (c[b + Aggs.MAX_DUR] < 0) {
                    c[b + Aggs.MAX_DUR] = 0;
                    c[b + Aggs.MAX_AT] = first;
                }
                c[b + Aggs.FIRST] = Math.min(c[b + Aggs.FIRST], first);
                c[b + Aggs.LAST] = Math.max(c[b + Aggs.LAST], now);
                c[b + Aggs.WAIT_SUM] += waits;
                c[b + Aggs.WAIT_MAX] = Math.max(c[b + Aggs.WAIT_MAX], Math.min(now - first, waits));
            }
        }

        /**
         * Start of a row (of an inline handoff, its task's; of an unfolded task, its submit), or the first start
         * of an aggregate node.
         */
        long startOf(int x) {
            if (x >= rows) return aggCell[(x - rows - 1) * Aggs.CELLS + Aggs.FIRST];
            return unfolded(x) ? start[x] - wait[x] : start[inline(x) ? x + 1 : x];
        }

        /** Whether row {@code r} is a task unfolded for a block, drawn as the handoff record of its submit. */
        boolean unfolded(int r) {
            return unfolded != null && r < rows && unfolded[r];
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
         * Child lists in start order. Rows are mostly added in that order; one that starts before siblings
         * added earlier (a phase named after the fact, a span started by hand) goes before them, a phase
         * named after the fact also before those that started with it. Aggregates join their parent's list
         * in start order.
         */
        int nodes() {
            return rows + 1 + aggs;
        }

        /** The child lists of this lane's own nodes; then they are numbered from {@code base}. */
        void tree() {
            int top = rows, nodes = nodes();
            int[] last = new int[rows + 1], prev = new int[rows];
            first = new int[nodes];
            next = new int[nodes];
            Arrays.fill(first, -1);
            Arrays.fill(next, -1);
            Arrays.fill(last, -1);
            for (int r = 0; r < rows; r++) {
                if (hidden != null && hidden[r]) continue;
                int p = parent[r] < 0 ? top : parent[r], a = last[p];
                long s = startOf(r);
                boolean retro = (flags[r] & RETRO) != 0;
                while (a >= 0 && (startOf(a) > s || retro && startOf(a) == s)) a = prev[a];
                int b = a < 0 ? first[p] : next[a]; // r goes between a and b
                prev[r] = a;
                next[r] = b;
                if (a < 0) first[p] = r;
                else next[a] = r;
                if (b < 0) last[p] = r;
                else prev[b] = r;
            }
            for (int e = 0; e < aggs; e++) if (shown(e)) attach(rows + 1 + e, aggregateParent(e));
            for (int x = 0; x < nodes; x++) {
                if (first[x] >= 0) first[x] += base;
                if (next[x] >= 0) next[x] += base;
            }
        }

        /**
         * The anchor row, or for an aggregate nested in another folded span the earlier aggregate of that
         * name under the same anchor (created when that span opened, so before this one). Without an
         * anchor (folded in a folded task) it is a top-level node of the lane.
         */
        private int aggregateParent(int e) {
            int k = e * Aggs.KEY, outer = aggKey[k + Aggs.PARENT];
            for (int f = 0; outer >= 0 && f < e; f++) {
                int j = f * Aggs.KEY;
                if (aggKey[j + Aggs.NAME] == outer && aggKey[j] == aggKey[k] && shown(f)) {
                    return rows + 1 + f;
                }
            }
            return aggKey[k] < 0 ? rows : aggKey[k];
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
     * totals as they were when {@link Trace#snapshot()} took it; of a nested trace, the copy renders
     * its subtree, with times from its start. It references no lane, so it stays
     * complete after {@link Trace#release()}, and any thread may read it. Taken after release it is empty.
     *
     * <p>A handoff row is drawn as one record with the task linked to it (submit time, queue wait, the
     * task's duration, thread and children); a handoff without a task is drawn never started.
     *
     * <p>A response object that carries the trace in its body ({@link #writeJson}, or {@code TempoJackson}
     * for Jackson) holds a {@code Snapshot}, never a {@link Trace}: a body is often serialized after the
     * request released its trace, or while its tasks still record, and would then find it empty or
     * changing. Take the snapshot when the response is built.
     */
    public static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot();

        final Shape[] shapes; // by lane id
        private int[] bases;  // by lane id: the number of the lane's first node (Shape#base)
        private final Totals totals;
        private final long now;
        final long wall;
        final int headerBytes;
        private final String linkBase;
        private final int misuses, dropped, folded;
        // The record rendered as the root: lane 0, row 0 for a trace; a nested trace's row, whose subtree
        // alone is rendered, its start (base) the zero point of every time and its thread the first.
        final int rootLane, rootRow;
        final long base;
        final boolean subtree;
        private boolean[][] rowIn; // of a block's snapshot: by lane id and row, the rows in its subtree

        private Snapshot() {
            shapes = new Shape[0];
            totals = null;
            now = wall = base = 0;
            misuses = dropped = folded = headerBytes = rootLane = rootRow = 0;
            linkBase = null;
            subtree = false;
        }

        /**
         * The trace now, or {@link #EMPTY} once it is released. A copy racing {@link Trace#release()} may
         * read chunks that went back to the pool; it is thrown away, also when what it read made it fail.
         */
        static Snapshot of(Context ctx) {
            return of(ctx, 0, 0, null);
        }

        /**
         * The subtree of {@code row} in lane {@code lane}, the row of self-contained block {@code block}, with
         * the block's totals and output settings; with {@code block} null the whole trace.
         */
        static Snapshot of(Context ctx, int lane, int row, Trace block) {
            if (ctx.released || ctx.id < 0) return EMPTY;
            boolean subtree = block != null;
            try {
                Snapshot s = new Snapshot(ctx, lane, row, block);
                VarHandle.acquireFence();
                return ctx.released || subtree && (lane >= s.shapes.length || s.shapes[lane] == null) ? EMPTY : s;
            } catch (RuntimeException e) {
                VarHandle.acquireFence();
                if (ctx.released) return EMPTY;
                throw e;
            }
        }

        private Snapshot(Context ctx, int lane, int row, Trace block) {
            now = ctx.now();
            Trace own = block != null ? block : ctx.root;
            headerBytes = own.headerBytes;
            linkBase = own.linkBase;
            Lane[] lanes = ctx.lanesById();
            shapes = new Shape[lanes.length];
            for (int i = 0; i < lanes.length; i++) if (lanes[i] != null) shapes[i] = Shape.of(lanes[i], block);
            boolean subtree = this.subtree = block != null;
            rootLane = lane;
            rootRow = row;
            Shape top = lane < shapes.length ? shapes[lane] : null;
            base = subtree && top != null && row < top.rows ? top.start[row] : 0;
            wall = ctx.wall > 0 ? ctx.wall + base / 1_000_000 : ctx.wall;
            totals = own.frozenTotals();
            misuses = ctx.misuses.get();
            dropped = subtree ? 0 : ctx.dropped(); // what a lane drops is not known by subtree
            int lostFolded = 0;
            long[] lost = ctx.lost();
            for (int i = 0; i < lost.length; i += 2) {
                Shape s = shape(lost[i]);
                int r = (int) lost[i];
                if (r < 0) lostFolded++; // counted as dropped, not folded
                if (s == null) continue;
                if (r >= 0 && r < s.rows) s.hide(r);
                else if (r < 0 && ~r < s.aggs) s.unsubmit(~r, lost[i + 1]);
            }
            folded = subtree ? 0 : ctx.folded() - lostFolded; // a subtree counts the folded it shows
            // A task whose handoff row is not visible (a live render racing the submit) is left out.
            for (Shape s : shapes) {
                if (s == null || s.link == null) continue;
                for (int r = 0; r < s.rows; r++) {
                    long x = s.link[r];
                    if (x < 0) continue;
                    s.hide(r);
                    Shape t = shape(x);
                    int h = (int) x;
                    if (t != null && h < t.rows && (t.flags[h] & HANDOFF) != 0) {
                        t.link(h, s.lane, r);
                    } else if ((s.flags[r] & TASK) != 0 && s.wait != null) { // unfolded (Lane#unfold): a handoff from that span
                        if (s.unfolded == null) s.unfolded = new boolean[s.rows];
                        s.unfolded[r] = true;
                    }
                }
            }
            // The tasks of folded handoffs, kept where they ran or were cancelled, join their TASKS entry.
            for (Shape s : shapes) {
                for (int e = 0; s != null && e < s.aggs; e++) {
                    int k = e * Aggs.KEY, target = s.aggKey[k];
                    int kind = s.aggKey[k + Aggs.KIND];
                    if (kind != Aggs.RAN && kind != Aggs.UNRUN || s.aggKey[k + Aggs.LANE] < 0) continue; // undrawn: totals only
                    Shape t = shape((long) s.aggKey[k + Aggs.LANE] << 32);
                    if (t != null && target < t.aggs && t.aggKey[target * Aggs.KEY + Aggs.KIND] == Aggs.TASKS) t.merge(target, s, e);
                }
            }
            bases = new int[shapes.length];
            for (int i = 0, n = 0; i < shapes.length; i++) {
                Shape s = shapes[i];
                bases[i] = n;
                if (s == null) continue;
                s.base = n;
                n += s.nodes();
                s.settle(now);
                s.tree();
            }
            // A span opened in an undrawn task on another thread, or a folded task unfolded for a block (Lane#unfold),
            // joins the children of the span the task was submitted from; left out while that span is not visible.
            for (Shape s : shapes) {
                if (s == null || s.link == null) continue;
                for (int r = 0; r < s.rows; r++) {
                    long x = s.link[r];
                    if (x < 0) continue; // a task's run linked to its handoff row joins it there, not here
                    Shape t = shape(x);
                    int p = (int) x;
                    if (t != null && p < t.rows && (t.flags[p] & (HANDOFF | PHASE)) == 0) adopt(t, p, s.base + r);
                }
            }
            if (subtree) { // the rows a block's totals read, as Context#total(int, Trace) reads them in place
                int[] rows = new int[shapes.length];
                int[][] parent = new int[shapes.length][];
                long[][] link = new long[shapes.length][];
                for (int i = 0; i < shapes.length; i++) {
                    Shape s = shapes[i];
                    rows[i] = s == null ? 0 : s.rows;
                    parent[i] = s == null ? new int[0] : s.parent;
                    link[i] = s == null ? null : s.link;
                }
                rowIn = Context.subtree(lane, row, rows, parent, link);
            }
        }

        /** Puts node {@code x} into the child list of row {@code p} of {@code t}, in start order. */
        private void adopt(Shape t, int p, int x) {
            long s = startOf(x);
            int a = -1;
            for (int c = t.first[p]; c >= 0 && startOf(c) <= s; c = nextOf(c)) a = c;
            Shape sx = shapeOf(x);
            if (a < 0) {
                sx.next[x - sx.base] = t.first[p];
                t.first[p] = x;
            } else {
                Shape sa = shapeOf(a);
                sx.next[x - sx.base] = sa.next[a - sa.base];
                sa.next[a - sa.base] = x;
            }
        }

        /** The lane of node {@code x}: the last with a base at or below it (lanes not read have no nodes). */
        private int laneOf(int x) {
            int lo = 0, hi = bases.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (bases[mid] <= x) lo = mid;
                else hi = mid - 1;
            }
            return lo;
        }

        private Shape shapeOf(int x) {
            return shapes[laneOf(x)];
        }

        private long startOf(int x) {
            Shape s = shapeOf(x);
            return s.startOf(x - s.base);
        }

        private int nextOf(int x) {
            Shape s = shapeOf(x);
            return s.next[x - s.base];
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
         * lane id, are the top level, with the aggregates of spans folded in a folded task begun outside
         * the trace, which have no recorded span to hang from. Spans open when the snapshot was taken are
         * clamped to that instant. Aggregates ({@link SpanView#aggregate()}) come among their parent's
         * children in start order; those of folded handoffs gather the tasks from every thread.
         */
        public void forEachSpan(SpanVisitor visitor) {
            SpanView v = new SpanView();
            int limit = 0; // a record per node at most, whatever the links say
            for (Shape s : shapes) if (s != null) limit += s.rows + s.aggs;
            int[] lane = new int[16], row = new int[16], id = new int[16]; // the path to the current record
            int n = 0;
            for (Shape top : shapes) {
                if (top == null || subtree && top.lane != rootLane) continue;
                for (int g = subtree ? top.base + rootRow : top.first[top.rows]; g >= 0 && n < limit; g = subtree ? -1 : nextOf(g)) {
                    int d = 0;
                    lane[0] = laneOf(g);
                    row[0] = g - shapes[lane[0]].base;
                    id[0] = n;
                    emit(v, shapes[lane[0]], row[0], -1, 0, n++, visitor);
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
                                c = n < limit ? shapes[lane[d]].next[row[d]] : -1;
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
                        lane[d] = laneOf(c);
                        row[d] = c - shapes[lane[d]].base;
                        id[d] = n;
                        emit(v, shapes[lane[d]], row[d], id[d - 1], d, n++, visitor);
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
            v.inline = v.mixed = false;
            v.row = r > s.rows ? -1 : r;
            if (r > s.rows) {
                int e = r - s.rows - 1, k = e * Aggs.KEY, b = e * Aggs.CELLS, t = s.threadOf(e), f;
                long[] c = s.aggCell;
                v.name = Name.byId(s.aggKey[k + Aggs.NAME]);
                v.start = c[b + Aggs.FIRST];
                v.duration = c[b + Aggs.SUM];
                v.cpu = c[b + Aggs.CPU];
                v.open = false;
                v.handoff = s.aggKey[k + Aggs.KIND] == Aggs.TASKS;
                v.flags = (f = (int) c[b + Aggs.FLAGS]) & ~NEVER;
                v.begun = v.handoff && (f & NEVER) == 0;
                if (t >= 0) v.shape = shapes[t];
                v.mixed = t == Shape.MIXED;
                v.wait = c[b + Aggs.WAIT_SUM];
                v.waitMax = c[b + Aggs.WAIT_MAX];
                v.aggregate = true;
                v.count = c[b + Aggs.COUNT];
                v.folded = c[b + Aggs.FOLDED];
                v.min = c[b + Aggs.MIN];
                v.max = c[b + Aggs.MAX_DUR];
                v.extent = c[b + Aggs.LAST] - v.start;
                v.maxAt = c[b + Aggs.MAX_AT] - v.start;
                v.start -= base;
                visitor.visit(v);
                return;
            }
            int flags = s.flags[r];
            boolean handoff = (flags & HANDOFF) != 0, begun = handoff && s.begun(r), unfolded = s.unfolded(r);
            Shape b = begun ? shapes[s.bodyLane[r]] : s;
            int br = begun ? s.bodyRow[r] : r;
            long dur = b.dur[br], waited = unfolded ? s.wait[r] : 0; // an unfolded task: the record of its submit
            v.shape = b;
            v.live = b.live;
            v.name = Name.byId(b.name[br]);
            v.start = s.start[r] - waited;
            v.open = dur == Lane.OPEN;
            v.duration = v.open ? Math.max(0, now - b.start[br]) : dur;
            v.flags = begun ? flags | b.flags[br] : flags;
            v.handoff = handoff || unfolded;
            v.begun = begun || unfolded;
            v.inline = begun && s.inline(r);
            v.wait = v.waitMax = begun ? (b.wait == null ? 0 : b.wait[br]) : unfolded ? waited : handoff ? Math.max(0, now - v.start) : 0;
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
            v.start -= base;
            visitor.visit(v);
        }

        /**
         * Like {@link Trace#total(Name)}, as the snapshot was taken: of the whole trace, or of a nested one's
         * subtree, as that trace's total reads it.
         */
        public Total total(Name name) {
            if (name == null) return Total.EMPTY;
            int id = name.id;
            long count = 0, sum = 0, min = Long.MAX_VALUE, max = 0, waits = 0, waitSum = 0, waitMax = 0;
            for (Shape s : shapes) {
                if (s == null) continue;
                boolean[] in = rowIn == null ? null : rowIn[s.lane];
                for (int r = 0; r < s.rows; r++) {
                    int f = s.flags[r];
                    if (s.name[r] != id || (f & HANDOFF) != 0 || in != null && !in[r]) continue;
                    boolean task = (f & TASK) != 0;
                    if (task) {
                        long w = s.wait == null ? 0 : s.wait[r];
                        waits++;
                        waitSum += w;
                        waitMax = Math.max(waitMax, w);
                    }
                    long d = s.dur[r];
                    if (d == Lane.OPEN || (f & UNRUN) != 0) continue;
                    count++;
                    sum += d;
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                }
                for (int e = 0; e < s.aggs; e++) { // as Trace.total: never-started tasks have no wait yet
                    int k = e * Aggs.KEY, b = e * Aggs.CELLS, kind = s.aggKey[k + Aggs.KIND];
                    if (s.aggKey[k + Aggs.NAME] != id || s.aggCell[b + Aggs.COUNT] == 0 || kind == Aggs.TASKS
                            || kind == Aggs.SPANS && s.aggKey[k] >= s.rows || subtree && (s.aggIn == null || !s.aggIn[e])) continue;
                    if (kind != Aggs.UNRUN) {
                        count += s.aggCell[b + Aggs.COUNT];
                        sum += s.aggCell[b + Aggs.SUM];
                        min = Math.min(min, s.aggCell[b + Aggs.MIN]);
                        max = Math.max(max, s.aggCell[b + Aggs.MAX_DUR]);
                    }
                    if (kind == Aggs.RAN || kind == Aggs.UNRUN) {
                        waits += s.aggCell[b + Aggs.COUNT];
                        waitSum += s.aggCell[b + Aggs.WAIT_SUM];
                        waitMax = Math.max(waitMax, s.aggCell[b + Aggs.WAIT_MAX]);
                    }
                }
            }
            return Totals.merge(totals, id, count, sum, min, max, waits, waitSum, waitMax);
        }

        /** {@link #total(Name)} of the name {@code name}; empty for a name never interned. */
        public Total total(String name) {
            return total(Name.find(name));
        }

        /** The shape of the root's lane, or null if it has no root. */
        private Shape top() {
            Shape s = rootLane < shapes.length ? shapes[rootLane] : null;
            return s == null || rootRow >= s.rows ? null : s;
        }

        /** Wall time of the root span: its duration, or while it was open its age when the snapshot was taken. */
        public long elapsedNanos() {
            Shape s = top();
            if (s == null) return 0;
            int r = rootRow;
            return s.dur[r] != Lane.OPEN ? s.dur[r] : Math.max(0, now - s.start[r]);
        }

        /**
         * The {@code Tempo-Timing} header value, as specified in {@code FORMAT.md}, at most
         * {@link Options#headerBytes} long (see {@link Trace#toHeader()}); empty if taken after release.
         */
        public String toHeader() {
            return Header.of(this, headerBytes);
        }

        /** Like {@link Trace#toHeader(int)}: at most {@code maxBytes} long; empty if taken after release. */
        public String toHeader(int maxBytes) {
            return Header.of(this, maxBytes);
        }

        /**
         * The {@code Tempo-Link} header value (see {@link Trace#toLink()}), carrying {@link #toHeader()};
         * empty if taken after release.
         */
        public String toLink() {
            return toLink(null);
        }

        /** Like {@link Trace#toLink(String)}; empty if taken after release. */
        public String toLink(String base) {
            return Link.of(Link.base(base, linkBase), toHeader());
        }

        /** Like {@link Trace#toLink(String, int)}: at most {@code maxBytes} long; empty if taken after release. */
        public String toLink(String base, int maxBytes) {
            return Header.link(this, Link.base(base, linkBase), maxBytes);
        }

        /**
         * Writes the trace in the JSON form of {@code FORMAT.md} section 8 to {@code out}: what the whole
         * {@link #toHeader()} value decodes to, with no size limit and no ladder, as one line of compact
         * JSON (keys in the order of the format's tables, names as the wire decodes them). Needs no
         * library; {@code TempoJackson} writes the same structure through Jackson. Taken after release,
         * or without a root, it has no threads and no spans.
         *
         * <p>Writes into a {@link StringBuilder} directly; any other target receives the text in pieces.
         */
        public void writeJson(Appendable out) throws IOException {
            JsonText.write(this, out);
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
            Shape s = top();
            if (s == null) return Tops.EMPTY;
            Tops t = new Tops();
            t.total = elapsedNanos();
            for (int r = rootRow + 1; r < s.rows; r++) { // a span's phase rows are in start order, see Lane.tops
                if (s.parent[r] != rootRow || (s.flags[r] & LABELED) == 0) continue;
                long d = s.dur[r];
                t.add(r, s.name[r], s.label == null ? null : s.label[r], s.start[r] - base, d != Lane.OPEN ? d : Math.max(0, now - s.start[r]));
            }
            return t;
        }

        int misuses() {
            return misuses;
        }

        int dropped() {
            return dropped;
        }

        /** Steps and ticks folded into aggregates for want of budget, open ones included. */
        int folded() {
            return folded;
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * The root's labelled phases (name, label, start, duration) and the root's wall time, read once for
     * the {@code Runtime} and {@code Server-Timing} renderers.
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
     * second writes the records into the one builder. Records deeper than {@link #MAX_DEPTH} (wire depth
     * adds up along handoff chains), the aggregates of folded spans nested in others (the wire has
     * no children under an aggregate) and the lane-level nodes besides the root (aggregates of a folded
     * task that had no recorded span to hang them from) are left out with their subtrees and counted as
     * dropped, like a non-finite attribute value: a record at depth 35 stands for its whole subtree. Every
     * occurrence in an aggregate on the wire counts as folded (FORMAT.md §4), as do folded spans and tasks
     * still open. A value over its limit goes down the {@link Ladder}. The second walk can instead write
     * the JSON form the whole value decodes to ({@link #json}), with the same rules and numbers.
     */
    static final class Header implements SpanVisitor {
        static final int MAX_DEPTH = 35, MIN_BYTES = 256;
        static final int KEEP = 8192; // a thread keeps its builder up to this capacity (one output String per render)
        static final char[] DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
        private static final char[] HEX = "0123456789ABCDEF".toCharArray();
        private static final int SHOWN = FAILED | CANCELLED | WAIT | HEALED | MISUSE;

        /** Whether a value is short enough: its length, or the length of the link that carries it. */
        interface Fit {
            boolean fits(CharSequence value);
        }

        private final Snapshot snap;
        private final int[] nameIndex; // by name id: wire index + 1; -1 for an attribute key not numbered yet
        private final int[] laneIndex; // by lane id: wire thread index + 1, 0 for a lane no record uses
        private int[] laneOrder;       // lane ids by wire thread index
        private int[] order = new int[16], keys = new int[16]; // name ids by wire index; keys by first use
        private int names, nKeys;
        private final long[] cur = new long[MAX_DEPTH + 2];
        private final int[] threadAt = new int[MAX_DEPTH + 1];
        private int[] idAt; // JSON form only: by depth, the current path's record ids
        private final boolean[] aggregateAt = new boolean[MAX_DEPTH + 1];
        private boolean second; // the second walk, which writes the records to out or json
        private StringBuilder out;
        private JsonSink json;
        private int records, skip, aggregates;
        private long rootDur, left, nonFinite; // left: spans left out, aggregates by count
        private long aggregated, foldedSeen; // occurrences in the aggregates written; folded ones in all aggregates
        private long folded, dropped; // x=
        private long unwritten; // what the fallback stands for: every span but the root, as folded and dropped

        private Header(Snapshot snap) {
            this.snap = snap;
            nameIndex = new int[nameBound(snap)];
            laneIndex = new int[snap.shapes.length];
        }

        /**
         * The value of {@code s}, at most {@code maxBytes} long when {@code maxBytes >= 256}: as it is if it
         * fits, else the first step of the {@link Ladder} that fits (FORMAT.md §7.1); below 256 bytes, or
         * if no step fits, the fallback. Empty without a root.
         */
        static String of(Snapshot s, int maxBytes) {
            StringBuilder b = borrow(maxBytes);
            try {
                return value(s, b, v -> v.length() <= maxBytes, maxBytes >= MIN_BYTES);
            } finally {
                giveBack(b);
            }
        }

        /**
         * The {@code Tempo-Link} to {@code base} carrying the value of {@code s}, at most {@code maxBytes}
         * long when {@code maxBytes - base.length() >= 256}: the ladder applies to the value until the
         * compressed link fits. Empty without a root.
         */
        static String link(Snapshot s, String base, int maxBytes) {
            StringBuilder b = borrow(maxBytes);
            try (Link.Packer p = new Link.Packer()) {
                String v = value(s, b, x -> base.length() + p.pack(x) <= maxBytes, (long) maxBytes - base.length() >= MIN_BYTES);
                if (v.isEmpty()) return "";
                p.pack(v);
                return p.link(base);
            } finally {
                giveBack(b);
            }
        }

        /** The thread's builder, which one render at a time uses; a new one while another render holds it. */
        private static StringBuilder borrow(int maxBytes) {
            ThreadSlot slot = SLOT.get();
            StringBuilder b = slot.text;
            slot.text = null;
            return b != null ? b : new StringBuilder(Math.max(64, Math.min(maxBytes, KEEP / 2)));
        }

        private static void giveBack(StringBuilder b) {
            if (b.capacity() <= KEEP) SLOT.get().text = b;
        }

        /** The whole value if {@code fit} takes it; else, with {@code ladder}, the first step it takes; else the fallback. */
        private static String value(Snapshot s, StringBuilder b, Fit fit, boolean ladder) {
            Header h = new Header(s);
            if (!h.write(b)) return "";
            if (fit.fits(b)) return b.toString();
            if (ladder) {
                String v = new Ladder(b.toString()).run(fit, b);
                if (v != null) return v;
            }
            b.setLength(0);
            b36(b.append("v1;x=0."), h.unwritten);
            b36(b.append(";T=%3F;N=%3F;S=00.0."), us(h.rootDur));
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

        /**
         * The first walk: numbers the threads and names the records use and counts what is folded and
         * dropped; false if the snapshot has no root. The second walk follows with {@code records} reset.
         */
        private boolean prepare() {
            snap.forEachSpan(this);
            if (records == 0) return false;
            for (int i = 0; i < nKeys; i++) if (nameIndex[keys[i]] < 0) number(keys[i]);
            // the root's thread is thread 0; the others follow by lane id
            int n = 0, root = snap.rootLane;
            laneOrder = new int[laneIndex.length];
            if (laneIndex[root] != 0) laneIndex[laneOrder[n] = root] = ++n;
            for (int l = 0; l < laneIndex.length; l++) if (l != root && laneIndex[l] != 0) laneIndex[laneOrder[n] = l] = ++n;
            laneOrder = Arrays.copyOf(laneOrder, n);
            // aggregates left out count as dropped only; spans folded for want of budget that are still
            // open are in no aggregate yet and count as folded
            dropped = snap.dropped() + left + nonFinite;
            folded = aggregated + Math.max(0, snap.folded() - foldedSeen);
            unwritten = folded + dropped + records - 1 - aggregates;
            second = true;
            records = 0;
            skip = -1;
            return true;
        }

        /** Writes the whole value into {@code b}; false if the snapshot has no root. */
        private boolean write(StringBuilder b) {
            if (!prepare()) return false;
            b.setLength(0);
            b.append("v1");
            if (snap.wall > 0) b36(b.append(";w="), snap.wall);
            if (folded > 0 || dropped > 0) b36(b36(b.append(";x="), folded).append('.'), dropped);
            boolean live = false;
            for (int i = 0; i < laneOrder.length; i++) {
                if (!snap.shapes[laneOrder[i]].live) continue;
                b36(b.append(live ? "." : ";l="), i);
                live = true;
            }
            b.append(";T=");
            for (int i = 0; i < laneOrder.length; i++) name(i == 0 ? b : b.append(':'), snap.shapes[laneOrder[i]].thread);
            b.append(";N=");
            for (int i = 0; i < names; i++) name(i == 0 ? b : b.append(':'), Name.byId(order[i]).text);
            b.append(";S=");
            out = b;
            snap.forEachSpan(this);
            b36(b.append(";e="), records);
            return true;
        }

        /**
         * Writes the JSON form of {@code s} (FORMAT.md §8) to {@code j}: what its whole value decodes to,
         * with no size limit and no ladder. Without a root it has no threads and no spans.
         */
        static void json(Snapshot s, JsonSink j) throws IOException {
            Header h = new Header(s);
            boolean any = h.prepare();
            j.begin('{');
            j.key("v");
            j.number(1);
            j.key("unit");
            j.string("us");
            if (any && s.wall > 0) {
                j.key("wall");
                j.number(s.wall);
            }
            j.key("folded");
            j.number(h.folded);
            j.key("dropped");
            j.number(h.dropped);
            j.key("threads");
            j.begin('[');
            for (int i = 0; any && i < h.laneOrder.length; i++) j.string(text(s.shapes[h.laneOrder[i]].thread));
            j.end(']');
            j.key("live");
            j.begin('[');
            for (int i = 0; any && i < h.laneOrder.length; i++) if (s.shapes[h.laneOrder[i]].live) j.number(i);
            j.end(']');
            j.key("truncated");
            j.bool(false);
            j.key("spans");
            j.begin('[');
            if (any) {
                h.json = j;
                h.idAt = new int[MAX_DEPTH + 1];
                try {
                    s.forEachSpan(h);
                } catch (UncheckedIOException e) {
                    throw e.getCause();
                }
            }
            j.end(']');
            j.end('}');
        }

        @Override
        public void visit(SpanView v) {
            int d = v.depth;
            if (skip >= 0 && d > skip || d > MAX_DEPTH || d == 0 && records > 0 || d > 0 && aggregateAt[d - 1]) {
                if (skip < 0 || d <= skip) skip = d; // left out with its subtree
                if (!second) {
                    left += v.count;
                    foldedSeen += v.folded;
                }
                return;
            }
            skip = -1;
            aggregateAt[d] = v.aggregate;
            if (!second) {
                foldedSeen += v.folded;
                if (v.aggregate) {
                    aggregated += v.count;
                    aggregates++;
                }
                count(v);
            } else if (json == null) {
                record(out, v);
            } else {
                try {
                    span(json, v);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            records++;
        }

        /** First walk: numbers the record's thread, name and attribute keys. */
        private void count(SpanView v) {
            if (records == 0) rootDur = v.duration;
            if (!unrun(v) && !v.mixed) laneIndex[v.shape.lane] = 1;
            if (nameIndex[v.name.id] <= 0) number(v.name.id);
            for (int i = 0, n = v.attributes(); i < n; i++) {
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
            long previous = cur[d], start = start(v);
            b36(b36(b36(b.append(DIGITS[d]), nameIndex[v.name.id] - 1).append('.'), start - previous).append('.'), us(v.duration));
            int thread = thread(v);
            if (v.mixed) b.append("@_");
            else if (d > 0 && thread != threadAt[d - 1]) b36(b.append('@'), thread);
            if (waits(v)) {
                b36(b.append('~'), us(v.wait));
                if (v.aggregate) b36(b.append('.'), us(v.waitMax));
            }
            if (v.cpu >= 0) b36(b.append('*'), us(v.cpu));
            if (v.aggregate) {
                b36(b36(b36(b.append('+'), v.count).append('.'), us(v.min)).append('.'), us(v.max));
                b36(b36(b.append('.'), us(v.extent)).append('.'), us(v.maxAt));
            }
            int f = flags(v);
            if (f != 0) b.append('^').append(FLAG_TEXT[f]);
            for (int i = 0, n = v.attributes(); i < n; i++) {
                if (!first(v, i)) continue;
                int j = last(v, i);
                if (!finite(v, j)) continue;
                b36(b.append(':'), nameIndex[v.key(j) >>> KIND_BITS] - 1).append('=');
                if ((v.key(j) & KIND_MASK) == ATTR_DOUBLE) number(b, Double.longBitsToDouble(v.value(j)));
                else b.append(v.value(j));
            }
        }

        /** Second walk, JSON form: the span object of the record {@link #record} would write, as decoded. */
        private void span(JsonSink j, SpanView v) throws IOException {
            long start = start(v);
            int d = v.depth, thread = thread(v);
            idAt[d] = records;
            j.begin('{');
            j.key("id");
            j.number(records);
            j.key("parent");
            j.number(d == 0 ? -1 : idAt[d - 1]); // the view's ids count the records left out too
            j.key("name");
            j.string(text(v.name.text));
            j.key("start");
            j.number(start);
            j.key("dur");
            j.number(us(v.duration));
            j.key("thread");
            j.number(v.mixed ? -1 : thread);
            if (waits(v) && !v.aggregate) {
                j.key("wait");
                j.number(us(v.wait));
            }
            if (v.cpu >= 0) {
                j.key("cpu");
                j.number(us(v.cpu));
            }
            int f = flags(v);
            if (f != 0) {
                j.key("flags");
                j.string(FLAG_TEXT[f]);
            }
            boolean attrs = false;
            for (int i = 0, n = v.attributes(); i < n; i++) {
                if (!first(v, i)) continue;
                int k = last(v, i);
                if (!finite(v, k)) continue;
                if (!attrs) {
                    j.key("attrs");
                    j.begin('{');
                    attrs = true;
                }
                j.key(text(Name.byId(v.key(k) >>> KIND_BITS).text));
                if ((v.key(k) & KIND_MASK) == ATTR_DOUBLE) j.decimal(Double.longBitsToDouble(v.value(k)));
                else j.number(v.value(k));
            }
            if (attrs) j.end('}');
            if (v.aggregate) {
                j.key("agg");
                j.begin('{');
                j.key("count");
                j.number(v.count);
                j.key("min");
                j.number(us(v.min));
                j.key("max");
                j.number(us(v.max));
                j.key("extent");
                j.number(us(v.extent));
                j.key("maxAt");
                j.number(us(v.maxAt));
                if (waits(v)) {
                    j.key("waitSum");
                    j.number(us(v.wait));
                    j.key("waitMax");
                    j.number(us(v.waitMax));
                }
                j.end('}');
            }
            j.end('}');
        }

        /**
         * The record's start in microseconds from the root's, which is the trace's zero point, as a decoder
         * reads it: delta-coded against its previous sibling or its parent, so never before them. An inline
         * handoff is a plain child from when it began (FORMAT.md §6.5).
         */
        private long start(SpanView v) {
            int d = v.depth;
            long start = Math.max(cur[d], us(v.start + (v.inline ? v.wait : 0)));
            cur[d] = cur[d + 1] = start;
            return start;
        }

        /** The record's wire thread index; a mixed-thread aggregate's is its parent's, as the wire omits it. */
        private int thread(SpanView v) {
            int d = v.depth;
            int thread = d == 0 ? 0 : unrun(v) || v.mixed ? threadAt[d - 1] : laneIndex[v.shape.lane] - 1;
            threadAt[d] = thread;
            return thread;
        }

        /** Whether the record carries a queue wait: a handoff not run inline, or an aggregate of handoffs. */
        private static boolean waits(SpanView v) {
            return v.aggregate ? v.handoff : v.handoff && !v.inline;
        }

        /** The record's flags, by bit in {@link #FLAG_TEXT}'s order {@code fconwmp}. */
        private static int flags(SpanView v) {
            int f = v.flags;
            return ((f & FAILED) != 0 ? 1 : 0) | ((f & CANCELLED) != 0 ? 2 : 0) | (v.open ? 4 : 0)
                    | (v.neverStarted() ? 8 : 0) | ((f & WAIT) != 0 ? 16 : 0) | ((f & (HEALED | MISUSE)) != 0 ? 32 : 0)
                    | ((f & PHASE) != 0 ? 64 : 0);
        }

        /** The flag strings by {@link #flags} bits: {@code fconwmp}, in that order. */
        private static final String[] FLAG_TEXT = new String[128];

        static {
            for (int m = 0; m < 128; m++) {
                StringBuilder b = new StringBuilder(7);
                for (int i = 0; i < 7; i++) if ((m & 1 << i) != 0) b.append("fconwmp".charAt(i));
                FLAG_TEXT[m] = b.toString();
            }
        }

        /**
         * A name as a decoder reads it back from {@link #name}: {@code ?} for a null one and for each
         * unpaired surrogate; the string itself when it has none, the empty name included.
         */
        static String text(String s) {
            if (s == null) return "?";
            char[] c = null;
            for (int i = 0, n = s.length(); i < n; i++) {
                char x = s.charAt(i);
                if (!Character.isSurrogate(x)) continue;
                if (Character.isHighSurrogate(x) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                    i++;
                    continue;
                }
                if (c == null) c = s.toCharArray();
                c[i] = '?';
            }
            return c == null ? s : new String(c);
        }

        /**
         * A task cancelled before it began, or an aggregate of such tasks and tasks never started: no thread
         * ran it, so it sits on its parent's (FORMAT.md §6.5).
         */
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
         * as {@code %XX}; an unpaired surrogate as {@code ?}, a null name as {@code %3F}, and the empty name
         * (of an unnamed span) as nothing.
         */
        static void name(StringBuilder b, String s) {
            if (s == null) {
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

    /**
     * The tokens of the JSON form (FORMAT.md §8) in the order {@link Header#json} writes them:
     * {@link JsonText} writes them as text, {@code TempoJackson} through a Jackson {@code JsonGenerator}.
     */
    abstract static class JsonSink {
        /** Opens an object ({@code '{'}) or an array ({@code '['}). */
        abstract void begin(char bracket) throws IOException;

        /** Closes the innermost object ({@code '}'}) or array ({@code ']'}). */
        abstract void end(char bracket) throws IOException;

        abstract void key(String key) throws IOException;

        abstract void string(String s) throws IOException;

        abstract void number(long v) throws IOException;

        /**
         * A finite double attribute value, as the wire writes it ({@link Header#number}): rounded to six
         * decimals, half to even, and an integral result as an integer.
         */
        abstract void decimal(double v) throws IOException;

        abstract void bool(boolean v) throws IOException;
    }

    /**
     * Compact JSON text, one line without spaces, keys in the order of FORMAT.md §8's tables. It goes
     * straight into a {@link StringBuilder} target, else into the thread's builder, which is flushed to
     * the target as it fills.
     */
    static final class JsonText extends JsonSink {
        private static final int FLUSH = Header.KEEP / 2;
        private static final char[] HEX = "0123456789ABCDEF".toCharArray(); // as Jackson writes them

        private final Appendable out;
        private final StringBuilder b;
        private long used; // bit per nesting level: the level has an item, so the next one needs a comma
        private int level;
        private boolean keyed; // a key was just written, so its value needs no comma

        private JsonText(Appendable out) {
            this.out = out;
            b = out instanceof StringBuilder ? (StringBuilder) out : Header.borrow(FLUSH);
        }

        static void write(Snapshot s, Appendable out) throws IOException {
            JsonText j = new JsonText(Objects.requireNonNull(out, "out"));
            if (j.b == out) {
                Header.json(s, j);
                return;
            }
            j.b.setLength(0);
            try {
                Header.json(s, j);
                out.append(j.b);
            } finally {
                j.b.setLength(0);
                Header.giveBack(j.b);
            }
        }

        private void item() {
            if (keyed) keyed = false;
            else if ((used & 1L << level) != 0) b.append(',');
            used |= 1L << level;
        }

        @Override
        void begin(char bracket) {
            item();
            b.append(bracket);
            used &= ~(1L << ++level);
        }

        @Override
        void end(char bracket) throws IOException {
            level--;
            b.append(bracket);
            if (b != out && b.length() >= FLUSH) {
                out.append(b);
                b.setLength(0);
            }
        }

        @Override
        void key(String key) {
            item();
            quote(key);
            b.append(':');
            keyed = true;
        }

        @Override
        void string(String s) {
            item();
            quote(s);
        }

        @Override
        void number(long v) {
            item();
            b.append(v);
        }

        @Override
        void decimal(double v) {
            item();
            Header.number(b, v);
        }

        @Override
        void bool(boolean v) {
            item();
            b.append(v);
        }

        /** A JSON string: {@code "} and {@code \} escaped, control characters as their short or {@code \}{@code u00XX} escape. */
        private void quote(String s) {
            int i = 0, n = s.length();
            for (char c; i < n && (c = s.charAt(i)) >= 0x20 && c != '"' && c != '\\'; ) i++;
            b.append('"').append(s, 0, i); // keys and most names: no escapes at all
            for (; i < n; i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c >= 0x20) b.append(c);
                else if (c == '\n') b.append("\\n");
                else if (c == '\r') b.append("\\r");
                else if (c == '\t') b.append("\\t");
                else if (c == '\b') b.append("\\b");
                else if (c == '\f') b.append("\\f");
                else b.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 15]);
            }
            b.append('"');
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * The byte-budget ladder of FORMAT.md §7.1, for a value over its limit. It reads the value as a decoder
     * does (absolute starts in microseconds, threads and names by their index in the value's tables) and
     * walks the steps, writing the value anew after each change and stopping at the first that fits:
     * <ol>
     * <li>fold each group of same-name siblings, handoffs apart from the rest, into one aggregate,
     *     largest group first; failed records and the slowest one stay single;</li>
     * <li>drop the deepest level, one at a time, while it is 3 or deeper;</li>
     * <li>drop the attributes;</li>
     * <li>drop depth 2: the root and its children remain.</li>
     * </ol>
     * The tables shrink to what the remaining records use. A record left out counts as dropped, an
     * aggregate by its count, which then no longer counts as folded; a record folded counts as folded.
     * It reads only values its own encoder wrote, so it checks nothing.
     */
    static final class Ladder {
        private static final String FLAGS = "fconwmp"; // bit i is FLAGS.charAt(i)
        private static final int F = 1, P = 64; // failed, phase

        private final String value;
        private String wall; // the w= digits, or null
        private final String[] threads, names; // as written in T and N
        private final boolean[] live; // by thread index
        private long folded, dropped;
        private final int n;
        private int pos, attrs;
        private final int[] depth, parent, name, thread, flags, attrFrom, attrTo;
        // wait: -1 for none (an aggregate's wait sum); cpu: -1 for none; count: 0 for a single record
        private final long[] start, dur, wait, waitMax, cpu, count, min, max, extent, maxAt;
        private int[] attrKey = new int[16], valueFrom = new int[16], valueTo = new int[16];
        private final boolean[] removed;
        private boolean attributes = true;
        private final int[] nameNew, nameOrder, threadNew, threadAt = new int[Header.MAX_DEPTH + 1];
        private final long[] cur = new long[Header.MAX_DEPTH + 2];

        Ladder(String value) {
            this.value = value;
            int s = value.indexOf(";S="), e = value.lastIndexOf(";e=");
            String l = null, tTable = null, nTable = null;
            for (int i = 3, j; i < s; i = j + 1) { // the fields after "v1;"
                j = value.indexOf(';', i);
                String f = value.substring(i + 2, j);
                switch (value.charAt(i)) {
                    case 'w': wall = f; break;
                    case 'x':
                        folded = Long.parseLong(f.substring(0, f.indexOf('.')), 36);
                        dropped = Long.parseLong(f.substring(f.indexOf('.') + 1), 36);
                        break;
                    case 'l': l = f; break;
                    case 'T': tTable = f; break;
                    default: nTable = f;
                }
            }
            threads = tTable.split(":", -1); // an empty name is one too
            names = nTable.split(":", -1);
            live = new boolean[threads.length];
            if (l != null) for (String x : l.split("\\.")) live[Integer.parseInt(x, 36)] = true;
            int records = 1;
            for (int i = s + 3; i < e; i++) if (value.charAt(i) == '/') records++;
            n = records;
            depth = new int[n];
            parent = new int[n];
            name = new int[n];
            thread = new int[n];
            flags = new int[n];
            attrFrom = new int[n];
            attrTo = new int[n];
            start = new long[n];
            dur = new long[n];
            wait = new long[n];
            waitMax = new long[n];
            cpu = new long[n];
            count = new long[n];
            min = new long[n];
            max = new long[n];
            extent = new long[n];
            maxAt = new long[n];
            removed = new boolean[n];
            nameNew = new int[names.length];
            nameOrder = new int[names.length];
            threadNew = new int[threads.length];
            int[] path = new int[Header.MAX_DEPTH + 1];
            pos = s + 3;
            for (int i = 0; i < n; i++, pos++) { // pos++: past the '/' or ';' that ends the record
                int d = Character.digit(value.charAt(pos++), 36);
                depth[i] = d;
                parent[i] = d == 0 ? -1 : path[d - 1];
                path[d] = i;
                name[i] = (int) num();
                pos++;
                start[i] = cur[d] + num();
                cur[d] = cur[d + 1] = start[i];
                pos++;
                dur[i] = num();
                thread[i] = d == 0 ? 0 : thread[parent[i]];
                wait[i] = waitMax[i] = cpu[i] = -1;
                if (at('@')) thread[i] = at('_') ? -1 : (int) num();
                if (at('~')) {
                    wait[i] = num();
                    if (at('.')) waitMax[i] = num();
                }
                if (at('*')) cpu[i] = num();
                if (at('+')) {
                    count[i] = num();
                    pos++;
                    min[i] = num();
                    pos++;
                    max[i] = num();
                    pos++;
                    extent[i] = num();
                    pos++;
                    maxAt[i] = num();
                }
                if (at('^')) for (int k; pos < e && (k = FLAGS.indexOf(value.charAt(pos))) >= 0; pos++) flags[i] |= 1 << k;
                attrFrom[i] = attrs;
                while (at(':')) {
                    if (attrs == attrKey.length) {
                        attrKey = Arrays.copyOf(attrKey, 2 * attrs);
                        valueFrom = Arrays.copyOf(valueFrom, 2 * attrs);
                        valueTo = Arrays.copyOf(valueTo, 2 * attrs);
                    }
                    attrKey[attrs] = (int) num();
                    valueFrom[attrs] = ++pos; // past '='
                    for (char c; (c = value.charAt(pos)) != '/' && c != ';' && c != ':'; ) pos++;
                    valueTo[attrs++] = pos;
                }
                attrTo[i] = attrs;
            }
        }

        private boolean at(char c) {
            if (value.charAt(pos) != c) return false;
            pos++;
            return true;
        }

        /** A base-36 number at {@code pos}. */
        private long num() {
            long v = 0;
            for (char c; (c = value.charAt(pos)) >= '0' && c <= '9' || c >= 'a' && c <= 'z'; pos++) v = v * 36 + Character.digit(c, 36);
            return v;
        }

        /** The first step whose value {@code fit} takes, written into {@code b}; null if none does. */
        String run(Header.Fit fit, StringBuilder b) {
            for (int[] g : groups()) if (fold(g) && fits(fit, b)) return b.toString();
            for (int d; (d = deepest()) >= 3; ) if (drop(d) && fits(fit, b)) return b.toString();
            if (attrs > 0) {
                attributes = false;
                if (fits(fit, b)) return b.toString();
            }
            return drop(2) && fits(fit, b) ? b.toString() : null;
        }

        private boolean fits(Header.Fit fit, StringBuilder b) {
            write(b);
            return fit.fits(b);
        }

        private boolean waits(int r) {
            return wait[r] >= 0;
        }

        /**
         * Step 1's groups: the children of one parent with one name, handoffs (records with a queue wait)
         * apart from the rest, phases never. Each lists its first record, then those it folds: all but the
         * failed single records and the slowest single one (the first of equals). Only groups that fold two
         * or more, the most first, then by their first record.
         */
        private int[][] groups() {
            int phases = 0;
            for (int i = 1; i < n; i++) if ((flags[i] & P) != 0) phases++;
            Integer[] by = new Integer[n - 1 - phases];
            for (int i = 1, k = 0; i < n; i++) if ((flags[i] & P) == 0) by[k++] = i;
            Comparator<Integer> key = Comparator.<Integer>comparingInt(i -> parent[i]).thenComparingInt(i -> name[i])
                    .thenComparing(this::waits);
            Arrays.sort(by, key.thenComparingInt(i -> i));
            List<int[]> groups = new ArrayList<>();
            for (int i = 0, j; i < by.length; i = j) {
                for (j = i + 1; j < by.length && key.compare(by[i], by[j]) == 0; j++) ;
                int slowest = -1;
                for (int k = i; k < j; k++) if (count[by[k]] == 0 && (slowest < 0 || dur[by[k]] > dur[slowest])) slowest = by[k];
                int[] g = new int[j - i + 1];
                int m = 1;
                g[0] = by[i];
                for (int k = i; k < j; k++) {
                    int r = by[k];
                    if (r != slowest && (count[r] > 0 || (flags[r] & F) == 0)) g[m++] = r;
                }
                if (m > 2) groups.add(Arrays.copyOf(g, m));
            }
            groups.sort((a, c) -> a.length != c.length ? c.length - a.length : a[0] - c[0]);
            return groups.toArray(new int[0][]);
        }

        /**
         * Folds the records of group {@code g} into its first one to fold, which keeps its place and start;
         * their subtrees are dropped. False if an earlier fold dropped the group.
         */
        private boolean fold(int[] g) {
            int t = g[1], th = thread[t], fl = -1;
            if (removed[t]) return false;
            long c = 0, sum = 0, lo = Long.MAX_VALUE, hi = -1, end = 0, slowAt = 0, waitSum = 0, waitHi = 0, cpuSum = -1;
            for (int k = 1; k < g.length; k++) {
                int r = g[k];
                boolean agg = count[r] > 0;
                long longest = agg ? max[r] : dur[r];
                if (longest > hi) {
                    hi = longest;
                    slowAt = agg ? start[r] + maxAt[r] : start[r];
                }
                c += agg ? count[r] : 1;
                sum += dur[r];
                lo = Math.min(lo, agg ? min[r] : dur[r]);
                end = Math.max(end, agg ? start[r] + extent[r] : start[r] + Math.max(0, wait[r]) + dur[r]);
                if (waits(r)) {
                    waitSum += wait[r];
                    waitHi = Math.max(waitHi, agg ? waitMax[r] : wait[r]);
                }
                if (cpu[r] >= 0) cpuSum = Math.max(0, cpuSum) + cpu[r];
                if (thread[r] != th) th = -1;
                fl &= flags[r];
                if (!agg) folded++;
                for (int i = r + 1; i < n && depth[i] > depth[r]; i++) if (!removed[i]) remove(i);
                if (r != t) removed[r] = true;
            }
            count[t] = c;
            dur[t] = sum;
            min[t] = lo;
            max[t] = hi;
            extent[t] = end - start[t];
            maxAt[t] = slowAt - start[t];
            if (waits(t)) {
                wait[t] = waitSum;
                waitMax[t] = waitHi;
            }
            cpu[t] = cpuSum;
            thread[t] = th;
            flags[t] = fl;
            attrTo[t] = attrFrom[t];
            return true;
        }

        /** Leaves record {@code i} out: dropped, an aggregate by its count, which is no longer folded. */
        private void remove(int i) {
            removed[i] = true;
            dropped += count[i] > 0 ? count[i] : 1;
            if (count[i] > 0) folded = Math.max(0, folded - count[i]);
        }

        private int deepest() {
            int d = 0;
            for (int i = 0; i < n; i++) if (!removed[i]) d = Math.max(d, depth[i]);
            return d;
        }

        /** Leaves out the records at depth {@code d}, which have no children; whether there were any. */
        private boolean drop(int d) {
            boolean any = false;
            for (int i = 0; i < n; i++) {
                if (removed[i] || depth[i] != d) continue;
                remove(i);
                any = true;
            }
            return any;
        }

        /** Writes the remaining records in canonical form, the tables reduced to what they use. */
        private void write(StringBuilder b) {
            Arrays.fill(nameNew, -1);
            Arrays.fill(threadNew, -1);
            int nNames = 0, records = 0;
            for (int i = 0; i < n; i++) {
                if (removed[i]) continue;
                if (nameNew[name[i]] < 0) nameOrder[nameNew[name[i]] = nNames++] = name[i];
                if (thread[i] >= 0) threadNew[thread[i]] = 0;
            }
            for (int i = 0; i < n && attributes; i++) {
                for (int a = attrFrom[i]; a < attrTo[i] && !removed[i]; a++) {
                    if (nameNew[attrKey[a]] < 0) nameOrder[nameNew[attrKey[a]] = nNames++] = attrKey[a];
                }
            }
            for (int j = 0, k = 0; j < threads.length; j++) if (threadNew[j] == 0) threadNew[j] = k++;

            b.setLength(0);
            b.append("v1");
            if (wall != null) b.append(";w=").append(wall);
            if (folded > 0 || dropped > 0) Header.b36(Header.b36(b.append(";x="), folded).append('.'), dropped);
            boolean any = false;
            for (int j = 0; j < threads.length; j++) {
                if (threadNew[j] < 0 || !live[j]) continue;
                Header.b36(b.append(any ? "." : ";l="), threadNew[j]);
                any = true;
            }
            b.append(";T=");
            any = false;
            for (int j = 0; j < threads.length; j++) {
                if (threadNew[j] < 0) continue;
                (any ? b.append(':') : b).append(threads[j]);
                any = true;
            }
            b.append(";N=");
            for (int k = 0; k < nNames; k++) (k > 0 ? b.append(':') : b).append(names[nameOrder[k]]);
            b.append(";S=");
            for (int i = 0; i < n; i++) {
                if (removed[i]) continue;
                int d = depth[i];
                if (records++ > 0) b.append('/');
                long delta = start[i] - (d == 0 ? 0 : cur[d]);
                cur[d] = cur[d + 1] = start[i];
                Header.b36(b.append(Header.DIGITS[d]), nameNew[name[i]]).append('.');
                Header.b36(Header.b36(b, delta).append('.'), dur[i]);
                threadAt[d] = thread[i];
                if (d > 0 && thread[i] != threadAt[d - 1]) {
                    if (thread[i] < 0) b.append("@_");
                    else Header.b36(b.append('@'), threadNew[thread[i]]);
                }
                if (waits(i)) {
                    Header.b36(b.append('~'), wait[i]);
                    if (count[i] > 0) Header.b36(b.append('.'), waitMax[i]);
                }
                if (cpu[i] >= 0) Header.b36(b.append('*'), cpu[i]);
                if (count[i] > 0) {
                    Header.b36(Header.b36(Header.b36(b.append('+'), count[i]).append('.'), min[i]).append('.'), max[i]);
                    Header.b36(Header.b36(b.append('.'), extent[i]).append('.'), maxAt[i]);
                }
                if (flags[i] != 0) {
                    b.append('^');
                    for (int k = 0; k < FLAGS.length(); k++) if ((flags[i] & 1 << k) != 0) b.append(FLAGS.charAt(k));
                }
                for (int a = attrFrom[i]; a < attrTo[i] && attributes; a++) {
                    Header.b36(b.append(':'), nameNew[attrKey[a]]).append('=').append(value, valueFrom[a], valueTo[a]);
                }
            }
            Header.b36(b.append(";e="), records);
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * One request's recording, and its outermost span: {@code t.span(n)} opens a child as {@code
     * Tempo.span(n)} does. Not pooled. Reads stay valid after {@link #close()} until {@link #release()}.
     *
     * <p>A trace started inside another one ({@link Tempo#trace(Name, Options)}) joins it as a subtree and
     * works as a self-contained block: for the block it behaves as if it were top-level. Its {@link
     * #total}, {@link #on} and {@link #records(int)} cover its own subtree, its tasks on other threads
     * included; it renders that subtree with its own {@link Options#headerBytes} and {@link
     * Options#linkBase}; its {@link Options#strict}, {@link Options#cpu} and {@link Options#budget} (a
     * sub-budget) hold inside it and in its tasks. What its options leave unset comes from the outer trace
     * (see {@link Options}). The outer trace still counts and draws everything, the block's spans with the rest.
     *
     * <pre>{@code
     * static final Tempo.Options BLOCK = new Tempo.Options().budget(64);
     *
     * Tempo.Trace in = Tempo.trace("rebuild-index", BLOCK); // top-level, or a block of the caller's trace
     * try (in) {
     *     index.rebuild();
     * }
     * log.info(in.toRuntime(3) + ", " + in.total("load").count() + " loads"); // the block's own
     * in.release(); // nothing to give back when it is a block: the buffers are the outer trace's
     * }</pre>
     */
    public static final class Trace extends Span {
        private static final VarHandle MODE, CREDITS, TOTALS;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                MODE = l.findVarHandle(Trace.class, "mode", int.class);
                CREDITS = l.findVarHandle(Trace.class, "credits", int.class);
                TOTALS = l.findVarHandle(Trace.class, "totals", Totals.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final boolean nested; // joined an outer trace: renders its subtree, releases nothing
        Consumer<String> printer;     // printOnClose; written and read by the owner thread

        // The scope: what the trace's subtree records by, and what it keeps. A trace's root is the scope of
        // the whole trace (Context#root); a nested trace is a block, a scope of its own inside up's, which the
        // lane carries while it is open and its tasks carry where they run (Lane#scope). Effective values:
        // a block's strictness and CPU setting already include its enclosing scope's where they apply.
        final Trace up;               // the enclosing scope; null for the trace's root
        final boolean strict;         // strict here: its own, or any enclosing scope's
        final int cpu, block;         // CPU_ON, CPU_OFF or CPU_AUTO; budget handed to a lane at a time
        final int headerBytes;        // of its own output
        final String linkBase;        // of its own output; null: Link.base()
        volatile int mode;            // (level + 1) << 1 | SEEN; once seen, the effective level, fixed
        private volatile int credits; // budget of steps, ticks and tasks not handed out to a lane
        volatile Totals totals;       // lane-less: Span#add and the queue waits of lost tasks
        boolean widened;              // guarded by ctx
        Listeners on;                 // guarded by ctx; replaced whole when one is added

        /** The root of a trace starting with {@code o}: its lane is attached when the trace opens. */
        Trace(Context ctx, Options o) {
            super(null, ctx, 0);
            scope = this;
            nested = false;
            up = null;
            strict = o.strict;
            cpu = o.cpu;
            credits = o.budget;
            block = Math.max(1, o.budget / 64);
            headerBytes = o.headerBytes;
            linkBase = o.linkBase;
            mode = (SPAN + 1) << 1;
        }

        /** The root of {@link Context#NONE} or {@link Context#FREE}: records nothing at {@code level}. */
        Trace(Context ctx, int level) {
            super(null, ctx, 0);
            scope = this;
            nested = false;
            up = null;
            strict = false;
            cpu = CPU_OFF;
            credits = block = headerBytes = 0;
            linkBase = null;
            mode = (level + 1) << 1;
        }

        /** A block opening at {@code depth} of {@code lane}, inside scope {@code up}, with options {@code o}. */
        Trace(Lane lane, Context ctx, int depth, Trace up, Options o) {
            super(lane, ctx, depth);
            scope = this;
            nested = true;
            this.up = up;
            strict = up.strict || o.strict;
            cpu = o.cpu == CPU_AUTO ? up.cpu : o.cpu;
            boolean own = o.isSet(Options.BUDGET); // else no sub-budget: the outer trace's remainder, in its blocks
            credits = own ? o.budget : Integer.MAX_VALUE;
            block = own ? Math.max(1, o.budget / 64) : up.block;
            headerBytes = o.isSet(Options.HEADER_BYTES) ? o.headerBytes : up.headerBytes;
            linkBase = o.isSet(Options.LINK_BASE) ? o.linkBase : up.linkBase;
            mode = (SPAN + 1) << 1;
        }

        /** {@link Tempo#trace}: {@code zero} is the clock read first, {@code text} the name when given as a String. */
        static Trace open(Name name, String text, Options options, long zero) {
            ThreadSlot slot = SLOT.get();
            Lane cur = slot.current;
            if (cur != null && !cur.ctx.released) return nest(cur, name, text, options);
            Context ctx = new Context(options == null ? Options.DEFAULTS : options, zero); // zero: the root's start
            Trace t = ctx.root;
            // Right after: CPU covers the root like its wall time, and the first CPU read of a JVM (it loads
            // the management bean, milliseconds) happens here, counted in the root's own time.
            long cpu = t.measuresCpu() ? ctx.cpuNow() : -1;
            int flags = 0;
            if (text != null) {
                name = t.nameOf(text);
                if (Name.overflowed(name, text)) flags = MISUSE;
            }
            if (name == null) {
                t.misuse("null name");
                name = Name.UNKNOWN;
                flags = MISUSE;
            }
            Lane lane = ctx.register(new Lane(ctx, slot));
            t.lane = lane;
            t.owner = lane.owner;
            lane.openRoot(t, name, flags, cpu);
            return t;
        }

        /**
         * A trace started inside the trace of {@code cur}, the thread's current lane: a block, a span of it on
         * top whose subtree is a scope of its own, inside the lane's scope. The one object of the call.
         */
        private static Trace nest(Lane cur, Name name, String text, Options options) {
            Context ctx = cur.ctx;
            Trace t = new Trace(cur, ctx, cur.depth, cur.scope, options == null ? Options.DEFAULTS : options);
            int flags = 0;
            if (text != null) {
                name = t.nameOf(text);
                if (Name.overflowed(name, text)) flags = MISUSE;
            }
            if (name == null) {
                t.misuse("null name");
                name = Name.UNKNOWN;
                flags = MISUSE;
            }
            return cur.nest(t, name, flags) == null ? Context.NONE.root : t;
        }

        // ---- the scope (any thread unless said otherwise) ----

        /**
         * Marks that a step, tick or task was opened in this scope: from now on its level is fixed, and so
         * are the levels of the scopes enclosing it, whose subtree it is in too. Returns the mode it is
         * fixed at, read in the same atomic step, so a widening either came before it or fails. A block's
         * fixed level is the wider of its own and its enclosing scope's.
         */
        int sawStep() {
            int m = mode;
            return (m & Context.SEEN) != 0 ? m : markSeen();
        }

        private int markSeen() {
            int u = up == null ? 0 : up.sawStep() & ~Context.SEEN;
            int m;
            while (((m = mode) & Context.SEEN) == 0 && !MODE.compareAndSet(this, m, Math.max(m, u) | Context.SEEN)) { }
            return mode;
        }

        /**
         * Widens this scope's level to {@code level}, unless a step, tick or task was seen in it first. Called
         * under the context's lock, which orders it against another widening; the seen mark is a CAS, which
         * this one races.
         */
        boolean widen(int level) {
            int m;
            do {
                m = mode;
                if ((m & Context.SEEN) != 0) return false;
            } while (!MODE.compareAndSet(this, m, (level + 1) << 1));
            return true;
        }

        /** The level of this scope: once seen, fixed; before, the wider of its own and its enclosing scope's. */
        int level() {
            int m = mode, own = (m >> 1) - 1;
            return (m & Context.SEEN) != 0 || up == null ? own : Math.max(own, up.level());
        }

        /** Tests: sets the level as if widened, without the checks. */
        void level(int level) {
            mode = (level + 1) << 1 | mode & Context.SEEN;
        }

        /** Whether a span opening now in this scope has its CPU time measured. */
        boolean measuresCpu() {
            return cpu == CPU_ON || cpu == CPU_AUTO && level() >= STEP;
        }

        /**
         * Hands a lane up to one block of this scope's budget, taken from every enclosing scope's too: a
         * block draws at most its own budget and never more than the trace has left. 0 when spent.
         */
        int credits() {
            return take(block);
        }

        private int take(int want) {
            int c, n;
            do {
                c = credits;
                if (c <= 0) return 0;
                n = Math.min(want, c);
            } while (!CREDITS.compareAndSet(this, c, c - n));
            if (up == null) return n;
            int got = up.take(n);
            if (got < n) CREDITS.getAndAdd(this, n - got);
            return got;
        }

        /** Gives back budget a lane held for this scope, to it and to every enclosing scope. */
        void refund(int n) {
            for (Trace s = this; s != null; s = s.up) CREDITS.getAndAdd(s, n);
        }

        int unspent() {
            return credits;
        }

        Totals totals() {
            Totals t = totals;
            if (t == null && !TOTALS.compareAndSet(this, null, t = new Totals(8))) t = totals;
            return t;
        }

        Totals frozenTotals() {
            Totals t = totals;
            return t == null ? null : t.copy();
        }

        /** Adds a lane-less occurrence or queue wait of name {@code id} to this scope and every one enclosing it. */
        void addTotal(int id, boolean wait, long nanos) {
            for (Trace s = this; s != null; s = s.up) s.totals().add(id, wait, nanos);
        }

        /** Whether scope {@code s} is {@code block} or nested in it. */
        static boolean within(Trace s, Trace block) {
            for (; s != null; s = s.up) if (s == block) return true;
            return false;
        }

        /**
         * The name of a {@code String} parameter: interned in the global table; past its cap {@code <other>},
         * counted as misuse in this scope. {@code null} stays null, for the caller to count.
         */
        Name nameOf(String text) {
            if (text == null) return null;
            Name n = Name.intern(text, true);
            if (Name.overflowed(n, text)) misuse("name table full: " + Name.DYNAMIC_CAP + " names from strings");
            return n;
        }

        /** The id of attribute key {@code key}; null is misuse in this scope, recorded as {@code ?}. */
        int attrKey(Name key) {
            if (key != null) return key.id;
            misuse("null attribute key");
            return Name.UNKNOWN.id;
        }

        // ---- the public API ----

        /**
         * What the trace records: {@link #SPAN} (from the start), {@link #STEP} or {@link #TICK}. Of a block,
         * what its subtree records: the wider of its own level and the outer trace's.
         */
        public int records() {
            return level();
        }

        /**
         * Widens what the trace records to {@code level} ({@link #STEP} or {@link #TICK}), once, before the
         * first step or tick opens, so it chains as {@code Tempo.trace(name).records(Tempo.STEP)}; afterwards
         * it is misuse and changes nothing (strict mode throws), which {@link #records()} shows. Asking for
         * the current level or a lower one changes nothing. A widening that turns on CPU time ({@link
         * Options#cpu}) reads the CPU clock once on the calling thread, so the JVM's one-time setup of that
         * clock (milliseconds) is paid here, in the trace's wall time, never inside a pool task; called on the
         * owner thread while the trace is open, the trace's own CPU time is measured from that read on. On a
         * block it widens the block's subtree only, its tasks included, before the first step or tick opens
         * in that subtree; the outer trace's level is untouched, and its tree shows what the block drew.
         */
        public Trace records(int level) {
            if (lane == null) return this;
            int to = clampLevel(level);
            String refused = null;
            boolean cpuOn = false;
            synchronized (ctx) {
                if (to <= level()) return this;
                boolean before = measuresCpu();
                if (widened) {
                    refused = "records widened twice";
                } else if (!widen(to)) {
                    refused = "records widened after a step or tick opened";
                } else {
                    widened = true;
                    cpuOn = !before && measuresCpu();
                }
            }
            if (refused != null) {
                misuse(refused);
            } else if (cpuOn) {
                long cpu = ctx.cpuNow(); // the first read of a JVM sets the clock up, on this thread
                Lane l = lane;
                if (Thread.currentThread() == owner && depth < l.depth && l.handles[depth] == this && row != Lane.FRAME
                        && (l.cpuMask >>> depth & 1) == 0) l.startCpu(depth, cpu);
            }
            return this;
        }

        /**
         * Returns the trace's buffers to the pool. Optional and idempotent; afterwards every read is empty
         * and never throws, so take a {@link #snapshot()} first to render later. A thread still inside a
         * task of this trace keeps its lane until the task ends; tasks begun after release record nothing,
         * and still cancel.
         * On a block it does nothing: the buffers are the outer trace's, released with it.
         */
        public void release() {
            if (lane != null && !nested) ctx.release();
        }

        /**
         * Adds a listener for this trace, called with the duration in nanoseconds of every timed occurrence
         * of {@code name} that ends from now on: spans, steps, recorded ticks, phases, tasks and {@link Span#add}.
         * Like {@link Tempo#on} it runs on the ending thread without a lock, so it must be thread-safe. Any
         * thread may add one; another thread hears it once it synchronizes with the adding one, e.g. through
         * a task submitted after it. On a block it hears the block's subtree only, including the tasks
         * submitted in it that end after the block closed; it is dropped with the outer trace's release.
         */
        public Trace on(Name name, LongConsumer nanos) {
            if (lane == null) return this;
            if (name == null || nanos == null) {
                misuse("null listener");
                return this;
            }
            synchronized (ctx) {
                on = new Listeners(on, name.id, nanos);
                ctx.listening = true;
            }
            return this;
        }

        /** Like {@link #on(Name, LongConsumer)}, the name looked up as for {@link Tempo#span(String)}. */
        public Trace on(String name, LongConsumer nanos) {
            return lane == null ? this : on(nameOf(name), nanos);
        }

        /**
         * Count, sum, mean and extremes of every timed occurrence of {@code name} that ended: spans, steps
         * and tasks (drawn, folded over the budget or undrawn), recorded ticks, phases and {@link Span#add};
         * and the queue waits of its tasks, of tasks left unrecorded at a cap, begun or cancelled. The numbers are
         * the same whatever the trace drew. Exact once the contributing threads are done, best effort while
         * they run; empty after release. Reads the lanes in place: no copy, one allocation, time linear in
         * the trace's rows, events and aggregates. On a block, those of the block's subtree, its tasks on
         * other threads included, computed when read (so recording pays nothing for it): a few arrays per
         * read, by lane, and time linear in the trace's rows, events and aggregates.
         */
        public Total total(Name name) {
            if (name == null || lane == null) return Total.EMPTY;
            return nested ? ctx.total(name.id, this) : ctx.total(name.id);
        }

        /** Like {@link #total(Name)}; empty for a name never interned. */
        public Total total(String name) {
            return total(Name.find(name));
        }

        /**
         * The {@code Tempo-Timing} header value, as specified in {@code FORMAT.md}: one line of at most
         * {@link Options#headerBytes} bytes, see {@link #toHeader(int)}. Renders a {@link #snapshot()}, so
         * spans still open end at one instant; empty after release.
         */
        public String toHeader() {
            return snapshot().toHeader();
        }

        /**
         * The {@code Tempo-Timing} header value in at most {@code maxBytes} bytes, for a limit shared with
         * other headers: a value that fits is written whole; one that does not goes down the ladder of
         * FORMAT.md §7.1, stopping at the first step that fits: same-name siblings fold into aggregates
         * (largest group first, failed ones and the slowest kept), then the deepest levels go down to
         * depth 2, then the attributes, then depth 2. What is left out is counted in {@code x=}. Below 256
         * bytes, or if no step fits, the value is the fallback {@code v1;x=0.<n>;T=%3F;N=%3F;S=00.0.<dur>;e=1},
         * which a limit below 256 may not hold. The {@code Runtime} and {@code Server-Timing} headers and this
         * one share a limit like this:
         *
         * <pre>{@code
         * String runtime = t.toRuntime(3), timing = t.toServerTiming();
         * int left = limit - runtime.length() - timing.length(); // minus the header names, if the limit counts them
         * response.header("Runtime", runtime).header("Server-Timing", timing).header("Tempo-Timing", t.toHeader(left));
         * }</pre>
         *
         * Empty after release.
         */
        public String toHeader(int maxBytes) {
            return snapshot().toHeader(maxBytes);
        }

        /**
         * The {@code Tempo-Link} header value, as specified in {@code FORMAT.md} section 9: a URL of the web
         * viewer (see {@link Options#linkBase}) whose fragment carries {@link #toHeader()} compressed, so
         * one click opens the trace and the trace never reaches the viewer's server. Empty after release.
         */
        public String toLink() {
            return snapshot().toLink();
        }

        /**
         * Like {@link #toLink()}, to the viewer at {@code base}; null for the default.
         *
         * @throws IllegalArgumentException if {@code base} is not a valid base, see {@link Options#linkBase}
         */
        public String toLink(String base) {
            return snapshot().toLink(base);
        }

        /**
         * Like {@link #toLink(String)}, at most {@code maxBytes} long in all, base included: the value goes
         * down the same ladder as {@link #toHeader(int)} until the compressed link fits. Below 256 bytes
         * besides the base, or if no step fits, it carries the fallback value. Compresses once per step
         * tried. Empty after release.
         *
         * @throws IllegalArgumentException if {@code base} is not a valid base, see {@link Options#linkBase}
         */
        public String toLink(String base, int maxBytes) {
            return snapshot().toLink(base, maxBytes);
        }

        /**
         * Writes the trace in the JSON form of {@code FORMAT.md} section 8, from a {@link #snapshot()}: see
         * {@link Snapshot#writeJson}. A response object that carries the trace in its body holds that
         * {@link Snapshot}, taken before {@link #release()}, never the {@code Trace}. Writes no spans after
         * release.
         */
        public void writeJson(Appendable out) throws IOException {
            snapshot().writeJson(out);
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
         * it before releasing when the trace is rendered later. Of a block, its subtree, with times from its
         * start, its totals and its output settings. Empty after release.
         */
        public Snapshot snapshot() {
            if (lane == null || row == Lane.FRAME) return Snapshot.EMPTY;
            return Snapshot.of(ctx, lane.id, row, nested ? this : null);
        }

        /**
         * Visits the labelled phases of the trace's root ({@link Phase#label}) in start order, with their
         * name, label, start from the trace's start and duration; an open phase lasts until now. Reads the
         * trace's lane in place.
         */
        public void forEachTop(TopVisitor visitor) {
            tops().forEach(visitor);
        }

        /**
         * The {@code Runtime} text {@code <total>s = <d1>s (<label1>) + ... + <dn>s (<labeln>)}, or {@code
         * <total>s} without labelled phases (see {@link #forEachTop}). The total is the trace's wall time,
         * up to now while it is open. Each phase prints its label. Durations are
         * seconds with {@code digits} fixed decimals, rounded half up, or with {@code digits = -1} as
         * {@code Double.toString(nanos / 1e9)} prints them. After release: just the total, {@code 0}.
         */
        public String toRuntime(int digits) {
            return tops().runtime(digits);
        }

        /** {@link #toRuntime(int)} with the total given, so it can match a total read once for several headers. */
        public String toRuntime(int digits, long totalNanos) {
            return tops().runtime(digits, totalNanos);
        }

        /**
         * A W3C {@code Server-Timing} value: one {@code <token>;dur=<ms>} per labelled phase (see
         * {@link #forEachTop}), then {@code total;dur=<ms>}, joined by {@code ", "}. The token is the
         * phase's name lower-cased with each run of characters outside {@code [a-z0-9]} replaced by
         * {@code -}; durations are milliseconds with three decimals, rounded half up.
         */
        public String toServerTiming() {
            return tops().serverTiming();
        }

        private Tops tops() {
            return lane == null || row == Lane.FRAME ? Tops.EMPTY : lane.tops(row);
        }

        /**
         * Passes {@code <label>: } and the {@link #toRuntime(int) toRuntime(-1)} text to {@code out} as one
         * line when the trace closes, or at once if it has closed; no prefix for a {@code null} label. Only
         * on the owner thread; a throw from {@code out} is handled like a listener's ({@link #on}).
         */
        public Trace printOnClose(Consumer<String> out, String label) {
            if (lane == null) return this;
            if (out == null) {
                misuse("null printer");
            } else if (Thread.currentThread() != owner) {
                misuse("printer added on a foreign thread");
            } else {
                Consumer<String> p = label == null ? out : line -> out.accept(label + ": " + line);
                if (depth < lane.depth && lane.handles[depth] == this) {
                    printer = p;
                } else if (row != Lane.FRAME) {
                    Throwable t = lane.print(p, row);
                    if (t != null && strict) throw Context.listenerThrew(t);
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

        /** Steps and ticks folded into aggregates for want of budget so far, open ones included. */
        int folded() {
            return ctx.folded();
        }

        /**
         * Occurrences in blocks that went to an enclosing scope's totals for want of room in a lane's
         * aggregates (see {@link Aggs#SCOPED}): the trace's totals have them, the blocks' do not.
         */
        int unattributed() {
            return ctx.unattributed();
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Anything timed: a {@link Span}, a {@link Phase} or a {@link Task}. It has a name and a start, and its
     * duration is {@link #elapsedNanos()}; numbers and the failed flag attach to it. A task's own time is
     * its queue wait; its run is timed by its span.
     */
    public abstract static class Timing {
        Timing() {}

        /** The name; null on {@link Tempo#NOOP} and when nothing is kept (see each type). Any thread. */
        public abstract Name name();

        /**
         * The start, in nanoseconds from the trace's start; 0 outside a trace, after release, and when
         * nothing is kept (see each type). Any thread, best effort off the owner.
         */
        public abstract long startNanos();

        /** Attaches a number. */
        public abstract Timing set(Name key, long value);

        /** Like {@link #set(Name, long)}, the key looked up as for {@link Tempo#span(String)}. */
        public abstract Timing set(String key, long value);

        /** Attaches a number. */
        public abstract Timing set(Name key, double value);

        /** Like {@link #set(Name, double)}, the key looked up as for {@link Tempo#span(String)}. */
        public abstract Timing set(String key, double value);

        /** Marks it failed. */
        public abstract Timing fail();

        /** Marks it failed; only the flag is recorded, not the throwable. */
        public abstract Timing fail(Throwable cause);

        /** Running time while open, the duration once ended; of a task, its queue wait. */
        public abstract long elapsedNanos();
    }

    /**
     * Same-thread handle to a span. A recorded span has a handle of its own for the whole trace: {@link
     * #set}, {@link #fail}, {@link #cancel} and {@link #waiting} work after close too, as in the catch block
     * right after, until the trace is released. A span folded past the budget or an undrawn {@link #step}
     * shares one handle with the others at its depth on that thread: it keeps no attributes, and flags only
     * while it is open. The other calls after close, and every call on a foreign thread, are ignored and
     * counted as misuse, and methods that open spans then return {@link #NOOP}.
     *
     * <p>Once its trace is released, the thread pools the handle and later issues it to a span of another
     * trace. A call through it while it sits in the pool is stale: ignored and counted as misuse in the
     * released trace. A call after the handle was issued again acts on the new span, undetected; so keep a
     * handle inside the block that opened it. {@link Options#strict} never pools a handle, so there every
     * stale call throws.
     *
     * <p>A span opens children only as the innermost open span of its thread: opening one under a span with
     * others open above it closes those first, flagged healed, and counts as misuse (strict mode throws).
     * Its {@link #phase phases} section its own timeline and open nothing, so they work at any time while
     * it is open.
     */
    public static class Span extends Timing implements AutoCloseable {
        private static final VarHandle STAMP;

        static {
            try {
                STAMP = MethodHandles.lookup().findVarHandle(Span.class, "stamp", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static final String STALE = "span used after its trace was released";

        // The binding, written by the owner when it issues the handle (bind) and by a retiring lane when the
        // handle goes back to the pool (detach). The stamp is the id of the trace the handle is issued in, 0
        // while it is pooled; it is written last on bind and first on detach, so a reader on another thread
        // that reads the same stamp before and after the binding read it whole.
        Lane lane;
        Context ctx;
        Thread owner;
        AtomicInteger misuses; // of the trace it was last issued in; kept while pooled, for stale uses
        // The scope its children open in, as its lane's was when it was issued (a frame's when it opened): the
        // innermost self-contained block, or the trace's own root; a block's is the block itself. Its level
        // decides a tick through it, its strictness every misuse of it. Context.NONE's root while pooled.
        Trace scope;
        int depth;
        @SuppressWarnings("unused") private long stamp; // via STAMP
        Span link; // the next handle in its lane's issued list, or in its slot's pool
        int row;    // Lane.FRAME for a span without a row (folded, undrawn, or outside a trace), which has the next seven
        Name frame;
        int agg;    // its entry in the lane's aggregates, or -1
        boolean folded; // folded for want of budget
        boolean hidden; // an undrawn step: only the totals read its entry
        int flags;  // flags set while open, kept where every occurrence of the aggregate has them
        long wait;  // the queue wait of a folded task while open; the duration once ended
        long time;  // the start

        Span(Lane lane, Context ctx, int depth) {
            this.lane = lane;
            this.ctx = ctx;
            this.owner = lane == null ? null : lane.owner;
            this.misuses = ctx.misuses;
            this.scope = lane == null ? ctx.root : lane.scope; // the root of Context.NONE, FREE: set by its Trace
            this.depth = depth;
            this.stamp = ctx.id;
        }

        /** Owner thread: issues this pooled handle to a span at depth {@code d} of {@code l}. */
        void bind(Lane l, int d) {
            Context c = l.ctx;
            lane = l;
            ctx = c;
            owner = l.owner;
            misuses = c.misuses;
            scope = l.scope;
            depth = d;
            folded = hidden = false;
            STAMP.setRelease(this, c.id);
        }

        /**
         * The lane retiring: takes the handle off its trace before it is pooled, so the pool keeps no lane
         * or trace alive and every use from now until the next {@link #bind} is stale. Any thread.
         */
        void detach() {
            STAMP.setOpaque(this, 0L);
            VarHandle.storeStoreFence();
            lane = Lane.POOLED;
            ctx = Context.NONE;
            scope = Context.NONE.root;
        }

        /** Whether the trace of this handle is released and its lane retired, or the handle is pooled. Not {@link #NOOP}. */
        boolean stale() {
            return lane.retired() || stamp() != ctx.id;
        }

        /** The id of the trace the handle is issued in; 0 while it is pooled. */
        long stamp() {
            return (long) STAMP.getAcquire(this);
        }

        /** Counts a misuse of this handle in its trace, or in the trace it was last issued in; strict mode throws. */
        void misuse(String what) {
            misuses.incrementAndGet();
            if (scope.strict) throw new AssertionError("Tempo misuse: " + what);
        }

        /**
         * Opens a child span, always timed and drawn; in a folded task begun outside the trace, which has
         * no row to hang it from, folded. Outside a trace it is timed only for the name's global listeners.
         */
        public Span span(Name name) {
            Lane l = top();
            return l == null ? NOOP : l.openSpan(name, 0);
        }

        /** Like {@link #span(Name)}, the name looked up as for {@link Tempo#span(String)}. */
        public Span span(String name) {
            Lane l = top();
            return l == null ? NOOP : l.openSpan(name);
        }

        /** An unnamed child span, labelled only by its phases. */
        public Span span() {
            return span(Name.EMPTY);
        }

        /**
         * A child step: always timed into the trace's totals and listeners; drawn when the trace records
         * steps ({@link Trace#records(int)}) while its budget lasts ({@link Options#budget}), past it folded.
         * A folded or undrawn step writes no row: it costs two clock reads and an aggregate update. Spans
         * opened inside it are recorded under its nearest recorded ancestor; its phases count towards the
         * totals but are not drawn, attributes on it are ignored, flags set while it is open show on a folded
         * aggregate if every occurrence has them, and {@link #cpu()} adds to the aggregate's CPU time.
         */
        public Span step(Name name) {
            Lane l = top();
            return l == null ? NOOP : l.openStep(name, 0);
        }

        /** Like {@link #step(Name)}, the name looked up as for {@link Tempo#span(String)}. */
        public Span step(String name) {
            Lane l = top();
            return l == null ? NOOP : l.openStep(name);
        }

        /**
         * A child tick: timed and drawn only when the trace records ticks, then like a drawn {@link #step};
         * otherwise {@link #NOOP} at the cost of a branch. Strict mode checks the handle on every call, so a
         * call after close, from a foreign thread or after release throws. Outside strict mode an
         * unrecorded tick checks the handle only where that is free: through a pooled handle, and on the
         * trace's first step or tick, which fixes its level; such a misuse is counted, and a closed or
         * foreign handle never fixes the level.
         */
        public Span tick(Name name) {
            Trace c = scope;
            int m = c.mode;
            if (m < Context.TICK_MODE && ((m & Context.SEEN) != 0 && !c.strict || unrecorded())) return NOOP;
            Lane l = top();
            return l == null ? NOOP : l.openTick(name, 0);
        }

        /** Like {@link #tick(Name)}, the name looked up as for {@link Tempo#span(String)}. */
        public Span tick(String name) {
            Trace c = scope;
            int m = c.mode;
            if (m < Context.TICK_MODE && ((m & Context.SEEN) != 0 && !c.strict || unrecorded())) return NOOP;
            Lane l = top();
            return l == null ? NOOP : l.openTick(name);
        }

        /**
         * A tick's slow way, below the level of ticks, in strict mode or with the level not yet fixed:
         * whether it is not recorded. {@link #NOOP} and a span outside a trace have no trace to record in; a
         * closed, foreign or stale handle is misuse (strict throws), and only a live one fixes the level.
         * A pooled handle's context is {@link Context#NONE}, whose level never fixes.
         */
        private boolean unrecorded() {
            Lane l = lane;
            return l == null || l.free || !live() || scope.sawStep() < Context.TICK_MODE;
        }

        /**
         * The lane, for a child to open on top: null unless {@link #live()}; with spans open above this one,
         * those are closed first, healed (misuse).
         */
        private Lane top() {
            if (!live()) return null;
            Lane l = lane;
            if (l.depth - 1 != depth) l.healAbove(depth);
            return l;
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

        /** Like {@link #run(Name, ThrowingRunnable)}, the name looked up as for {@link Tempo#span(String)}. */
        public <E extends Exception> void run(String name, ThrowingRunnable<E> body) throws E {
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

        /** Like {@link #run(Name, ThrowingRunnable)} for a body with a result. */
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

        /** Like {@link #call(Name, ThrowingSupplier)}, the name looked up as for {@link Tempo#span(String)}. */
        public <T, E extends Exception> T call(String name, ThrowingSupplier<T, E> body) throws E {
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
         * Starts a phase of this span: a labelled section of its timeline, not a parent of anything. It ends
         * the open phase at the same instant, so two phases in a row share one clock read; otherwise it
         * starts now, and the time since the last boundary stays unphased. On a span timed by hand ({@link
         * #start}, {@link #lap(long)}, {@link #stop}) with no phase open, it starts at the last boundary.
         * The span's children stay its children and may cross phase boundaries. A phase counts towards the
         * totals and listeners of its name, also on a folded or undrawn span, which draws none.
         */
        public Phase phase(Name name) {
            return phase(name, 0);
        }

        /** Like {@link #phase(Name)}, the name looked up as for {@link Tempo#span(String)}. */
        public Phase phase(String name) {
            if (lane == null) return Phase.NOOP;
            Name n = scope.nameOf(name);
            return phase(n, n != null && Name.overflowed(n, name) ? MISUSE : 0);
        }

        private Phase phase(Name name, int flags) {
            if (!live()) return Phase.NOOP;
            return name != null ? lane.phase(depth, name, flags) : lane.phase(depth, nullName(), MISUSE);
        }

        /** Ends the open phase now; no phase opens in its place. Without one open it does nothing. Returns this span. */
        public Span done() {
            if (live()) lane.done(depth, null, 0);
            return this;
        }

        /** Like {@link #done()}. */
        public Span lap() {
            return done();
        }

        /**
         * Ends the open phase, which must be {@code name}: with another one open it ends under its own name,
         * flagged and counted as misuse (strict mode throws). With none open, it names the time since the
         * last boundary (a phase start or end, a lap, or the span's start) as a phase {@code name}. Returns
         * this span.
         */
        public Span done(Name name) {
            if (live()) {
                if (name != null) lane.done(depth, name, 0);
                else lane.done(depth, nullName(), MISUSE);
            }
            return this;
        }

        /** Like {@link #done(Name)}, the name looked up as for {@link Tempo#span(String)}. */
        public Span done(String name) {
            if (lane == null) return this;
            Name n = scope.nameOf(name);
            if (n == null) return done((Name) null);
            if (live()) lane.done(depth, n, Name.overflowed(n, name) ? MISUSE : 0);
            return this;
        }

        /** Like {@link #done(Name)}. */
        public Span lap(Name name) {
            return done(name);
        }

        /** Like {@link #done(String)}. */
        public Span lap(String name) {
            return done(name);
        }

        /**
         * Ends the open phase at {@code nanos}, a reading of the trace's clock ({@code System.nanoTime()}
         * or {@link Options#clock}): a boundary there, also with no phase open. It must lie between the last
         * boundary and now; outside it is clamped, the span flagged and the misuse counted (strict mode
         * throws). Returns this span.
         */
        public Span lap(long nanos) {
            if (live()) lane.lap(depth, nanos);
            return this;
        }

        /**
         * Starts this span at {@code nanos}, a reading of the trace's clock, instead of when it opened: for
         * work timed elsewhere, as in {@code parent.span("x").phase("a").start(t0).lap(t1).phase("b")
         * .stop(t2).close()}. While the span's only boundary is the start of its open first phase, that phase
         * starts there too. The time must lie within the parent's window (from the parent's start to now)
         * and before the span's first boundary; outside it is clamped, the span flagged and the misuse
         * counted (strict mode throws). A trace's root, which starts at the trace's zero point, and a task,
         * which starts where its queue wait ends, do not move. Returns this span.
         */
        public Span start(long nanos) {
            if (live()) lane.start(depth, nanos);
            return this;
        }

        /**
         * Ends this span at {@code nanos}, a reading of the trace's clock, and its open phase with it. The
         * span stays the thread's innermost until {@link #close()}, which then keeps this end. The time must
         * lie between the last boundary and now; outside it is clamped, the span flagged and the misuse
         * counted (strict mode throws). Phases, laps and a second stop after it are misuse. Returns this span.
         */
        public Span stop(long nanos) {
            if (live()) lane.stop(depth, nanos);
            return this;
        }

        /**
         * Measures the thread's CPU time from now until the span closes, when the trace measures CPU
         * ({@link Options#cpu}); see {@link SpanView#cpuNanos()}. A read costs a system call. On a folded
         * or undrawn span it adds to the aggregate's CPU time.
         */
        public Span cpu() {
            if (live() && scope.measuresCpu()) lane.sampleCpu(depth);
            return this;
        }

        private Name nullName() {
            scope.misuse("null name");
            return Name.UNKNOWN;
        }

        /**
         * Attaches a number to this span. Also after it closed (e.g. in the catch block right after), until
         * its trace is released. Ignored on a folded or undrawn span, which has no row to keep it ({@link
         * #step}).
         */
        public Span set(Name key, long value) {
            if (owned() && row != Lane.FRAME && !lane.event(row, key(key) << KIND_BITS | ATTR_LONG, value)) misuse(STALE);
            return this;
        }

        /** Like {@link #set(Name, long)}, the key looked up as for {@link Tempo#span(String)}. */
        public Span set(String key, long value) {
            return lane == null || lane.free ? this : set(scope.nameOf(key), value);
        }

        public Span set(Name key, double value) {
            if (owned() && row != Lane.FRAME
                    && !lane.event(row, key(key) << KIND_BITS | ATTR_DOUBLE, Double.doubleToRawLongBits(value))) misuse(STALE);
            return this;
        }

        /** Like {@link #set(Name, double)}, the key looked up as for {@link Tempo#span(String)}. */
        public Span set(String key, double value) {
            return lane == null || lane.free ? this : set(scope.nameOf(key), value);
        }

        private int key(Name key) {
            return scope.attrKey(key);
        }

        /**
         * Adds one occurrence of {@code name} lasting {@code nanos}, measured elsewhere, to the trace's
         * totals ({@link Trace#total}) and calls the name's listeners on the calling thread. No span is
         * drawn. Any thread, also after close; allocation-free once the trace has seen the name. In a
         * self-contained block it counts in the block's totals too: on the thread recording inside the block
         * it goes to its lane's aggregate of the name in the block, allocation-free like an undrawn step;
         * elsewhere (e.g. after the block closed) to lane-less totals of the block. Outside a trace it only
         * calls the global listeners. Ignored after release, and counted as misuse once the handle's lane has
         * retired too.
         */
        public Span add(Name name, long nanos) {
            // Any thread, so it reads the binding between two reads of the stamp (see bind and detach).
            long s = (long) STAMP.getAcquire(this);
            Lane l = lane;
            Context c = ctx;
            AtomicInteger m = misuses;
            Trace sc = scope;
            VarHandle.loadLoadFence();
            if (l == null) return this;
            if (s != (long) STAMP.getOpaque(this) || s != c.id || l.retired()) {
                m.incrementAndGet(); // pooled handles are never strict
                if (sc.strict) throw new AssertionError("Tempo misuse: " + STALE);
                return this;
            }
            if (c.released) return this; // a task still running after release
            long v = Math.max(0, nanos);
            if (!l.free) {
                int id = sc.attrKey(name);
                if (sc.up == null) sc.totals().add(id, false, v);
                else Lane.add(c, sc, id, v);
            }
            Throwable t = name != null && c.listening ? c.notify(sc, name, v) : null;
            if (t != null && sc.strict) throw Context.listenerThrew(t);
            return this;
        }

        /** Like {@link #add(Name, long)}, the name looked up as for {@link Tempo#span(String)}. */
        public Span add(String name, long nanos) {
            if (lane == null) return this;
            return add(lane.free ? Name.find(name) : scope.nameOf(name), nanos);
        }

        /**
         * Marks the span failed. Also after close, until the trace is released, like {@link #set(Name,
         * long)}; on a folded or undrawn span ({@link #step}) only while it is open.
         */
        public Span fail() {
            return flag(FAILED);
        }

        /** Marks the span failed; only the flag is recorded, not the throwable. Also after close, like {@link #fail()}. */
        public Span fail(Throwable cause) {
            return flag(FAILED);
        }

        /** Marks the span cancelled or rejected. Also after close, like {@link #fail()}. */
        public Span cancel() {
            return flag(CANCELLED);
        }

        /** Marks the span as a blocking wait (join, take, get). */
        public Span waiting() {
            return flag(WAIT);
        }

        private Span flag(int bits) {
            if (!owned()) return this;
            Lane l = lane;
            if (row != Lane.FRAME) {
                if (!l.flag(row, bits)) misuse(STALE);
            } else if (depth < l.depth && l.handles[depth] == this) {
                flags |= bits; // an open frame: its aggregate shows what all occurrences had
            }
            return this;
        }

        /**
         * Running time while open, the duration once closed; 0 on {@link #NOOP} and after release. Any
         * thread, best effort off the owner.
         */
        public long elapsedNanos() {
            long s = (long) STAMP.getAcquire(this);
            Lane l = lane;
            int r = row;
            if (l == null || s != l.ctx.id) return 0; // pooled, or issued again while this read
            return r == Lane.FRAME ? l.frameElapsed(this) : l.elapsed(r);
        }

        /**
         * The span's name; null on {@link Tempo#NOOP} and once the trace is released. A span folded past
         * the budget or an undrawn step shares its handle with the others at its depth, so after it closed
         * this is the name of the latest of them. Any thread, best effort off the owner.
         */
        @Override
        public Name name() {
            long s = (long) STAMP.getAcquire(this);
            Lane l = lane;
            int r = row;
            Name f = frame;
            if (l == null || s != l.ctx.id) return null;
            if (r == Lane.FRAME) return l.ctx.released ? null : f;
            int id = l.nameIn(r);
            return id < 0 ? null : Name.byId(id);
        }

        /**
         * The span's start, in nanoseconds from the trace's start: where it opened, or {@link #start(long)}
         * put it. 0 on {@link Tempo#NOOP}, outside a trace, once released, and on a folded or undrawn span
         * once it closed. Any thread, best effort off the owner.
         */
        @Override
        public long startNanos() {
            long s = (long) STAMP.getAcquire(this);
            Lane l = lane;
            int r = row;
            if (l == null || l.free || s != l.ctx.id) return 0;
            return r == Lane.FRAME ? l.frameStart(this) : l.startIn(r);
        }

        /** Whether this span records into a trace: not {@link #NOOP}, not a span timed outside a trace, not pooled. */
        public boolean recording() {
            Lane l = lane;
            return l != null && !l.free && l != Lane.POOLED;
        }

        /**
         * Ends the span and any span still open above it (flagged healed, counted as misuse). Closing a
         * {@link Trace} again does nothing.
         */
        @Override
        public void close() {
            Lane l = lane;
            if (l != null && Thread.currentThread() == owner && depth < l.depth && l.handles[depth] == this) l.close(depth);
            else if (!(this instanceof Trace) || Thread.currentThread() != owner) live(); // counts the misuse
        }


        /** Owner thread and still open. */
        private boolean live() {
            Lane l = lane;
            if (Thread.currentThread() == owner && depth < l.depth && l.handles[depth] == this) return true;
            if (l != null) misuse(Thread.currentThread() != owner ? "span used on a foreign thread" : stale() ? STALE : "span used after close");
            return false;
        }

        /** Owner thread, and open or closed in a trace not released; else counts the misuse. */
        private boolean owned() {
            Lane l = lane;
            if (Thread.currentThread() != owner) {
                if (l != null) misuse("span used on a foreign thread");
                return false;
            }
            if (depth < l.depth && l.handles[depth] == this || !stale()) return true;
            misuse(STALE);
            return false;
        }
    }

    /**
     * A labelled section of a span's timeline, from {@link Span#phase}: it ends at the span's next phase,
     * at {@link #done()} or {@link #lap()}, or when the span ends. It opens nothing; {@link #phase}, {@link
     * #done()}, {@link #lap()} and the hand-timing calls on it continue its span's sequence, and {@link
     * #done()} returns the span. {@link #set}, {@link #fail()} and {@link #label} apply to the phase itself,
     * also after it ended, until the trace is released.
     *
     * <p>Like a span's, a recorded phase's handle is its own for the whole trace and pooled by the thread
     * once the trace is released; a call through it then is ignored and counted as misuse ({@link
     * Options#strict} throws, and never pools one). The phases of a folded or undrawn span, which draws
     * none, share one handle per depth: they count towards the totals but keep no attributes.
     */
    public static final class Phase extends Timing {
        private static final VarHandle STAMP;

        static {
            try {
                STAMP = MethodHandles.lookup().findVarHandle(Phase.class, "stamp", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        /** The phase of {@link Tempo#NOOP}: records nothing; its {@link #done()} returns {@link Tempo#NOOP}. */
        public static final Phase NOOP = new Phase();

        // The binding, as for Span: written by the owner when it issues the handle and by a retiring lane when
        // the handle goes back to the pool; the stamp is the trace's id while issued, 0 while pooled.
        Lane lane;
        Context ctx = Context.NONE;
        Thread owner;
        AtomicInteger misuses = Context.NONE.misuses;
        Span span; // the span this phase sections
        int row = Lane.FRAME; // FRAME: no row, a phase of a folded or undrawn span or past the row cap
        @SuppressWarnings("unused") private long stamp; // via STAMP
        Phase link; // the next handle in its lane's issued list, or in its slot's pool

        Phase() {}

        /** Owner thread: issues this handle to phase row {@code r} (or FRAME) of {@code s}, open at depth {@code d} of {@code l}. */
        void bind(Lane l, Span s, int d, int r) {
            Context c = l.ctx;
            lane = l;
            ctx = c;
            owner = l.owner;
            misuses = c.misuses;
            span = s;
            row = r;
            STAMP.setRelease(this, c.id);
        }

        /** The lane retiring: takes the handle off its trace before it is pooled, as {@link Span#detach()}. */
        void detach() {
            STAMP.setOpaque(this, 0L);
            VarHandle.storeStoreFence();
            lane = Lane.POOLED;
            ctx = Context.NONE;
            span = null;
        }

        private boolean stale() {
            return lane.retired() || (long) STAMP.getAcquire(this) != ctx.id;
        }

        /** The scope of the span this phase sections, whose strictness and names it uses. */
        private Trace scope() {
            Span s = span;
            return s != null ? s.scope : ctx.root;
        }

        private void misuse(String what) {
            misuses.incrementAndGet();
            if (scope().strict) throw new AssertionError("Tempo misuse: " + what);
        }

        /** Owner thread and not stale; else counts the misuse. False for {@link #NOOP}. */
        private boolean owned() {
            if (lane == null) return false;
            if (Thread.currentThread() != owner) {
                misuse("phase used on a foreign thread");
                return false;
            }
            if (!stale()) return true;
            misuse(Span.STALE);
            return false;
        }

        /** The span this phase sections; {@link Tempo#NOOP} for {@link #NOOP} or a stale handle (misuse). */
        private Span span() {
            if (lane == null) return Tempo.NOOP;
            if (!stale()) return span;
            misuse(Span.STALE);
            return Tempo.NOOP;
        }

        /** Attaches a number to this phase, also after it ended; ignored on a phase without a row. */
        @Override
        public Phase set(Name key, long value) {
            if (owned() && row != Lane.FRAME && !lane.event(row, scope().attrKey(key) << KIND_BITS | ATTR_LONG, value)) misuse(Span.STALE);
            return this;
        }

        /** Like {@link #set(Name, long)}, the key looked up as for {@link Tempo#span(String)}. */
        @Override
        public Phase set(String key, long value) {
            return lane == null || lane.free ? this : set(scope().nameOf(key), value);
        }

        @Override
        public Phase set(Name key, double value) {
            if (owned() && row != Lane.FRAME
                    && !lane.event(row, scope().attrKey(key) << KIND_BITS | ATTR_DOUBLE, Double.doubleToRawLongBits(value))) misuse(Span.STALE);
            return this;
        }

        /** Like {@link #set(Name, double)}, the key looked up as for {@link Tempo#span(String)}. */
        @Override
        public Phase set(String key, double value) {
            return lane == null || lane.free ? this : set(scope().nameOf(key), value);
        }

        /** Marks the phase failed, also after it ended. */
        @Override
        public Phase fail() {
            if (owned() && row != Lane.FRAME && !lane.flag(row, FAILED)) misuse(Span.STALE);
            return this;
        }

        /** Like {@link #fail()}; only the flag is recorded, not the throwable. */
        @Override
        public Phase fail(Throwable cause) {
            return fail();
        }

        /**
         * The text of this phase in the {@code Runtime} and {@code Server-Timing} headers ({@link
         * Trace#toRuntime(int)}, {@link Trace#toServerTiming()}), which list the trace's labelled phases.
         * Also after the phase ended; a second label replaces the first. Null is misuse.
         */
        public Phase label(String text) {
            if (!owned() || row == Lane.FRAME) return this;
            if (text == null) misuse("null label");
            else if (!lane.label(row, text)) misuse(Span.STALE);
            return this;
        }

        /** The span's next phase: {@link Span#phase(Name)}. */
        public Phase phase(Name name) {
            return span().phase(name);
        }

        /** The span's next phase: {@link Span#phase(String)}. */
        public Phase phase(String name) {
            return span().phase(name);
        }

        /** Ends the span's open phase: {@link Span#done()}. Returns the span. */
        public Span done() {
            return span().done();
        }

        /** Like {@link #done()}. */
        public Span lap() {
            return span().done();
        }

        /** {@link Span#done(Name)}. Returns the span. */
        public Span done(Name name) {
            return span().done(name);
        }

        /** {@link Span#done(String)}. Returns the span. */
        public Span done(String name) {
            return span().done(name);
        }

        /** Like {@link #done(Name)}. */
        public Span lap(Name name) {
            return span().done(name);
        }

        /** Like {@link #done(String)}. */
        public Span lap(String name) {
            return span().done(name);
        }

        /** Ends the span's open phase at {@code nanos}: {@link Span#lap(long)}. Returns the span. */
        public Span lap(long nanos) {
            return span().lap(nanos);
        }

        /** Starts the span at {@code nanos}, this phase with it while it is the first: {@link Span#start(long)}. Returns this phase. */
        public Phase start(long nanos) {
            span().start(nanos);
            return this;
        }

        /** Ends the span, and this phase if open, at {@code nanos}: {@link Span#stop(long)}. Returns the span. */
        public Span stop(long nanos) {
            return span().stop(nanos);
        }

        /**
         * Running time while open, the duration once ended; 0 for a phase without a row and after release.
         * Any thread, best effort off the owner.
         */
        @Override
        public long elapsedNanos() {
            long s = (long) STAMP.getAcquire(this);
            Lane l = lane;
            int r = row;
            if (l == null || r == Lane.FRAME || s != l.ctx.id) return 0;
            return l.elapsed(r);
        }

        /** The phase's name; null for a phase without a row, on {@link #NOOP} and after release. Any thread. */
        @Override
        public Name name() {
            long s = (long) STAMP.getAcquire(this);
            Lane l = lane;
            int r = row;
            if (l == null || r == Lane.FRAME || s != l.ctx.id) return null;
            int id = l.nameIn(r);
            return id < 0 ? null : Name.byId(id);
        }

        /** The phase's start, in nanoseconds from the trace's start; 0 for a phase without a row and after release. Any thread. */
        @Override
        public long startNanos() {
            long s = (long) STAMP.getAcquire(this);
            Lane l = lane;
            int r = row;
            if (l == null || r == Lane.FRAME || s != l.ctx.id) return 0;
            return l.startIn(r);
        }
    }

    // ---------------------------------------------------------------------------------------------

    @FunctionalInterface
    public interface SpanVisitor {
        void visit(SpanView span);
    }

    /** Receives the labelled phases of a trace, see {@link Trace#forEachTop}. */
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
        long start, duration, wait, waitMax, cpu, count, min, max, extent, maxAt;
        long folded; // of count, the occurrences folded for want of budget
        boolean open, live, handoff, begun, aggregate, mixed;
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
         * Spans of one name folded into one entry, per parent and lap: steps and ticks past the budget, and
         * spans in a folded task, per lane; or the tasks of handoffs past the budget, from whichever threads ran
         * them, which make it a {@link #handoff()} with queue waits. Its start is the first occurrence's (a
         * handoff's submit), its flags those every occurrence has; it has no attributes.
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
         * A {@link Task} drawn at its submit: it was submitted at {@link #startNanos()},
         * waited {@link #waitNanos()} and ran for {@link #durationNanos()} on {@link #thread()}; its
         * children are the task's spans. A task begun on the submitting thread itself is one too.
         */
        public boolean handoff() { return handoff; }

        /** A handoff whose task has not begun (yet): no thread ran it, nor cancelled it; for an aggregate, none. */
        public boolean neverStarted() { return handoff && !begun; }

        /**
         * Queue wait of a handoff, submit to begin (or cancel); still counting if it never started. For an
         * aggregate of handoffs the sum over its occurrences. 0 for other spans.
         */
        public long waitNanos() { return wait; }

        /** The longest queue wait of an aggregate of handoffs; for a handoff its wait. */
        public long waitMaxNanos() { return waitMax; }

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

        /**
         * Recorded through a misused call, e.g. with a null name, a {@code done} that names another phase,
         * or a time given by hand outside its window.
         */
        public boolean misuse() { return (flags & MISUSE) != 0; }

        /**
         * A phase of its parent ({@link Span#phase}, or {@link Span#done(Name)} naming the time since the
         * last boundary): a section of the parent's timeline, never the parent of anything.
         */
        public boolean phase() { return (flags & PHASE) != 0; }

        /** The label of a phase ({@link Phase#label}), or {@code null}. */
        public String label() { return label; }

        /** {@link #SPAN}, {@link #STEP} or {@link #TICK}: the word that opened it. */
        public int level() {
            return (flags & TICK_ROW) != 0 ? TICK : (flags & STEP_ROW) != 0 ? STEP : SPAN;
        }

        /**
         * The thread that ran the span; for a handoff that never started, the submitting thread; null for an
         * aggregate whose occurrences ran on several threads ({@link #mixedThreads()}).
         */
        public String thread() { return mixed ? null : shape.thread; }

        /** An aggregate whose occurrences ran on more than one thread. */
        public boolean mixedThreads() { return mixed; }

        /** Attributes; a handoff has those set at submit ({@link Task#set}) first, then its task's. */
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
     * Work submitted on one thread to run on another, made by {@link Tempo#task}, {@link Tempo#runnable},
     * {@link Tempo#callable}, {@link Tempo#supplier} or a {@link Tempo#executor}. Its parent is the
     * innermost open span of the thread that made it, and its queue wait runs from then until it begins.
     * A task runs at most once and is never reused, so a straggler cannot record into another request.
     *
     * <p>Like a {@link Span#step step}, a task is always counted: its run time and queue wait go to the
     * totals ({@link Trace#total}) and listeners of its name. It is drawn when the trace records steps
     * ({@link Trace#records(int)}), against the budget: a record at its submit, with its run on the thread
     * that ran it; past the budget, an aggregate. Otherwise nothing is written at the submit, and spans
     * opened inside the task are drawn under the span it was submitted from, on the thread that ran it.
     *
     * <p>Begin and {@link #cancel()} race for the task, and exactly one of them wins, inside a trace or
     * not: {@code cancel()} returns {@code true} when the body will not run. So an early exit stops what
     * has not begun with no lock and no help from the executor:
     *
     * <pre>{@code
     * for (Tempo.RunnableTask t : pending) t.cancel(); // true: it will not run, and is recorded cancelled
     * }</pre>
     *
     * A task made by {@link Tempo#task} has no body: a hand-written worker begins it with {@link #tryBegin()}.
     * {@link #set} and {@link #fail()} apply to its record at the submit: on the submitting thread, while
     * the trace is not released, and only on a drawn task that is not folded.
     */
    public static class Task extends Timing {
        // state: row << ROW | flag bits | NEW, BEGUN or ENDED (cancelled or rejected), with the state of the
        // Future of a TaskFuture in FUTURE. row: the task's record at the submit in lane; folded, ~ its TASKS
        // entry; UNDRAWN, the row in lane that spans inside it hang from, or -1.
        static final int NEW = 0, BEGUN = 1, ENDED = 2, STATES = 3, UNDRAWN = 1 << 2,
                FUTURE = 3, FUTURE_MASK = 7 << FUTURE, WAITERS = 1 << 6, QUIET = 1 << 7, ROW = 8;
        private static final VarHandle STATE;

        static {
            try {
                STATE = MethodHandles.lookup().findVarHandle(Task.class, "state", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        // Written by the submitting thread before the task is handed on; null lane: outside a trace.
        Lane lane;
        Name name;
        long submit;
        volatile int state;
        // Folded: the next older Task in the submitting lane's waiting list (its scope is its TASKS entry's).
        // Drawn or undrawn: the scope it was submitted in, the Trace of the innermost self-contained block or
        // the trace's own root, which it carries to the lane where it runs. One field for both, so a task
        // stays one object of the size it had before blocks.
        Timing next;
        long wait;  // the queue wait, once it began or was cancelled; best effort off that thread

        /** A task of {@code name}, or of {@code text} looked up as for {@link Tempo#span(String)}. */
        Task(Name name, String text, int bits) {
            this(SLOT.get().current, name, text, bits);
        }

        /** A task submitted under the innermost open span of {@code l}, the calling thread's current lane. */
        Task(Lane l, Name name, String text, int bits) {
            if (l != null && !l.ctx.released) {
                l.submit(this, name, text, bits);
            } else {
                this.name = text != null ? Name.find(text) : name; // only a global listener could hear it
                STATE.set(this, bits); // published with the task itself, so no fence
            }
        }

        /** Lane.submit: the record at the submit, in scope {@code scope}. */
        void init(Lane l, int row, int bits, long now, Trace scope) {
            lane = l;
            submit = now;
            next = scope;
            STATE.set(this, row << ROW | bits);
        }

        /** The scope the task was submitted in, in a trace; of a folded one, its TASKS entry's. */
        Trace scope() {
            int s = state;
            return (s & UNDRAWN) != 0 || s >> ROW >= 0 ? (Trace) next : lane.taskScope(~(s >> ROW));
        }

        int row() {
            return state >> ROW;
        }

        boolean undrawn() {
            return (state & UNDRAWN) != 0;
        }

        boolean hasBody() {
            return false;
        }

        /** Neither begun nor cancelled (nor rejected) yet; any thread. */
        boolean waiting() {
            return (state & STATES) == NEW;
        }

        private void misuse(String what) {
            Lane l = lane;
            if (l != null) scope().misuse(what);
        }

        /**
         * Begins the task on the calling thread and returns its span, which the caller closes; {@code null}
         * if it was cancelled or rejected first, or began before (misuse, strict mode throws), so the work is
         * skipped. The span is {@link Tempo#NOOP} when nothing is recorded: outside a trace (unless a global
         * listener hears the name, see {@link Tempo#on}), after release, at a cap. On a thread already inside
         * the trace (caller runs) the span is a child of that thread's innermost open span, otherwise the
         * root of the thread's lane; {@link Tempo#current()} returns it until it closes.
         *
         * <pre>{@code
         * Tempo.Span s = task.tryBegin();
         * if (s == null) return; // cancelled before it began: recorded so, nothing to do
         * try (s) {
         *     work();
         * }
         * }</pre>
         *
         * A task with a body begins when it runs; calling this on it is misuse and returns {@code null}.
         */
        public Span tryBegin() {
            if (hasBody()) {
                misuse("tryBegin on a task with a body");
                return null;
            }
            return begin();
        }

        /** {@link #tryBegin()} without the body check: null unless this call won the task. */
        final Span begin() {
            int s;
            do {
                s = state;
                if ((s & STATES) != NEW) {
                    if ((s & STATES) == BEGUN) misuse("task begun twice");
                    return null;
                }
            } while (!STATE.compareAndSet(this, s, s | BEGUN));
            Lane l = lane;
            if (l == null) return name != null && name.listened() ? SLOT.get().free(name) : NOOP;
            return l.ctx.released ? NOOP : record(true);
        }

        /**
         * Cancels the task before it begins, e.g. on an early exit; any thread. Returns {@code true} if
         * this call won the race with the begin: the body will not run, the task is recorded as cancelled
         * before it began, with its queue wait until now, and the future of a task that is one completes as
         * cancelled. {@code false} if it changed nothing: the task has begun (it runs and is recorded as it
         * runs), or it was cancelled or rejected already. Works outside a trace and after release too, where
         * only the recording is left out.
         */
        public boolean cancel() {
            return end();
        }

        /** Marks the task rejected by its executor; returns, and is recorded, like {@link #cancel()}. */
        public boolean reject() {
            return end();
        }

        private boolean end() {
            int s;
            do {
                s = state;
                if ((s & STATES) != NEW) return false;
            } while (!STATE.compareAndSet(this, s, s | ENDED));
            try {
                Lane l = lane;
                if (l != null && !l.ctx.released) record(false);
            } finally {
                ended();
            }
            return true;
        }

        /** Called once a cancel or reject won the task. */
        void ended() {}

        /** Called right after a begin or cancel won the task: the clock read first ends the queue wait. */
        private Span record(boolean run) {
            Context ctx = lane.ctx;
            long now = ctx.now(); // before the lane lookup: a thread's first task sets its lane up, which is run time
            wait = Math.max(0, now - submit);
            Lane l = laneHere(ctx);
            Span s = l == null ? null : l.task(this, run, now);
            if (s == null) ctx.lost(this, l == null);
            return s == null ? NOOP : s;
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

        /** Attaches a number to the task's record at the submit; see {@link Task}. */
        public Task set(Name key, long value) {
            return event(key, ATTR_LONG, value);
        }

        /** Like {@link #set(Name, long)}, the key looked up as for {@link Tempo#span(String)}. */
        public Task set(String key, long value) {
            Lane l = lane;
            return l == null ? this : set(scope().nameOf(key), value);
        }

        public Task set(Name key, double value) {
            return event(key, ATTR_DOUBLE, Double.doubleToRawLongBits(value));
        }

        /** Like {@link #set(Name, double)}, the key looked up as for {@link Tempo#span(String)}. */
        public Task set(String key, double value) {
            Lane l = lane;
            return l == null ? this : set(scope().nameOf(key), value);
        }

        private Task event(Name key, int kind, long value) {
            Lane l = lane;
            int s = state, r = s >> ROW;
            if (l == null || (s & UNDRAWN) != 0 || r < 0) return this; // nothing recorded at the submit
            if (Thread.currentThread() != l.owner) misuse("task used on a foreign thread");
            else if (!l.event(r, scope().attrKey(key) << KIND_BITS | kind, value)) misuse(Span.STALE);
            return this;
        }

        /** Marks the task's record at the submit failed; see {@link Task}. The task's own span is marked when its body throws. */
        public Task fail() {
            Lane l = lane;
            int s = state, r = s >> ROW;
            if (l == null || (s & UNDRAWN) != 0 || r < 0) return this;
            if (Thread.currentThread() != l.owner) misuse("task used on a foreign thread");
            else if (!l.flag(r, FAILED)) misuse(Span.STALE);
            return this;
        }

        /** Like {@link #fail()}; only the flag is recorded, not the throwable. */
        public Task fail(Throwable cause) {
            return fail();
        }

        /** The task's name; any thread. */
        @Override
        public Name name() {
            return name;
        }

        /** The submit, in nanoseconds from the trace's start; 0 outside a trace and after release. Any thread. */
        @Override
        public long startNanos() {
            Lane l = lane;
            return l == null || l.ctx.released ? 0 : submit;
        }

        /**
         * The time the task object measures: its queue wait, {@link #waitNanos()}. Its run is timed by its
         * span, which {@link Tempo#current()} returns inside the body.
         */
        @Override
        public long elapsedNanos() {
            return waitNanos();
        }

        /**
         * The queue wait: up to now while the task waits, from its submit until it began or was cancelled
         * once it did. 0 outside a trace and after release. Any thread.
         */
        public long waitNanos() {
            Lane l = lane;
            if (l == null || l.ctx.released) return 0;
            return waiting() ? Math.max(0, l.ctx.now() - submit) : wait;
        }

        /** Whether a {@link #begin()} that returned null lost to an earlier begin (misuse), not to a cancel. */
        final boolean begunBefore() {
            return (state & STATES) == BEGUN;
        }

        static CancellationException cancelled() {
            return new CancellationException("Tempo task cancelled before it began");
        }

        @SuppressWarnings("unchecked")
        static <T extends Throwable> T sneaky(Throwable t) throws T {
            throw (T) t;
        }

        /** After the constructor has recorded the submit: a null body is misuse there (strict mode throws). */
        final void checkBody(Object body) {
            if (body == null) misuse("null task body");
        }
    }

    /**
     * A task with a body that is also its {@link Future}, as a {@link java.util.concurrent.FutureTask} is:
     * the body runs at most once. A throwable from the body marks the task's span failed, completes the
     * future with it, and is thrown on to the caller, as from the plain body (a {@link Tempo#executor}'s
     * {@code submit} keeps it in the future only, as {@code ExecutorService.submit} does). The span closes
     * before the future completes, so whoever waits on it sees the task ended in the trace. {@link #cancel(boolean)} first cancels the task ({@link Task#cancel()}); once the
     * task has begun it cancels only the future, as a {@code FutureTask} does, and the span is marked
     * cancelled when it ends.
     */
    abstract static class TaskFuture<V> extends Task implements Future<V> {
        // The Future's state, in Task's FUTURE bits; as java.util.concurrent.FutureTask's.
        static final int PENDING = 0, COMPLETING = 1, NORMAL = 2, EXCEPTIONAL = 3, CANCELLED = 4,
                INTERRUPTING = 5, INTERRUPTED = 6;

        private Object outcome; // the result or the throwable; read after the state says so
        volatile Thread runner; // while it runs the body, for cancel(true)

        TaskFuture(Name name, String text, int bits) {
            super(name, text, bits);
        }

        TaskFuture(Lane l, Name name, int bits) {
            super(l, name, null, bits);
        }

        abstract V body() throws Exception;

        /**
         * The result of a body without one, from an executor's {@code submit(task, result)}: kept in the
         * outcome until the body ends. Before the task has begun only.
         */
        final void preset(Object result) {
            if (waiting() && future() == PENDING) outcome = result;
        }

        @Override
        final boolean hasBody() {
            return true;
        }

        /** Begins the task and runs the body, unless it was cancelled or rejected first, or began before. */
        final void exec() {
            Span s = begin();
            if (s == null) return;
            runner = Thread.currentThread();
            Object o = null;
            int f = NORMAL;
            try {
                if (future() == PENDING) o = body(); // a cancel of the future may have come since the begin
                if (o == null) o = outcome;          // a result preset by submit(task, result), or null
            } catch (Throwable t) {
                s.fail();
                o = t;
                f = EXCEPTIONAL;
            }
            finish(s, f, o);
            if (f == EXCEPTIONAL && (state & QUIET) == 0) throw Task.<RuntimeException>sneaky((Throwable) o);
        }

        /** Ends span {@code s} of the body, then completes the future with outcome {@code o}. */
        final void finish(Span s, int f, Object o) {
            try {
                if (future() >= CANCELLED) s.cancel(); // the future was cancelled while the body ran
                s.close();
            } finally {
                complete(f, o);
                runner = null;
                while (future() == INTERRUPTING) Thread.yield(); // let cancel(true) interrupt this task, not the next
            }
        }

        final int future() {
            return (state & FUTURE_MASK) >>> FUTURE;
        }

        private void complete(int f, Object o) {
            if (!future(PENDING, COMPLETING)) return;
            outcome = o;
            future(COMPLETING, f);
            wake();
        }

        /** Keeps a throwable of the body in the future only: {@code ExecutorService.submit} does so. */
        final void quiet() {
            int s;
            do {
                s = state;
            } while ((s & QUIET) == 0 && !Task.STATE.compareAndSet(this, s, s | QUIET));
        }

        /** Moves the future from {@code from} to {@code to}; false if it was not in {@code from}. */
        private boolean future(int from, int to) {
            int s;
            do {
                s = state;
                if ((s & FUTURE_MASK) >>> FUTURE != from) return false;
            } while (!Task.STATE.compareAndSet(this, s, s & ~FUTURE_MASK | to << FUTURE));
            return true;
        }

        /** Wakes the threads in {@link #get()}; they set WAITERS before they read the state, this reads it after. */
        private void wake() {
            if ((state & WAITERS) != 0) {
                synchronized (this) {
                    notifyAll();
                }
            }
        }

        @Override
        void ended() {
            if (future(PENDING, CANCELLED)) wake();
        }

        /**
         * Cancels the task if it has not begun ({@link Task#cancel()}): then the body will not run. Otherwise,
         * as a {@code FutureTask} does, cancels the future while the body runs (interrupting it if {@code
         * mayInterruptIfRunning}), whose result is then dropped and whose span is marked cancelled; {@code false}
         * once the future is done.
         */
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (cancel()) return true;
            if (!future(PENDING, mayInterruptIfRunning ? INTERRUPTING : CANCELLED)) return false;
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null) t.interrupt();
                } finally {
                    future(INTERRUPTING, INTERRUPTED);
                }
            }
            wake();
            return true;
        }

        @Override
        public boolean isCancelled() {
            return future() >= CANCELLED;
        }

        @Override
        public boolean isDone() {
            return future() != PENDING;
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            int f = future();
            if (f <= COMPLETING) f = await(false, 0L);
            return report(f);
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            if (unit == null) throw new NullPointerException();
            int f = future();
            if (f <= COMPLETING && (f = await(true, unit.toNanos(timeout))) <= COMPLETING) throw new TimeoutException();
            return report(f);
        }

        private int await(boolean timed, long nanos) throws InterruptedException {
            long deadline = timed ? System.nanoTime() + nanos : 0L;
            synchronized (this) {
                int s;
                do {
                    s = state;
                } while ((s & WAITERS) == 0 && !Task.STATE.compareAndSet(this, s, s | WAITERS));
                int f;
                while ((f = future()) <= COMPLETING) {
                    if (!timed) {
                        wait();
                    } else {
                        long left = deadline - System.nanoTime();
                        if (left <= 0L) return f;
                        TimeUnit.NANOSECONDS.timedWait(this, left);
                    }
                }
                return f;
            }
        }

        @SuppressWarnings("unchecked")
        private V report(int f) throws ExecutionException {
            if (f == NORMAL) return (V) outcome;
            if (f >= CANCELLED) throw new CancellationException();
            throw new ExecutionException((Throwable) outcome);
        }
    }

    /**
     * A {@link Runnable} task from {@link Tempo#runnable}, and its {@link Future} (see {@link Task}): hand it
     * to an executor's {@code execute} and keep it as the future, to its {@code submit}, or to a {@link
     * Tempo#executor}, which returns it from {@code submit}. A throwable from the body completes the future
     * with it and is thrown on from {@link #run()}, as from the plain runnable.
     */
    public static final class RunnableTask extends TaskFuture<Void> implements RunnableFuture<Void> {
        private final Runnable body;

        RunnableTask(Name name, String text, Runnable body, int bits) {
            super(name, text, bits);
            this.body = body;
            checkBody(body);
        }

        /** Tests: a task submitted in lane {@code l} (null: outside a trace), as if it were the thread's current lane. */
        RunnableTask(Lane l, Name name, Runnable body) {
            super(l, name, 0);
            this.body = body;
            checkBody(body);
        }

        @Override
        Void body() {
            if (body != null) body.run();
            return null;
        }

        /**
         * Begins the task and runs the body, unless it was cancelled or rejected first, or began before
         * (misuse): as a {@code FutureTask}'s, the body runs at most once.
         */
        @Override
        public void run() {
            exec();
        }
    }

    /**
     * A {@link Callable} task from {@link Tempo#callable}, and its {@link Future} (see {@link Task}): hand it
     * to an executor's {@code submit}, which calls {@link #call()}, or to a {@link Tempo#executor}, which
     * runs it as it is and returns it from {@code submit}, {@code invokeAll} and {@code invokeAny}. The
     * future completes with the body's result or throwable. Its type is not a {@link Runnable}, so {@code
     * submit} takes it as a {@code Callable}.
     */
    public abstract static class CallableTask<V> extends TaskFuture<V> implements Callable<V> {
        private final Callable<V> body;

        CallableTask(Name name, String text, Callable<V> body, int bits) {
            super(name, text, bits);
            this.body = body;
            checkBody(body);
        }

        @Override
        V body() throws Exception {
            return body == null ? null : body.call();
        }

        /**
         * Begins the task and calls the body; a throwable marks the span failed and is rethrown. Throws
         * {@link CancellationException} without calling the body if the task was cancelled or rejected
         * first. A second call is misuse (strict mode throws); it calls the body again, unrecorded, and
         * leaves the future as the first call completed it.
         */
        @Override
        public V call() throws Exception {
            Span s = begin();
            if (s == null) {
                if (!begunBefore()) throw cancelled();
                return body();
            }
            runner = Thread.currentThread();
            V v;
            try {
                v = body();
            } catch (Throwable t) {
                s.fail();
                finish(s, EXCEPTIONAL, t);
                throw t;
            }
            finish(s, NORMAL, v);
            return v;
        }
    }

    /**
     * A {@link Supplier} task from {@link Tempo#supplier}, e.g. for {@code CompletableFuture.supplyAsync}.
     * {@link #get()} begins the task and gets the body's value; a throwable marks the span failed and is
     * rethrown. It is not a {@link Future}: the {@code CompletableFuture} is.
     */
    public static final class SupplierTask<V> extends Task implements Supplier<V> {
        private final Supplier<V> body;

        SupplierTask(Name name, String text, Supplier<V> body) {
            super(name, text, 0);
            this.body = body;
            checkBody(body);
        }

        @Override
        boolean hasBody() {
            return true;
        }

        /**
         * Begins the task and gets the body's value. Throws {@link CancellationException} without calling
         * the body if the task was cancelled or rejected first. A second call is misuse (strict mode
         * throws); it gets the body's value again, unrecorded.
         */
        @Override
        public V get() {
            Span s = begin();
            if (s == null) {
                if (!begunBefore()) throw cancelled();
                return body == null ? null : body.get();
            }
            try {
                return body == null ? null : body.get();
            } catch (Throwable t) {
                s.fail();
                throw t;
            } finally {
                s.close();
            }
        }
    }

    /**
     * The class of every {@link CallableTask}: also a {@link RunnableFuture}, so a {@link Tempo#executor}
     * hands it to the pool's {@code execute} as it is. Its static type stays {@code CallableTask}, which is
     * not a {@code Runnable}, so {@code pool.submit(task)} is not ambiguous.
     */
    static final class CallTask<V> extends CallableTask<V> implements RunnableFuture<V> {
        CallTask(Name name, String text, Callable<V> body, int bits) {
            super(name, text, body, bits);
        }

        @Override
        public void run() {
            exec();
        }
    }

    /** The task of an executor's {@code submit(Runnable, result)}: a runnable body with a set result. */
    static final class ResultTask<V> extends TaskFuture<V> implements RunnableFuture<V> {
        private final Runnable body;
        private final V result;

        ResultTask(Name name, Runnable body, V result) {
            super(name, null, QUIET);
            this.body = body;
            this.result = result;
            checkBody(body);
        }

        @Override
        V body() {
            if (body != null) body.run();
            return result;
        }

        @Override
        public void run() {
            exec();
        }
    }

    /**
     * {@link Tempo#executor}: every task submitted to it is a task of its name, handed to the pool's
     * {@code execute} as its own {@link Future}, so a submit allocates that one object. A task made by
     * {@link Tempo#runnable} or {@link Tempo#callable} goes in as it is, under its own name. A submit the
     * pool refuses is recorded as rejected before the {@link RejectedExecutionException} reaches the
     * caller. {@code invokeAll} goes through {@link #newTaskFor}. So does an {@code
     * ExecutorCompletionService} over this executor, its own in {@code invokeAny} too: it wraps the task in
     * a future of its own and hands that to {@link #execute} right away, on the same thread, which passes
     * it to the pool as it is, so every element is recorded once, under its task's name.
     */
    static final class TaskExecutor extends AbstractExecutorService {
        private final ExecutorService pool;
        private final Name name;

        TaskExecutor(ExecutorService pool, Name name) {
            this.pool = pool;
            this.name = name;
        }

        /**
         * Runs {@code r} as a task; a throwable from it is thrown on the pool thread, as from a plain
         * runnable. The completion wrapper of the task {@link #newTaskFor} just made on this thread goes to
         * the pool as it is: that task is the one recorded, and rejected if the pool refuses the wrapper.
         */
        @Override
        public void execute(Runnable r) {
            ThreadSlot slot = SLOT.get();
            TaskFuture<?> made = slot.made;
            if (made != null) slot.made = null;
            if (ours(r)) {
                start((RunnableFuture<?>) r);
            } else if (made != null && r instanceof Future) {
                try {
                    pool.execute(r);
                } catch (RejectedExecutionException e) {
                    made.reject();
                    throw e;
                }
            } else {
                start(new RunnableTask(name, null, r, 0));
            }
        }

        /** Submits {@code r} as a task, which is the future returned; a throwable from it stays in the future. */
        @Override
        public Future<?> submit(Runnable r) {
            return start(task(r, null));
        }

        @Override
        public <T> Future<T> submit(Runnable r, T result) {
            return start(task(r, result));
        }

        @Override
        public <T> Future<T> submit(Callable<T> c) {
            return start(task(c));
        }

        /** A task that is its own future and runnable: one made by {@link Tempo#runnable}, {@link Tempo#callable}, or this executor. */
        private static boolean ours(Object r) {
            return r instanceof TaskFuture && r instanceof RunnableFuture;
        }

        /** {@code r} itself if it is a task (its throwable kept in the future, {@code result} its result), else a new task. */
        @SuppressWarnings("unchecked")
        private <T> RunnableFuture<T> task(Runnable r, T result) {
            if (!ours(r)) return result == null ? (RunnableFuture<T>) new RunnableTask(name, null, r, Task.QUIET)
                    : new ResultTask<>(name, r, result);
            TaskFuture<?> t = (TaskFuture<?>) r;
            t.quiet();
            if (result != null) t.preset(result);
            return (RunnableFuture<T>) r;
        }

        /** {@code c} itself if it is a task (its throwable kept in the future), else a new task. */
        @SuppressWarnings("unchecked")
        private <T> RunnableFuture<T> task(Callable<T> c) {
            if (!ours(c)) return new CallTask<>(name, null, c, Task.QUIET);
            ((TaskFuture<?>) c).quiet();
            return (RunnableFuture<T>) c;
        }

        /** As the JDK's; a timed call that gives up before executing every task leaves none remembered. */
        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException {
            try {
                return super.invokeAll(tasks, timeout, unit);
            } finally {
                SLOT.get().made = null;
            }
        }

        /** Like {@link #task(Runnable, Object)}; remembered for the {@link #execute} that follows. */
        @Override
        protected <T> RunnableFuture<T> newTaskFor(Runnable r, T result) {
            return made(task(r, result));
        }

        /** Like {@link #task(Callable)}; remembered for the {@link #execute} that follows. */
        @Override
        protected <T> RunnableFuture<T> newTaskFor(Callable<T> c) {
            return made(task(c));
        }

        private static <T> RunnableFuture<T> made(RunnableFuture<T> t) {
            SLOT.get().made = (TaskFuture<?>) t;
            return t;
        }

        private <F extends Runnable> F start(F t) {
            try {
                pool.execute(t);
            } catch (RejectedExecutionException e) {
                if (t instanceof Task) ((Task) t).reject();
                throw e;
            }
            return t;
        }

        @Override
        public void shutdown() {
            pool.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return pool.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return pool.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return pool.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return pool.awaitTermination(timeout, unit);
        }

        @Override
        public String toString() {
            return "Tempo.executor(" + pool + ", " + (name == null ? null : name.text) + ")";
        }

        /** The pool of an executor made over a null one: runs every task at once, on the submitting thread. */
        static final class Inline extends AbstractExecutorService {
            private volatile boolean shutdown;

            @Override
            public void execute(Runnable r) {
                if (shutdown) throw new RejectedExecutionException("shut down");
                r.run();
            }

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public List<Runnable> shutdownNow() {
                shutdown = true;
                return new ArrayList<>();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return shutdown;
            }
        }
    }

    /**
     * Tempo-Link (FORMAT.md section 9): {@code <base>#t=z.<base64url(raw DEFLATE(value))>}. The default base
     * is baked into the jar at build time as a resource, from the repository the build runs in, so a
     * fork's jar points at the fork's viewer; a copy of this file without the resource uses
     * {@link #DEFAULT_LINK_BASE}.
     */
    static final class Link {
        /** The viewer a link points at when neither the build, the options nor {@code -Dtempo.linkBase} name one. */
        static final String DEFAULT_LINK_BASE = "https://dualuse.github.io/tempo/";
        static final String RESOURCE = "link-base.txt"; // next to Tempo.class

        /** The base the library was built with, read once when a link first needs it. */
        static final class Built {
            static final String BASE = read(Tempo.class.getResourceAsStream(RESOURCE));
        }

        /** {@code -Dtempo.linkBase} when it is a valid base, else the base the library was built with. */
        static String base() {
            String p = System.getProperty("tempo.linkBase");
            return p != null && valid(p) ? p : Built.BASE;
        }

        /** The base in a resource (closed here), or {@link #DEFAULT_LINK_BASE} if there is none or it is not valid. */
        static String read(InputStream resource) {
            try (InputStream in = resource) {
                String b = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                return valid(b) ? b : DEFAULT_LINK_BASE;
            } catch (IOException | RuntimeException e) {
                return DEFAULT_LINK_BASE;
            }
        }

        /** {@code base}, or the default for null. */
        static String base(String base, String fallback) {
            if (base == null) return fallback != null ? fallback : base();
            if (!valid(base)) throw new IllegalArgumentException("not a Tempo-Link base: " + base);
            return base;
        }

        /** An absolute URL of printable ASCII without {@code #} or {@code ,}. */
        static boolean valid(String base) {
            int colon = base.indexOf(':');
            if (colon < 1 || !isAsciiLetter(base.charAt(0))) return false;
            for (int i = 0; i < base.length(); i++) {
                char c = base.charAt(i);
                if (c <= ' ' || c >= 0x7F || c == '#' || c == ',') return false;
                if (i < colon && !isAsciiLetter(c) && !(c >= '0' && c <= '9') && c != '+' && c != '-' && c != '.') return false;
            }
            return true;
        }

        private static boolean isAsciiLetter(char c) {
            return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
        }

        /** The link to {@code value} at {@code base}; empty for an empty value. */
        static String of(String base, String value) {
            if (value.isEmpty()) return "";
            try (Packer p = new Packer()) {
                p.pack(value);
                return p.link(base);
            }
        }

        /** Packs values with one raw DEFLATE stream at level 9, reset for each; ended on close. */
        static final class Packer implements AutoCloseable {
            private final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
            private byte[] in = new byte[0], out = new byte[256];
            private int n; // bytes in out

            /** Compresses {@code value} (ASCII); returns the length of {@code #t=z.} and the payload. */
            int pack(CharSequence value) {
                int len = value.length();
                if (in.length < len) in = new byte[Math.max(len, 2 * in.length)];
                for (int i = 0; i < len; i++) in[i] = (byte) value.charAt(i);
                deflater.reset();
                deflater.setInput(in, 0, len);
                deflater.finish();
                n = 0;
                while (!deflater.finished()) {
                    if (n == out.length) out = Arrays.copyOf(out, 2 * n);
                    n += deflater.deflate(out, n, out.length - n);
                }
                return 5 + (4 * n + 2) / 3; // base64 without padding
            }

            /** {@code base} with the value last packed. */
            String link(String base) {
                return base + "#t=z." + Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(out, n));
            }

            @Override
            public void close() {
                deflater.end();
            }
        }
    }

    /**
     * A trace's totals of one name ({@link Trace#total}): count, sum, mean and extremes of its timed
     * occurrences in nanoseconds, and the sum and maximum of its tasks' queue waits. Zero when there were none.
     */
    public static final class Total {
        static final Total EMPTY = new Total(0, 0, 0, 0, 0, 0);

        private final long count, sum, min, max, waitSum, waitMax;

        Total(long count, long sum, long min, long max, long waitSum, long waitMax) {
            this.count = count;
            this.sum = sum;
            this.min = min;
            this.max = max;
            this.waitSum = waitSum;
            this.waitMax = waitMax;
        }

        public long count() { return count; }

        public long sum() { return sum; }

        /** {@link #sum()} / {@link #count()}, 0 when nothing was counted. */
        public long mean() { return count == 0 ? 0 : sum / count; }

        public long min() { return min; }

        public long max() { return max; }

        /** Queue waits of the name's tasks, begun or cancelled; independent of {@link #count()}. */
        public long waitSum() { return waitSum; }

        public long waitMax() { return waitMax; }

        @Override
        public String toString() {
            return "count=" + count + " sum=" + sum + " mean=" + mean() + " min=" + min + " max=" + max
                    + " waitSum=" + waitSum + " waitMax=" + waitMax;
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
