package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Passive transformable render instance. It has no mandatory tick; movement only occurs when an
 * external caller, a group controller or an explicitly installed behavior changes its state.
 */
public final class ParticleInstance {
    private Transform transform;
    private Vector3f velocity;
    private Vector4f color;
    private float[] attributes;
    private ParticleBehavior behavior;
    private boolean visible;

    private ParticleInstance(Builder builder) {
        transform = builder.transform.copy();
        velocity = new Vector3f(builder.velocity);
        color = new Vector4f(builder.color);
        attributes = builder.attributes.clone();
        behavior = builder.behavior;
        visible = builder.visible;
    }

    public static Builder builder(Transform transform) {
        return new Builder(transform);
    }

    public synchronized Transform transform() {
        return transform.copy();
    }

    public synchronized void transform(Transform value) {
        transform = Objects.requireNonNull(value, "value").copy();
    }

    /** Applies a mutation to a private transform copy and commits it atomically. */
    public synchronized void transform(Consumer<Transform> mutation) {
        Transform next = transform.copy();
        Objects.requireNonNull(mutation, "mutation").accept(next);
        transform = next;
    }

    public synchronized Vector3f velocity() {
        return new Vector3f(velocity);
    }

    public synchronized void velocity(Vector3f value) {
        velocity = new Vector3f(Objects.requireNonNull(value, "value"));
    }

    public synchronized Vector4f color() {
        return new Vector4f(color);
    }

    public synchronized void color(Vector4f value) {
        color = new Vector4f(Objects.requireNonNull(value, "value"));
    }

    public synchronized float[] attributes() {
        return attributes.clone();
    }

    public synchronized void attributes(float... values) {
        attributes = Objects.requireNonNull(values, "values").clone();
    }

    public synchronized boolean visible() {
        return visible;
    }

    public synchronized void visible(boolean value) {
        visible = value;
    }

    public synchronized ParticleBehavior behavior() {
        return behavior;
    }

    public synchronized void behavior(ParticleBehavior value) {
        behavior = value;
    }

    synchronized ParticleSnapshot snapshot(long id) {
        return new ParticleSnapshot(id, transform, velocity, color, attributes, visible);
    }

    synchronized void apply(ParticleUpdate update) {
        transform = update.transformValue().copy();
        velocity = new Vector3f(update.velocityValue());
        color = new Vector4f(update.colorValue());
        attributes = update.attributeValues().clone();
        visible = update.visibleValue();
    }

    synchronized void write(long id, ParticleInstanceBuffer destination, boolean visibleOnly) {
        if (visibleOnly && !visible) return;
        destination.put(id, transform, velocity, color, attributes, visible);
    }

    synchronized void apply(
            ParticleInstanceBuffer source,
            int index,
            org.joml.Matrix4f matrix
    ) {
        source.matrix(index, matrix);
        transform.set(matrix);
        source.velocity(index, velocity);
        source.color(index, color);
        int attributeCount = source.attributeCount(index);
        if (attributes.length != attributeCount) attributes = new float[attributeCount];
        for (int attribute = 0; attribute < attributeCount; attribute++) {
            attributes[attribute] = source.attribute(index, attribute);
        }
        visible = source.visible(index);
    }

    public static final class Builder {
        private final Transform transform;
        private Vector3f velocity = new Vector3f();
        private Vector4f color = new Vector4f(1, 1, 1, 1);
        private float[] attributes = new float[0];
        private ParticleBehavior behavior;
        private boolean visible = true;

        private Builder(Transform transform) {
            this.transform = Objects.requireNonNull(transform, "transform").copy();
        }

        public Builder velocity(Vector3f value) {
            velocity = new Vector3f(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder color(Vector4f value) {
            color = new Vector4f(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder attributes(float... values) {
            attributes = Objects.requireNonNull(values, "values").clone();
            return this;
        }

        public Builder behavior(ParticleBehavior value) {
            behavior = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder visible(boolean value) {
            visible = value;
            return this;
        }

        public ParticleInstance build() {
            return new ParticleInstance(this);
        }
    }
}
