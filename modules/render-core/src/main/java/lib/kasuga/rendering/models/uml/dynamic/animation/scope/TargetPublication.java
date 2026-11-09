package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/**
 * Immutable result of evaluating one target for one committed scope revision.
 *
 * @param <O> target-specific immutable output type
 */
public record TargetPublication<O>(
        AnimationTargetRef ref,
        long revision,
        OutputState<O> outputState) {

    public TargetPublication {
        Objects.requireNonNull(ref, "ref");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        Objects.requireNonNull(outputState, "outputState");
    }
}
