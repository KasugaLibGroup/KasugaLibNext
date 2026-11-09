package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.List;
import java.util.Objects;

/** Immutable state committed by one successful logical step. */
public record ScopeSnapshot(
        long revision,
        TemporalStep timeStep,
        RootTimelineSnapshot rootTimeline,
        ScopeInputSnapshot inputs,
        List<PlaybackTimeResolution> playbacks
) {

    public ScopeSnapshot {
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(timeStep, "timeStep");
        Objects.requireNonNull(rootTimeline, "rootTimeline");
        Objects.requireNonNull(inputs, "inputs");
        playbacks = List.copyOf(Objects.requireNonNull(playbacks, "playbacks"));
    }
}
