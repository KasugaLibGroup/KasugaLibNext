package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.List;
import java.util.Objects;

/** Shared pre-step context for every controller in one evaluation round. */
public record ControllerEvaluationContext(
        TemporalStep timeStep,
        ScopeInputSnapshot inputs,
        List<PlaybackView> activePlaybacks,
        List<PlaybackEvent> playbackEvents,
        List<PlaybackTimeResolution> playbackTimes
) {

    public ControllerEvaluationContext(
            TemporalStep timeStep,
            ScopeInputSnapshot inputs,
            List<PlaybackView> activePlaybacks,
            List<PlaybackEvent> playbackEvents
    ) {
        this(timeStep, inputs, activePlaybacks, playbackEvents, List.of());
    }

    public ControllerEvaluationContext {
        Objects.requireNonNull(timeStep, "timeStep");
        Objects.requireNonNull(inputs, "inputs");
        activePlaybacks = List.copyOf(activePlaybacks);
        playbackEvents = List.copyOf(playbackEvents);
        playbackTimes = List.copyOf(playbackTimes);
    }
}
