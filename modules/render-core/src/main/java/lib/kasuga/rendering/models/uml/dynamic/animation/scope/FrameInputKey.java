package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/**
 * Typed key for frame-domain values that do not participate in logical animation state.
 *
 * @param <T> value type
 */
public record FrameInputKey<T>(String id, Class<T> type) {

    public FrameInputKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (id.isBlank()) {
            throw new IllegalArgumentException("frame input id must not be blank");
        }
    }
}
