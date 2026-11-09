package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/**
 * Immutable mapping from a placement window on the parent timeline to a source window on the
 * child timeline.
 *
 * <p>The two ranges and the speed are independent. This type validates their values but never
 * derives one field from another.
 */
public record PlaybackBinding(
        double placementStart,
        double placementDuration,
        double sourceStart,
        double sourceDuration,
        double speed,
        PlaybackBoundaryPolicy boundaryPolicy
) {

    public PlaybackBinding {
        requireFiniteNonNegative(placementStart, "placementStart");
        requirePositiveFinite(placementDuration, "placementDuration");
        requireFiniteNonNegative(sourceStart, "sourceStart");
        requirePositiveFinite(sourceDuration, "sourceDuration");
        if (!Double.isFinite(speed) || speed < 0.0) {
            throw new IllegalArgumentException("speed must be finite and non-negative");
        }
        Objects.requireNonNull(boundaryPolicy, "boundaryPolicy");
    }

    public double placementEnd() {
        return placementStart + placementDuration;
    }

    public double sourceEnd() {
        return sourceStart + sourceDuration;
    }

    public PlaybackBinding withSpeed(double newSpeed) {
        return new PlaybackBinding(
                placementStart,
                placementDuration,
                sourceStart,
                sourceDuration,
                newSpeed,
                boundaryPolicy);
    }

    public PlaybackBinding withBoundaryPolicy(PlaybackBoundaryPolicy newBoundaryPolicy) {
        return new PlaybackBinding(
                placementStart,
                placementDuration,
                sourceStart,
                sourceDuration,
                speed,
                newBoundaryPolicy);
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }
}
