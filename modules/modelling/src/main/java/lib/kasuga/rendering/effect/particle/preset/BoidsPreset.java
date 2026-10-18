package lib.kasuga.rendering.effect.particle.preset;

import lib.kasuga.rendering.effect.particle.ParticleBehavior;
import lib.kasuga.rendering.effect.particle.ParticleGroupSnapshot;
import lib.kasuga.rendering.effect.particle.ParticleInstance;
import lib.kasuga.rendering.effect.particle.ParticleSnapshot;
import lib.kasuga.rendering.effect.particle.ParticleUpdate;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

/** Optional per-instance flocking behavior with separation, alignment and cohesion. */
public final class BoidsPreset {
    private static final int SCALE = 0;
    private static final int ATTRIBUTE_COUNT = 1;

    private final Settings settings;
    private final Vector3f center = new Vector3f();

    public BoidsPreset(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public synchronized void center(Vector3f value) {
        center.set(Objects.requireNonNull(value, "value"));
    }

    public synchronized Vector3f center() {
        return new Vector3f(center);
    }

    public ParticleInstance create(Vector3f position, Vector3f velocity,
                                   float scale, Vector4f color) {
        if (!(scale > 0)) throw new IllegalArgumentException("Boid scale must be positive");
        return ParticleInstance.builder(orientedTransform(position, velocity, scale))
                .velocity(velocity)
                .color(color)
                .attributes(scale)
                .behavior(behavior())
                .build();
    }

    public ParticleBehavior behavior() {
        return (particle, group, level) -> update(particle, group);
    }

    private ParticleUpdate update(ParticleSnapshot particle, ParticleGroupSnapshot group) {
        requireAttributes(particle);
        Vector3f position = particle.position();
        Vector3f velocity = particle.velocity();
        Vector3f separation = new Vector3f();
        Vector3f alignment = new Vector3f();
        Vector3f cohesion = new Vector3f();
        int neighbors = 0;

        for (ParticleSnapshot candidate : group.near(position, settings.neighborRadius)) {
            if (candidate.id() == particle.id()) continue;
            Vector3f delta = candidate.position().sub(position);
            float distanceSquared = delta.lengthSquared();
            if (distanceSquared <= 1.0e-6f) continue;
            separation.fma(-1.0f / distanceSquared, delta);
            alignment.add(candidate.velocity());
            cohesion.add(candidate.position());
            neighbors++;
        }

        Vector3f acceleration = new Vector3f();
        if (neighbors > 0) {
            alignment.div(neighbors).sub(velocity);
            cohesion.div(neighbors).sub(position);
            steer(acceleration, separation, settings.separationWeight);
            steer(acceleration, alignment, settings.alignmentWeight);
            steer(acceleration, cohesion, settings.cohesionWeight);
        }

        Vector3f toCenter = center().sub(position);
        float centerDistance = toCenter.length();
        if (centerDistance > settings.boundaryRadius) {
            steer(acceleration, toCenter, settings.boundaryWeight);
        }
        limit(acceleration, settings.maxForce);
        velocity.add(acceleration);
        limit(velocity, settings.maxSpeed);
        position.add(velocity);

        return ParticleUpdate.keep(particle)
                .withVelocity(velocity)
                .withTransform(orientedTransform(position, velocity, particle.attribute(SCALE)));
    }

    private static void steer(Vector3f destination, Vector3f direction, float weight) {
        if (direction.lengthSquared() <= 1.0e-8f || weight == 0) return;
        destination.fma(weight, direction.normalize());
    }

    private static void limit(Vector3f vector, float maximum) {
        float lengthSquared = vector.lengthSquared();
        if (lengthSquared > maximum * maximum) {
            vector.normalize(maximum);
        }
    }

    private static Transform orientedTransform(Vector3f position, Vector3f velocity, float scale) {
        float horizontal = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        float pitch = (float) -Math.atan2(velocity.y, Math.max(horizontal, 1.0e-6f));
        float yaw = (float) Math.atan2(velocity.x, velocity.z);
        return new Transform()
                .translate(position)
                .rotate(pitch, yaw, 0, false)
                .scale(scale, scale, scale * 2.4f);
    }

    private static void requireAttributes(ParticleSnapshot particle) {
        if (particle.attributeCount() != ATTRIBUTE_COUNT) {
            throw new IllegalArgumentException(
                    "Boid instance " + particle.id() + " requires " + ATTRIBUTE_COUNT + " attribute"
            );
        }
    }

    public record Settings(
            float neighborRadius,
            float separationWeight,
            float alignmentWeight,
            float cohesionWeight,
            float maxSpeed,
            float maxForce,
            float boundaryRadius,
            float boundaryWeight
    ) {
        public Settings {
            positive(neighborRadius, "neighborRadius");
            nonNegative(separationWeight, "separationWeight");
            nonNegative(alignmentWeight, "alignmentWeight");
            nonNegative(cohesionWeight, "cohesionWeight");
            positive(maxSpeed, "maxSpeed");
            positive(maxForce, "maxForce");
            positive(boundaryRadius, "boundaryRadius");
            nonNegative(boundaryWeight, "boundaryWeight");
        }

        public static Settings defaults() {
            return new Settings(4.5f, 0.075f, 0.045f, 0.032f, 0.18f, 0.012f, 12.0f, 0.08f);
        }
    }

    private static void positive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void nonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
