package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;
import java.util.Optional;

/**
 * Local time derived for one playback instance during one logical step.
 *
 * <p>Unwrapped source time is retained for event scanning. Source local time is the value passed
 * to content sampling after the selected boundary policy.
 */
public record PlaybackTimeResolution(
        PlaybackInstanceId instanceId,
        String definitionId,
        Optional<PlaybackInstanceId> parentId,
        double previousParentLocalTime,
        double currentParentLocalTime,
        double previousSourceLocalTime,
        double currentSourceLocalTime,
        double previousUnwrappedSourceTime,
        double currentUnwrappedSourceTime,
        boolean active,
        long loopCrossings,
        int direction,
        boolean completed
) {

    public PlaybackTimeResolution {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(parentId, "parentId");
        requireFinite(previousParentLocalTime, "previousParentLocalTime");
        requireFinite(currentParentLocalTime, "currentParentLocalTime");
        requireFinite(previousSourceLocalTime, "previousSourceLocalTime");
        requireFinite(currentSourceLocalTime, "currentSourceLocalTime");
        requireFinite(previousUnwrappedSourceTime, "previousUnwrappedSourceTime");
        requireFinite(currentUnwrappedSourceTime, "currentUnwrappedSourceTime");
        if (loopCrossings < 0) {
            throw new IllegalArgumentException("loopCrossings must be non-negative");
        }
        if (direction != -1 && direction != 1) {
            throw new IllegalArgumentException("direction must be -1 or 1");
        }
    }

    public PlaybackInstanceId id() {
        return instanceId;
    }

    public double previousParentLocal() {
        return previousParentLocalTime;
    }

    public double currentParentLocal() {
        return currentParentLocalTime;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
