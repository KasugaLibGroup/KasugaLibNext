package lib.kasuga.rendering.models.uml.dynamic.proxy;

import org.jetbrains.annotations.Nullable;

/**
 * Result of one host tick submission.
 *
 * <p>{@code advanced} means at least one logical step committed and can be true together with
 * {@code aborted} when a post-commit writeback or visual boundary failed.
 */
public record ProxyTickOutcome(
        ProxyLifecycleState state,
        boolean advanced,
        long revision,
        boolean aborted,
        @Nullable RuntimeException failure
) {

    public ProxyTickOutcome {
        state = java.util.Objects.requireNonNull(state, "state");
        if (!aborted && failure != null) {
            throw new IllegalArgumentException("a successful tick cannot carry a failure");
        }
    }
}
