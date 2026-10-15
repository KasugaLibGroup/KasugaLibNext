package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

/** Complete next-state value produced by a group controller or optional instance behavior. */
public final class ParticleUpdate {
    private final Transform transform;
    private final Vector3f velocity;
    private final Vector4f color;
    private final float[] attributes;
    private final boolean visible;
    private final boolean remove;

    private ParticleUpdate(Transform transform, Vector3f velocity, Vector4f color,
                           float[] attributes, boolean visible, boolean remove) {
        this.transform = Objects.requireNonNull(transform, "transform").copy();
        this.velocity = new Vector3f(Objects.requireNonNull(velocity, "velocity"));
        this.color = new Vector4f(Objects.requireNonNull(color, "color"));
        this.attributes = Objects.requireNonNull(attributes, "attributes").clone();
        this.visible = visible;
        this.remove = remove;
    }

    public static ParticleUpdate keep(ParticleSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new ParticleUpdate(
                snapshot.transform(), snapshot.velocity(), snapshot.color(),
                snapshot.attributes(), snapshot.visible(), false
        );
    }

    public static ParticleUpdate remove(ParticleSnapshot snapshot) {
        ParticleUpdate current = keep(snapshot);
        return new ParticleUpdate(
                current.transform, current.velocity, current.color,
                current.attributes, current.visible, true
        );
    }

    public ParticleUpdate withTransform(Transform value) {
        return new ParticleUpdate(value, velocity, color, attributes, visible, remove);
    }

    public ParticleUpdate withVelocity(Vector3f value) {
        return new ParticleUpdate(transform, value, color, attributes, visible, remove);
    }

    public ParticleUpdate withColor(Vector4f value) {
        return new ParticleUpdate(transform, velocity, value, attributes, visible, remove);
    }

    public ParticleUpdate withAttributes(float... values) {
        return new ParticleUpdate(transform, velocity, color, values, visible, remove);
    }

    public ParticleUpdate withVisible(boolean value) {
        return new ParticleUpdate(transform, velocity, color, attributes, value, remove);
    }

    Transform transformValue() {
        return transform;
    }

    Vector3f velocityValue() {
        return velocity;
    }

    Vector4f colorValue() {
        return color;
    }

    float[] attributeValues() {
        return attributes;
    }

    boolean visibleValue() {
        return visible;
    }

    public boolean removesInstance() {
        return remove;
    }
}
