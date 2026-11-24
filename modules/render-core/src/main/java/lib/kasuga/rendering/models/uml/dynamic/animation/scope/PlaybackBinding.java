package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/**
 * Immutable mapping from a placement window on the parent timeline to a source window on the
 * child timeline. A placement may start before the current root time; this lets a controller
 * resume a clip from a committed midpoint without inventing a second clock.
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
        requireFinite(placementStart, "placementStart");
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

    public PlaybackBinding withPlacementStart(double newPlacementStart) {
        return new PlaybackBinding(
                newPlacementStart,
                placementDuration,
                sourceStart,
                sourceDuration,
                speed,
                boundaryPolicy);
    }

    public PlaybackBinding withSourceStart(double newSourceStart) {
        return new PlaybackBinding(
                placementStart,
                placementDuration,
                newSourceStart,
                sourceDuration,
                speed,
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

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }
}
