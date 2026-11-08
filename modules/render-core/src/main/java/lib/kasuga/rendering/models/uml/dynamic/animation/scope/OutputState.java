package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The output state carried by one target publication.
 *
 * @param <O> target-specific immutable output type
 */
public sealed interface OutputState<O> permits OutputState.Active, OutputState.Cleared {

    /**
     * A live output that may interpolate from the previous logical revision.
     */
    record Active<O>(
            @Nullable O previousOutput,
            O currentOutput,
            Continuity continuity) implements OutputState<O> {

        public Active {
            Objects.requireNonNull(currentOutput, "currentOutput");
            Objects.requireNonNull(continuity, "continuity");
        }
    }

    /**
     * Explicit removal of this target's output.
     */
    record Cleared<O>(String reason) implements OutputState<O> {

        public Cleared {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("clear reason must not be blank");
            }
        }
    }
}
