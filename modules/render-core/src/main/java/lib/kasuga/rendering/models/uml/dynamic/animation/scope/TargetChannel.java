package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Namespaced identity of one target output contract.
 */
public record TargetChannel(String id) {

    private static final Pattern NAMESPACED_ID =
            Pattern.compile("[A-Za-z0-9_.-]+:[A-Za-z0-9_./-]+");

    public TargetChannel {
        Objects.requireNonNull(id, "id");
        if (!NAMESPACED_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("target channel must be a namespaced id: " + id);
        }
    }
}
