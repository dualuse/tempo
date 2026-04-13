package io.github.dualuse.tempo;

/**
 * A non-closeable view over a {@link Scope} for phase chaining.
 *
 * <p>Methods on this interface return the same underlying {@link Scope} instance
 * (typed as {@code Lap}), allowing fluent chains like
 * {@code s.done("A").next("B")} without exposing {@link AutoCloseable#close()}.
 */
public interface Lap {

    /**
     * Start a new phase with the given name. Ends the currently open phase
     * (if any) before starting the new one.
     *
     * @throws IllegalStateException if the scope is already closed
     */
    Lap phase(String name);

    /** Alias for {@link #phase(String)} for readable chains. */
    Lap next(String name);

    /**
     * End the currently open phase, assigning or verifying its name.
     *
     * <p>If the phase was previously started via {@link #phase(String)} or
     * {@link #next(String)} with a different name, this method throws
     * {@link IllegalStateException}. If the phase was started implicitly
     * (at scope entry or after a previous {@code done}), the given name is
     * assigned to it.
     *
     * @throws IllegalStateException if the name mismatches an already-named phase
     *         or the scope is already closed
     */
    Lap done(String name);
}
