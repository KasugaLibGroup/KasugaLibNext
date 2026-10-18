package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import org.joml.Vector3f;

import java.util.Objects;
import java.util.Optional;

/** One Box3D contact point queried from the perspective of {@link #body()}. */
public record BodyContact(SimBody body, Optional<SimBody> other,
                          Vector3f point, Vector3f normal,
                          float separation, float normalImpulse,
                          float totalNormalImpulse, float normalVelocity) {
    public BodyContact {
        Objects.requireNonNull(body, "body");
        other = Objects.requireNonNull(other, "other");
        point = new Vector3f(Objects.requireNonNull(point, "point"));
        normal = new Vector3f(Objects.requireNonNull(normal, "normal"));
        if (!point.isFinite() || !normal.isFinite()
                || !Float.isFinite(separation) || !Float.isFinite(normalImpulse)
                || !Float.isFinite(totalNormalImpulse) || !Float.isFinite(normalVelocity)) {
            throw new IllegalArgumentException("contact values must be finite");
        }
    }

    @Override public Vector3f point() { return new Vector3f(point); }
    /** Unit normal pointing from the other shape toward {@link #body()}. */
    @Override public Vector3f normal() { return new Vector3f(normal); }
    public boolean touching() { return separation <= 0f || totalNormalImpulse > 0f; }
}
