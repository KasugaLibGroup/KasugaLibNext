package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;
import java.util.Optional;

/** One applied, rejected or reused command result. */
public record CommandResult(
        String requestId,
        Optional<PlaybackInstanceId> assignedInstanceId,
        String diagnostic
) {

    public CommandResult {
        Objects.requireNonNull(requestId, "requestId");
        assignedInstanceId = assignedInstanceId == null ? Optional.empty() : assignedInstanceId;
        diagnostic = diagnostic == null ? "" : diagnostic;
    }
}
