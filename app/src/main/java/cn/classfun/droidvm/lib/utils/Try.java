package cn.classfun.droidvm.lib.utils;

import androidx.annotation.Nullable;

/**
 * One implementation's attempt within a degradation ladder: which implementation
 * ran, plus its return value on success or its failure reason otherwise. The pair
 * {@code (value | error, impl)} is what a feature call accumulates into an ordered
 * {@code List<Try<...>>}; a success stops the ladder, so the caller judges the
 * overall outcome from the <b>last</b> element:
 * <ul>
 *   <li>last {@link #ok()} and alone -- success via the primary implementation;</li>
 *   <li>last {@link #ok()} after earlier fails -- success, degraded to a fallback;</li>
 *   <li>last not {@link #ok()} -- failure, and every rung's reason is in the list.</li>
 * </ul>
 *
 * @param <I> the feature's implementation enum (which way it was done)
 * @param <V> the value produced on success ({@link Void} for pure actions)
 */
public final class Try<I, V> {
    /** The implementation this attempt used. */
    public final I impl;
    /** The value produced on success; {@code null} on failure (or for {@link Void}). */
    @Nullable
    public final V value;
    /** The failure reason; {@code null} on success. */
    @Nullable
    public final String error;

    private Try(I impl, @Nullable V value, @Nullable String error) {
        this.impl = impl;
        this.value = value;
        this.error = error;
    }

    public static <I, V> Try<I, V> ok(I impl, @Nullable V value) {
        return new Try<>(impl, value, null);
    }

    public static <I, V> Try<I, V> fail(I impl, String error) {
        return new Try<>(impl, null, error);
    }

    /** True if this implementation succeeded (a non-null error means failure). */
    public boolean ok() {
        return error == null;
    }
}
