package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/** Immutable root timeline state after a plan is committed. */
public record RootTimelineSnapshot(
        double previousTime,
        double currentTime,
        double accumulatorSeconds,
        boolean paused,
        boolean closed
) {

    public RootTimelineSnapshot {
        if (!Double.isFinite(previousTime) || !Double.isFinite(currentTime)
                || !Double.isFinite(accumulatorSeconds)) {
            throw new IllegalArgumentException("timeline values must be finite");
        }
        if (previousTime < 0.0 || currentTime < 0.0 || accumulatorSeconds < 0.0) {
            throw new IllegalArgumentException("timeline values must be non-negative");
        }
    }
}
