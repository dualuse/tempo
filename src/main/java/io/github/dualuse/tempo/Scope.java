package io.github.dualuse.tempo;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * A timed node in a Tempo tree, structured as a linked DAG.
 *
 * <p>Each {@code Scope} links to its {@linkplain #firstChild first child},
 * {@linkplain #nextSibling next sibling}, and {@linkplain #parent parent},
 * forming a left-child/right-sibling tree with no collection objects.
 *
 * <p>Scopes are created through {@link Tempo#scope(String)} or instance
 * methods like {@link #scope(String)}. They are {@link AutoCloseable} and
 * intended for use with try-with-resources.
 */
public class Scope implements AutoCloseable, Lap, Iterable<Scope> {

    // --- VarHandle for lock-free child append ---
    private static final VarHandle LAST_CHILD;
    static {
        try {
            LAST_CHILD = MethodHandles.lookup()
                .findVarHandle(Scope.class, "lastChild", Scope.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // --- Immutable state ---
    private final String name;
    private final long startNanos;
    private final long threadId;
    private final String threadName;
    protected final Scope parent;                     // package-private, null for root

    // --- Mutable timing state ---
    protected volatile long endNanos;                 // 0 = open, >0 = closed
    private volatile Scope openChild;       // currently open child (from phase())
    private volatile long lastPhaseEnd;     // end of the last phase (or startNanos)

    // --- Linked DAG structure ---
    private volatile Scope firstChild;
    @SuppressWarnings("unused") // accessed via LAST_CHILD VarHandle
    private volatile Scope lastChild;
    private volatile Scope nextSibling;

    /** Scope constructor — sets Tempo.CURRENT. Used by Tempo.scope(), scope(), fork(). */
    Scope(String name, Scope parent) {
        this.name = name;
        this.parent = parent;
        this.threadId = Thread.currentThread().getId();
        this.threadName = Thread.currentThread().getName();
        this.startNanos = System.nanoTime();
        this.lastPhaseEnd = startNanos;

        if (parent != null) {
            parent.addChild(this);
        }

        Tempo.CURRENT.set(this);
    }

    /** Phase constructor — does NOT touch Tempo.CURRENT. Used by phase(), done(). */
    private Scope(String name, Scope parent, long startNanos) {
        this.name = name;
        this.parent = parent;
        this.threadId = Thread.currentThread().getId();
        this.threadName = Thread.currentThread().getName();
        this.startNanos = startNanos;
        this.lastPhaseEnd = startNanos;

        if (parent != null) {
            parent.addChild(this);
        }
    }

    /** Lock-free append via VarHandle.getAndSet. */
    private void addChild(Scope child) {
        Scope prev = (Scope) LAST_CHILD.getAndSet(this, child);
        if (prev == null) {
            firstChild = child;
        } else {
            prev.nextSibling = child;
        }
    }

    // ---------------------------------------------------------------
    // Tree & timing getters
    // ---------------------------------------------------------------

    public String getName() {
        return name;
    }

    public long getStartNanos() {
        return startNanos;
    }

    public long getEndNanos() {
        return endNanos;
    }

    public long getElapsedNanos() {
        long end = (endNanos == 0L) ? System.nanoTime() : endNanos;
        return end - startNanos;
    }

    public Duration getElapsed() {
        return Duration.ofNanos(getElapsedNanos());
    }

    public long getThreadId() {
        return threadId;
    }

    public String getThreadName() {
        return threadName;
    }

    /**
     * The root of the scope tree (walks up parent pointers).
     * Intentionally not a {@code getXxx} so Jackson does not try to serialize it.
     */
    public Scope root() {
        Scope s = this;
        while (s.parent != null) {
            s = s.parent;
        }
        return s;
    }

    /** Snapshot of direct children as a list (built on demand). */
    public List<Scope> getChildren() {
        List<Scope> result = new ArrayList<>();
        for (Scope c = firstChild; c != null; c = c.nextSibling) {
            result.add(c);
        }
        return result;
    }

    /** Iterates over direct children via the linked sibling chain. */
    @Override
    public Iterator<Scope> iterator() {
        return new Iterator<>() {
            Scope cursor = firstChild;

            @Override public boolean hasNext() {
                return cursor != null;
            }

            @Override public Scope next() {
                if (cursor == null) throw new NoSuchElementException();
                Scope c = cursor;
                cursor = cursor.nextSibling;
                return c;
            }
        };
    }

    // ---------------------------------------------------------------
    // Phase methods (Lap interface)
    // ---------------------------------------------------------------

    @Override
    public Lap phase(String name) {
        checkNotClosed();
        long now = System.nanoTime();
        endOpenChild(now);
        Scope child = new Scope(name, this, now);
        openChild = child;
        return this;
    }

    @Override
    public Lap next(String name) {
        return phase(name);
    }

    @Override
    public Lap done(String name) {
        checkNotClosed();
        long now = System.nanoTime();

        if (openChild != null) {
            if (!openChild.name.equals(name)) {
                throw new IllegalStateException(
                    "Phase was started as '" + openChild.name
                    + "' but done() called with '" + name + "'");
            }
            openChild.endNanos = now;
            openChild = null;
        } else {
            Scope child = new Scope(name, this, lastPhaseEnd);
            child.endNanos = now;
        }
        lastPhaseEnd = now;
        return this;
    }

    private void endOpenChild(long now) {
        if (openChild != null) {
            openChild.endNanos = now;
            openChild = null;
        }
        lastPhaseEnd = now;
    }

    // ---------------------------------------------------------------
    // Sub-scope methods
    // ---------------------------------------------------------------

    /** Create a child scope. Use in try-with-resources. */
    public Scope scope(String name) {
        checkNotClosed();
        return new Scope(name, this);
    }

    /** Create a child scope and run body inside it, closing on exit. */
    public void scope(String name, Runnable body) {
        try (Scope s = scope(name)) {
            body.run();
        }
    }

    /** Create a child scope, run body, return the result. Wraps checked exceptions. */
    public <T> T scope(String name, Callable<T> body) {
        try (Scope s = scope(name)) {
            return body.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------------
    // Fork methods
    // ---------------------------------------------------------------

    public Future<?> fork(String name, ExecutorService exec, Runnable body) {
        final Scope parentScope = this;
        return exec.submit(() -> {
            try (Scope s = new Scope(name, parentScope)) {
                body.run();
            }
        });
    }

    public <T> Future<T> fork(String name, ExecutorService exec, Callable<T> body) {
        final Scope parentScope = this;
        return exec.submit(() -> {
            try (Scope s = new Scope(name, parentScope)) {
                return body.call();
            }
        });
    }

    public Future<?> fork(String name, ExecutorService exec, ScopedRunnable body) {
        final Scope parentScope = this;
        return exec.submit(() -> {
            try (Scope s = new Scope(name, parentScope)) {
                body.execute(s);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public <T> Future<T> fork(String name, ExecutorService exec, ScopedCallable<T> body) {
        final Scope parentScope = this;
        return exec.submit(() -> {
            try (Scope s = new Scope(name, parentScope)) {
                try {
                    return body.execute(s);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    // ---------------------------------------------------------------
    // Output methods
    // ---------------------------------------------------------------

    /**
     * Human-readable flat timing report.
     * Format: {@code TOTALs (NAME) = c1s (n1) + c2s (n2) + ...}
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(formatSeconds(getElapsedNanos()))
          .append("s (").append(name).append(")");
        if (firstChild != null) {
            sb.append(" = ");
            boolean first = true;
            for (Scope c = firstChild; c != null; c = c.nextSibling) {
                if (!first) sb.append(" + ");
                sb.append(formatSeconds(c.getElapsedNanos()))
                  .append("s (").append(c.name).append(")");
                first = false;
            }
        }
        return sb.toString();
    }

    /**
     * Deep Server-Timing header value. Every descendant becomes a metric with a
     * dot-separated path name. Crosses thread boundaries transparently.
     */
    public String toServerTiming() {
        StringBuilder sb = new StringBuilder();
        boolean[] first = {true};
        for (Scope c = firstChild; c != null; c = c.nextSibling) {
            emitDeepMetrics(sb, c, "", first);
        }
        return sb.toString();
    }

    private static void emitDeepMetrics(StringBuilder sb, Scope node, String prefix, boolean[] first) {
        String segment = percentEncodeToken(node.name);
        String path = prefix.isEmpty() ? segment : prefix + "." + segment;
        if (!first[0]) sb.append(',');
        first[0] = false;
        sb.append(path).append(";dur=").append(formatSeconds(node.getElapsedNanos()));
        for (Scope c = node.firstChild; c != null; c = c.nextSibling) {
            emitDeepMetrics(sb, c, path, first);
        }
    }

    /**
     * Per-thread-lane JSON. Top-level keys are {@code <threadName>:<threadId>},
     * each containing the scope sub-tree that ran on that thread.
     */
    public String toJSON() {
        LinkedHashMap<String, List<Scope>> byThread = new LinkedHashMap<>();
        collectThreadEntryPoints(this, byThread);

        StringBuilder sb = new StringBuilder("{");
        boolean firstThread = true;
        for (var entry : byThread.entrySet()) {
            if (!firstThread) sb.append(',');
            firstThread = false;
            sb.append(jsonString(entry.getKey())).append(":{");
            boolean firstScope = true;
            for (Scope ep : entry.getValue()) {
                if (!firstScope) sb.append(',');
                firstScope = false;
                sb.append(jsonString(percentEncodePathSegment(ep.name))).append(':');
                appendLaneScope(sb, ep);
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    private static void collectThreadEntryPoints(Scope node, LinkedHashMap<String, List<Scope>> byThread) {
        if (node.parent == null || node.threadId != node.parent.threadId) {
            String key = node.threadName + ":" + node.threadId;
            byThread.computeIfAbsent(key, k -> new ArrayList<>()).add(node);
        }
        for (Scope c = node.firstChild; c != null; c = c.nextSibling) {
            collectThreadEntryPoints(c, byThread);
        }
    }

    private static void appendLaneScope(StringBuilder sb, Scope scope) {
        // Collect first and last same-thread children for ^ and $ calculation
        Scope firstSame = null, lastSame = null;
        boolean hasSameThread = false;
        for (Scope c = scope.firstChild; c != null; c = c.nextSibling) {
            if (c.threadId == scope.threadId) {
                if (firstSame == null) firstSame = c;
                lastSame = c;
                hasSameThread = true;
            }
        }

        if (!hasSameThread) {
            sb.append(formatSeconds(scope.getElapsedNanos()));
        } else {
            long scopeEnd = scope.endNanos == 0L ? System.nanoTime() : scope.endNanos;
            long lastEnd = lastSame.endNanos == 0L ? System.nanoTime() : lastSame.endNanos;

            sb.append("{\"^\":").append(formatSeconds(firstSame.startNanos - scope.startNanos));
            for (Scope c = scope.firstChild; c != null; c = c.nextSibling) {
                if (c.threadId == scope.threadId) {
                    sb.append(',').append(jsonString(c.name)).append(':');
                    appendLaneScope(sb, c);
                }
            }
            sb.append(",\"$\":").append(formatSeconds(scopeEnd - lastEnd));
            sb.append("}");
        }
    }

    // ---------------------------------------------------------------
    // AutoCloseable
    // ---------------------------------------------------------------

    @Override
    public void close() {
        if (endNanos != 0L) return; // idempotent
        long now = System.nanoTime();
        endOpenChild(now);
        endNanos = now;
        if (parent != null && parent.threadId == this.threadId) {
            Tempo.CURRENT.set(parent);
        } else {
            Tempo.CURRENT.remove();
        }
    }

    private void checkNotClosed() {
        if (endNanos != 0L) {
            throw new IllegalStateException("Scope '" + name + "' is already closed");
        }
    }

    // ---------------------------------------------------------------
    // Formatting helpers
    // ---------------------------------------------------------------

    private static String formatSeconds(long nanos) {
        return String.valueOf(nanos / 1e9);
    }

    // RFC 7230 token chars excluding '.' (our path separator) and '%' (our escape prefix)
    private static boolean isTokenChar(char c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return true;
        return "!#$&'*+-^_`|~".indexOf(c) >= 0;
    }

    /** Percent-encode for Server-Timing metric names. Encodes all non-token chars plus '.' and '%'. */
    private static String percentEncodeToken(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (isTokenChar(c)) {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", (int) c));
            }
        }
        return sb.toString();
    }

    /** Percent-encode for JSON path-segment keys. Only encodes '/' and '%'. */
    private static String percentEncodePathSegment(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '%') {
                sb.append('%').append(String.format("%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ---------------------------------------------------------------
    // Functional interfaces for fork()
    // ---------------------------------------------------------------

    @FunctionalInterface
    public interface ScopedRunnable {
        void execute(Scope scope) throws Exception;
    }

    @FunctionalInterface
    public interface ScopedCallable<T> {
        T execute(Scope scope) throws Exception;
    }
}
