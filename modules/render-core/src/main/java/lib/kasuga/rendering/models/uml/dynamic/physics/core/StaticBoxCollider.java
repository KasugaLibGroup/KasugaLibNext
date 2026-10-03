package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import org.joml.Vector3f;

/** Handle for a static axis-aligned box registered in a rigid-body world. */
public final class StaticBoxCollider {
    private final Vector3f minimum;
    private final Vector3f maximum;
    private final float friction;
    private final float restitution;

    StaticBoxCollider(Vector3f minimum, Vector3f maximum, float friction, float restitution) {
        this.minimum = new Vector3f(minimum);
        this.maximum = new Vector3f(maximum);
        this.friction = friction;
        this.restitution = restitution;
    }

    public Vector3f minimum() { return new Vector3f(minimum); }
    public Vector3f maximum() { return new Vector3f(maximum); }
    public float friction() { return friction; }
    public float restitution() { return restitution; }
    Vector3f minimumRef() { return minimum; }
    Vector3f maximumRef() { return maximum; }
}
