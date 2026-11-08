package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.List;

/** State and side effects proposed by one controller for a single logical step. */
public record ControlFrame<S>(
        S nextState,
        List<PlaybackCommand> commands,
        List<ControlEvent> events
) {

    public ControlFrame {
        commands = List.copyOf(commands);
        events = List.copyOf(events);
    }
}
