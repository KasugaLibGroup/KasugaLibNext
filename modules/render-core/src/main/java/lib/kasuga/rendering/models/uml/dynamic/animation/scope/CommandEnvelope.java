package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/** Stable ordering and idempotency metadata for one playback command. */
public record CommandEnvelope(
        String requestId,
        CommandPriority priority,
        int originOrdinal,
        int commandOrdinal,
        PlaybackCommand command
) {

    public CommandEnvelope {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(command, "command");
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (originOrdinal < 0 || commandOrdinal < 0) {
            throw new IllegalArgumentException("ordinals must be non-negative");
        }
    }
}
