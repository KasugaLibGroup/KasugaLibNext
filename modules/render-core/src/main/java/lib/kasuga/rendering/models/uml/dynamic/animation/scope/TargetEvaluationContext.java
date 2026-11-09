package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.List;
import java.util.Objects;

/**
 * Immutable state read by target evaluators after a scope step has committed.
 *
 * <p>The context contains logical state only. Render interpolation and platform frame data are
 * deliberately absent.
 */
public record TargetEvaluationContext(
        long revision,
        TemporalStep timeStep,
        RootTimelineSnapshot rootTimeline,
        ScopeInputSnapshot inputs,
        List<PlaybackView> activePlaybacks,
        List<PlaybackTimeResolution> playbackTimes
) {

    public TargetEvaluationContext(
            long revision,
            TemporalStep timeStep,
            RootTimelineSnapshot rootTimeline,
            ScopeInputSnapshot inputs,
            List<PlaybackView> activePlaybacks
    ) {
        this(revision, timeStep, rootTimeline, inputs, activePlaybacks, List.of());
    }

    public TargetEvaluationContext {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        Objects.requireNonNull(timeStep, "timeStep");
        Objects.requireNonNull(rootTimeline, "rootTimeline");
        Objects.requireNonNull(inputs, "inputs");
        activePlaybacks = List.copyOf(Objects.requireNonNull(activePlaybacks, "activePlaybacks"));
        playbackTimes = List.copyOf(Objects.requireNonNull(playbackTimes, "playbackTimes"));
    }
}
