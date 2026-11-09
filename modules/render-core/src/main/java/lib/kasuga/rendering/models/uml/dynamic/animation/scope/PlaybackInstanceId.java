package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of one playback instance within one authority scope. */
public record PlaybackInstanceId(UUID authorityScopeId, long sequence) {

    public PlaybackInstanceId {
        Objects.requireNonNull(authorityScopeId, "authorityScopeId");
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }
}
