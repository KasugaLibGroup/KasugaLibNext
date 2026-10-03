package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import org.joml.Vector3f;

import java.util.Objects;

/** Hit result of a ray query against dynamic rigid bodies. */
public record RayHit(SimBody body, Vector3f point, float distance) {
    public RayHit {
        Objects.requireNonNull(body, "body");
        point = new Vector3f(Objects.requireNonNull(point, "point"));
        if (!point.isFinite() || !Float.isFinite(distance) || distance < 0f) {
            throw new IllegalArgumentException("ray hit must be finite and in front of the origin");
        }
    }

    @Override public Vector3f point() { return new Vector3f(point); }
}
