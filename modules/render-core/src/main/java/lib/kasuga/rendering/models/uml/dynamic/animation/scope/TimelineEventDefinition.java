package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/** One event at a local source offset. */
public record TimelineEventDefinition(String eventId, double sourceOffsetSeconds) {

    public TimelineEventDefinition {
        Objects.requireNonNull(eventId, "eventId");
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (!Double.isFinite(sourceOffsetSeconds) || sourceOffsetSeconds < 0.0) {
            throw new IllegalArgumentException(
                    "sourceOffsetSeconds must be finite and non-negative");
        }
    }
}
