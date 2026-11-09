package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Optional;

/** Observable result of one command application. */
public record CommandApplyResult(Optional<PlaybackInstanceId> assignedInstanceId) {

    public CommandApplyResult {
        assignedInstanceId = assignedInstanceId == null ? Optional.empty() : assignedInstanceId;
    }

    public static CommandApplyResult accepted() {
        return new CommandApplyResult(Optional.empty());
    }

    public static CommandApplyResult created(PlaybackInstanceId id) {
        return new CommandApplyResult(Optional.of(id));
    }
}
