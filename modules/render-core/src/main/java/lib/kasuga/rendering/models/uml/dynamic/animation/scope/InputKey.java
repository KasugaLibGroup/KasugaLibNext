package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/** Stable, typed key for one runtime animation input. */
public record InputKey<T>(String id, Class<T> type) {

    public InputKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (id.isBlank()) {
            throw new IllegalArgumentException("input key id must not be blank");
        }
    }
}
