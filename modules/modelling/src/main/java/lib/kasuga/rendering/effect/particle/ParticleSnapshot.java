package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

/** Immutable-by-copy view of one transformable render instance during an update or render pass. */
public final class ParticleSnapshot {
    private final long id;
    private final Transform transform;
    private final Vector3f velocity;
    private final Vector4f color;
    private final float[] attributes;
    private final boolean visible;

    ParticleSnapshot(long id, Transform transform, Vector3f velocity, Vector4f color,
                     float[] attributes, boolean visible) {
        this.id = id;
        this.transform = Objects.requireNonNull(transform, "transform").copy();
        this.velocity = new Vector3f(Objects.requireNonNull(velocity, "velocity"));
        this.color = new Vector4f(Objects.requireNonNull(color, "color"));
        this.attributes = Objects.requireNonNull(attributes, "attributes").clone();
        this.visible = visible;
    }

    public long id() {
        return id;
    }

    public Transform transform() {
        return transform.copy();
    }

    public Vector3f position() {
        return transform.getPosition();
    }

    public Vector3f velocity() {
        return new Vector3f(velocity);
    }

    public Vector4f color() {
        return new Vector4f(color);
    }

    public float[] attributes() {
        return attributes.clone();
    }

    public float attribute(int index) {
        return attributes[index];
    }

    public int attributeCount() {
        return attributes.length;
    }

    public boolean visible() {
        return visible;
    }
}
