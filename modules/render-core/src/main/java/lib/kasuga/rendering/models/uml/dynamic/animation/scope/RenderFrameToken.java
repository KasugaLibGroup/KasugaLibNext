package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/**
 * Platform-neutral identity of one render frame.
 */
public record RenderFrameToken(Object id) {

    public RenderFrameToken {
        Objects.requireNonNull(id, "id");
        if (id instanceof String text && text.isBlank()) {
            throw new IllegalArgumentException("render frame token must not be blank");
        }
    }
}
