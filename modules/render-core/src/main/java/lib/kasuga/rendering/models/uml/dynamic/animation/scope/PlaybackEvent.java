package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/** Immutable playback event observed during a logical step. */
public record PlaybackEvent(
        PlaybackInstanceId instanceId,
        String eventId,
        long occurrenceSequence
) {

    public PlaybackEvent {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(eventId, "eventId");
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (occurrenceSequence <= 0) {
            throw new IllegalArgumentException("occurrenceSequence must be positive");
        }
    }
}
