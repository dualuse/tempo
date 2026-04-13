package io.github.dualuse.tempo;

/**
 * Static entry point for Tempo profiling.
 *
 * <p>This class is not instantiable — it holds a per-thread scope reference and
 * exposes static factory methods for creating root and nested scopes.
 *
 * <p>Typical usage:
 * <pre>{@code
 * try (Scope s = Tempo.scope("POST /compute")) {
 *     s.phase("init");
 *     // work...
 *     s.phase("load");
 *     // work...
 * }
 * }</pre>
 */
public final class Tempo {

    /** Innermost open scope on the current thread, or {@code null}. */
    static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private Tempo() {}

    /**
     * Create a new scope. If a scope is currently active on this thread, the new
     * scope becomes its child. Otherwise, the new scope becomes a root.
     */
    public static Scope scope(String name) {
        Scope parent = CURRENT.get();
        return new Scope(name, parent);
    }

    /**
     * Create a new scope as a child of the given parent. Used when crossing
     * threads, where the thread-local context does not carry the parent.
     */
    public static Scope scope(String name, Scope parent) {
        return new Scope(name, parent);
    }
}
