package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.ArrayList;
import java.util.List;

/** Staged controller outputs for one logical step. */
public final class ControllerRound {

    private final List<ControllerStep> steps;
    private boolean committed;
    private boolean discarded;

    ControllerRound(List<ControllerStep> steps) {
        this.steps = List.copyOf(steps);
    }

    public List<PlaybackCommand> commands() {
        List<PlaybackCommand> commands = new ArrayList<>();
        for (ControllerStep step : steps) {
            commands.addAll(step.commands());
        }
        return List.copyOf(commands);
    }

    public List<ControlEvent> events() {
        List<ControlEvent> events = new ArrayList<>();
        for (ControllerStep step : steps) {
            events.addAll(step.events());
        }
        return List.copyOf(events);
    }

    public void commit() {
        if (committed || discarded) {
            return;
        }
        steps.forEach(ControllerStep::commit);
        committed = true;
    }

    public void discard() {
        if (committed || discarded) {
            return;
        }
        for (int index = steps.size() - 1; index >= 0; index--) {
            steps.get(index).discard();
        }
        discarded = true;
    }
}
