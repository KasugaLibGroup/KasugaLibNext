package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/** Immutable semantic event emitted by a controller. */
public record ControlEvent(String typeId) {

    public ControlEvent {
        Objects.requireNonNull(typeId, "typeId");
        if (typeId.isBlank()) {
            throw new IllegalArgumentException("control event typeId must not be blank");
        }
    }
}
