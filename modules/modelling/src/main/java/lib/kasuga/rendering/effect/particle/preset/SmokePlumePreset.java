package lib.kasuga.rendering.effect.particle.preset;

import lib.kasuga.rendering.effect.particle.ParticleGroupBehavior;
import lib.kasuga.rendering.effect.particle.ParticleInstance;
import lib.kasuga.rendering.effect.particle.ParticleSnapshot;
import lib.kasuga.rendering.effect.particle.ParticleUpdate;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

/** Group-controlled smoke instances with rising flow, curl-like drift, expansion and fade-out. */
public final class SmokePlumePreset {
    private static final int AGE = 0;
    private static final int LIFETIME = 1;
    private static final int BASE_SCALE = 2;
    private static final int SEED = 3;
    private static final int BASE_ALPHA = 4;
    private static final int ATTRIBUTE_COUNT = 5;

    private final Settings settings;

    public SmokePlumePreset(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public ParticleInstance create(Vector3f position, Vector3f initialVelocity,
                                   float scale, float seed, Vector4f color) {
        if (!(scale > 0)) throw new IllegalArgumentException("Smoke scale must be positive");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(initialVelocity, "initialVelocity");
        Objects.requireNonNull(color, "color");
        return ParticleInstance.builder(new Transform().translate(position).scale(scale, scale, scale))
                .velocity(initialVelocity)
                .color(color)
                .attributes(0, settings.lifetimeTicks, scale, seed, color.w)
                .build();
    }

    public ParticleGroupBehavior controller() {
        return (group, updates, level) -> {
            for (ParticleSnapshot particle : group.instances()) {
                requireAttributes(particle);
                float age = particle.attribute(AGE) + 1.0f;
                float lifetime = particle.attribute(LIFETIME);
                if (age >= lifetime) {
                    updates.submit(particle.id(), ParticleUpdate.remove(particle));
                    continue;
                }

                float progress = age / lifetime;
                float seed = particle.attribute(SEED);
                Vector3f velocity = particle.velocity();
                velocity.mul(settings.drag);
                velocity.y += (settings.riseSpeed - velocity.y) * settings.riseResponse;

                float phase = seed + age * settings.noiseFrequency;
                Vector3f position = particle.position();
                position.add(
                        velocity.x + (float) Math.sin(phase * 1.37f) * settings.horizontalDrift,
                        velocity.y,
                        velocity.z + (float) Math.cos(phase * 1.91f) * settings.horizontalDrift
                );

                float scale = particle.attribute(BASE_SCALE)
                        * (1.0f + settings.expansion * progress);
                float fade = 1.0f - progress;
                Vector4f color = particle.color();
                color.w = particle.attribute(BASE_ALPHA) * fade * fade;
                float[] attributes = particle.attributes();
                attributes[AGE] = age;

                updates.submit(particle.id(), ParticleUpdate.keep(particle)
                        .withTransform(new Transform()
                                .translate(position)
                                .rotate(0, phase * settings.rotationSpeed, 0, false)
                                .scale(scale, scale, scale))
                        .withVelocity(velocity)
                        .withColor(color)
                        .withAttributes(attributes));
            }
        };
    }

    private static void requireAttributes(ParticleSnapshot particle) {
        if (particle.attributeCount() != ATTRIBUTE_COUNT) {
            throw new IllegalArgumentException(
                    "Smoke instance " + particle.id() + " requires " + ATTRIBUTE_COUNT + " attributes"
            );
        }
    }

    public record Settings(
            int lifetimeTicks,
            float riseSpeed,
            float riseResponse,
            float horizontalDrift,
            float noiseFrequency,
            float rotationSpeed,
            float drag,
            float expansion
    ) {
        public Settings {
            if (lifetimeTicks <= 0) throw new IllegalArgumentException("lifetimeTicks must be positive");
            requireNonNegative(riseResponse, "riseResponse");
            requireNonNegative(horizontalDrift, "horizontalDrift");
            requireNonNegative(noiseFrequency, "noiseFrequency");
            requireNonNegative(drag, "drag");
            requireNonNegative(expansion, "expansion");
        }

        public static Settings defaults() {
            return new Settings(90, 0.045f, 0.08f, 0.008f, 0.13f, 0.35f, 0.985f, 1.8f);
        }
    }

    private static void requireNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
