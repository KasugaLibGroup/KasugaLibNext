package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import org.joml.Vector3f;

/** Handle for a static plane registered in a rigid-body world. */
public final class PlaneCollider {
    private final Vector3f normal;
    private final float offset;
    private final float friction;
    private final float restitution;

    PlaneCollider(Vector3f normal, float offset, float friction, float restitution) {
        this.normal = new Vector3f(normal);
        this.offset = offset;
        this.friction = friction;
        this.restitution = restitution;
    }

    public Vector3f normal() { return new Vector3f(normal); }
    public float offset() { return offset; }
    public float friction() { return friction; }
    public float restitution() { return restitution; }
    Vector3f normalRef() { return normal; }
}
