package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

/**
 * Immutable-by-copy state passed through a {@link ParticleOperator}.
 *
 * <p>The spatial transform keeps position, size and rotation together. Velocity remains an
 * explicit physical quantity, while color and custom attributes can be changed by the same
 * operator. This makes one operator suitable for either physical integration or scripted
 * animation.</p>
 */
public final class ParticleState {
    private final Transform transform;
    private final Vector3f velocity;
    private final Vector4f color;
    private final float[] attributes;
    private final int ageTicks;
    private final int lifetimeTicks;
    private final boolean visible;
    private final boolean removed;

    private ParticleState(
            Transform transform,
            Vector3f velocity,
            Vector4f color,
            float[] attributes,
            int ageTicks,
            int lifetimeTicks,
            boolean visible,
            boolean removed
    ) {
        this.transform = Objects.requireNonNull(transform, "transform").copy();
        this.velocity = new Vector3f(Objects.requireNonNull(velocity, "velocity"));
        this.color = new Vector4f(Objects.requireNonNull(color, "color"));
        this.attributes = Objects.requireNonNull(attributes, "attributes").clone();
        this.ageTicks = ageTicks;
        this.lifetimeTicks = lifetimeTicks;
        this.visible = visible;
        this.removed = removed;
    }

    static ParticleState from(ParticleSnapshot snapshot, int ageTicks, int lifetimeTicks) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new ParticleState(
                snapshot.transform(),
                snapshot.velocity(),
                snapshot.color(),
                snapshot.attributes(),
                ageTicks,
                lifetimeTicks,
                snapshot.visible(),
                false
        );
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

    public int ageTicks() {
        return ageTicks;
    }

    public int lifetimeTicks() {
        return lifetimeTicks;
    }

    public float lifetimeProgress() {
        return Math.min(1.0f, (float) ageTicks / lifetimeTicks);
    }

    public boolean visible() {
        return visible;
    }

    public boolean removed() {
        return removed;
    }

    public ParticleState withTransform(Transform value) {
        return copy(value, velocity, color, attributes, visible, removed);
    }

    public ParticleState withVelocity(Vector3f value) {
        return copy(transform, value, color, attributes, visible, removed);
    }

    public ParticleState withColor(Vector4f value) {
        return copy(transform, velocity, value, attributes, visible, removed);
    }

    public ParticleState withAttributes(float... values) {
        return copy(transform, velocity, color, values, visible, removed);
    }

    public ParticleState withVisible(boolean value) {
        return copy(transform, velocity, color, attributes, value, removed);
    }

    public ParticleState remove() {
        return copy(transform, velocity, color, attributes, visible, true);
    }

    ParticleUpdate toUpdate(ParticleSnapshot snapshot) {
        if (removed) return ParticleUpdate.remove(snapshot);
        return ParticleUpdate.keep(snapshot)
                .withTransform(transform)
                .withVelocity(velocity)
                .withColor(color)
                .withAttributes(attributes)
                .withVisible(visible);
    }

    private ParticleState copy(
            Transform transform,
            Vector3f velocity,
            Vector4f color,
            float[] attributes,
            boolean visible,
            boolean removed
    ) {
        return new ParticleState(
                transform, velocity, color, attributes,
                ageTicks, lifetimeTicks, visible, removed
        );
    }
}
