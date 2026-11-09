package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.List;
import java.util.Objects;

/** Immutable input to one controller during one pre-commit logical step. */
public record ControlEvaluationRequest<S>(
        TemporalStep timeStep,
        ScopeInputSnapshot inputs,
        S state,
        List<PlaybackView> activePlaybacks,
        List<PlaybackEvent> playbackEvents,
        List<PlaybackTimeResolution> playbackTimes
) {

    public ControlEvaluationRequest(
            TemporalStep timeStep,
            ScopeInputSnapshot inputs,
            S state,
            List<PlaybackView> activePlaybacks,
            List<PlaybackEvent> playbackEvents
    ) {
        this(timeStep, inputs, state, activePlaybacks, playbackEvents, List.of());
    }

    public ControlEvaluationRequest {
        Objects.requireNonNull(timeStep, "timeStep");
        Objects.requireNonNull(inputs, "inputs");
        activePlaybacks = List.copyOf(Objects.requireNonNull(activePlaybacks, "activePlaybacks"));
        playbackEvents = List.copyOf(Objects.requireNonNull(playbackEvents, "playbackEvents"));
        playbackTimes = List.copyOf(Objects.requireNonNull(playbackTimes, "playbackTimes"));
    }
}
