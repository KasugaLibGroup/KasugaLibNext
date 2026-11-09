package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/** One immutable logical time transition produced by a root timeline plan. */
public record TemporalStep(
        double previousTime,
        double currentTime,
        double deltaSeconds,
        ScopeAdvanceCause cause,
        TemporalDiscontinuity discontinuity
) {

    public TemporalStep {
        if (!Double.isFinite(previousTime) || !Double.isFinite(currentTime)
                || !Double.isFinite(deltaSeconds)) {
            throw new IllegalArgumentException("temporal values must be finite");
        }
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(discontinuity, "discontinuity");
    }
}
