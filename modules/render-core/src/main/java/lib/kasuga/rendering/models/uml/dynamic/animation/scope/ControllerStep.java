package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.List;
import java.util.Objects;

final class ControllerStep {

    private final ControlFrame<?> frame;
    private final Runnable stateCommitter;
    private final ControllerCheckpoint checkpoint;

    ControllerStep(
            ControlFrame<?> frame,
            Runnable stateCommitter,
            ControllerCheckpoint checkpoint
    ) {
        this.frame = Objects.requireNonNull(frame, "frame");
        this.stateCommitter = Objects.requireNonNull(stateCommitter, "stateCommitter");
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
    }

    List<PlaybackCommand> commands() {
        return frame.commands();
    }

    List<ControlEvent> events() {
        return frame.events();
    }

    void commit() {
        checkpoint.commit();
        stateCommitter.run();
    }

    void discard() {
        checkpoint.rollback();
    }
}
