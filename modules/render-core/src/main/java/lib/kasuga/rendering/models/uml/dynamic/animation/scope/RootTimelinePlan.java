package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.List;
import java.util.Objects;

/** Staged root timeline result. Nothing is authoritative until {@link RootTimeline#commit} succeeds. */
public record RootTimelinePlan(
        List<TemporalStep> steps,
        RootTimelineSnapshot snapshot,
        int droppedSteps
) {

    public RootTimelinePlan {
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        Objects.requireNonNull(snapshot, "snapshot");
        if (droppedSteps < 0) {
            throw new IllegalArgumentException("droppedSteps must be non-negative");
        }
    }
}
