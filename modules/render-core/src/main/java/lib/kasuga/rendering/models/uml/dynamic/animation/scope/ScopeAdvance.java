package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/** One platform-neutral time submission to an animation scope. */
public record ScopeAdvance(double rawDeltaSeconds, ScopeAdvanceCause cause) {

    public ScopeAdvance {
        if (!Double.isFinite(rawDeltaSeconds) || rawDeltaSeconds < 0.0) {
            throw new IllegalArgumentException("rawDeltaSeconds must be finite and non-negative");
        }
        Objects.requireNonNull(cause, "cause");
    }
}
