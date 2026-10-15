package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable solid box used by an incrementally updated environment cell. */
public record EnvironmentBox(Vector3f minimum, Vector3f maximum) {
    public EnvironmentBox {
        minimum = new Vector3f(Objects.requireNonNull(minimum, "minimum"));
        maximum = new Vector3f(Objects.requireNonNull(maximum, "maximum"));
        if (!minimum.isFinite() || !maximum.isFinite()
                || minimum.x >= maximum.x || minimum.y >= maximum.y || minimum.z >= maximum.z) {
            throw new IllegalArgumentException("environment box bounds must be finite and non-empty");
        }
    }

    @Override public Vector3f minimum() { return new Vector3f(minimum); }
    @Override public Vector3f maximum() { return new Vector3f(maximum); }
}
