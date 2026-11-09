package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/** Stable key for one one-shot runtime trigger. */
public record InputTriggerKey(String id) {

    public InputTriggerKey {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("trigger key id must not be blank");
        }
    }
}
