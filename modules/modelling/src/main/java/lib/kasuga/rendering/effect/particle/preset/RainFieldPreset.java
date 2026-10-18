package lib.kasuga.rendering.effect.particle.preset;

import lib.kasuga.rendering.effect.particle.ParticleGroupBehavior;
import lib.kasuga.rendering.effect.particle.ParticleInstance;
import lib.kasuga.rendering.effect.particle.ParticleSnapshot;
import lib.kasuga.rendering.effect.particle.ParticleUpdate;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;
import java.util.Random;

/** Camera- or world-relative rain volume whose instances are recycled by one group controller. */
public final class RainFieldPreset {
    private static final int SEED = 0;
    private static final int STREAK_LENGTH = 1;
    private static final int CYCLE = 2;
    private static final int ATTRIBUTE_COUNT = 3;

    private final Settings settings;
    private final Vector3f center = new Vector3f();

    public RainFieldPreset(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public synchronized void center(Vector3f value) {
        center.set(Objects.requireNonNull(value, "value"));
    }

    public synchronized Vector3f center() {
        return new Vector3f(center);
    }

    public ParticleInstance create(Random random, Vector4f color) {
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(color, "color");
        Vector3f origin = center();
        float seed = random.nextFloat() * 4096.0f;
        float length = randomRange(random, settings.minimumLength, settings.maximumLength);
        Vector3f position = new Vector3f(
                origin.x + signed(random) * settings.halfExtents.x,
                origin.y + signed(random) * settings.halfExtents.y,
                origin.z + signed(random) * settings.halfExtents.z
        );
        Vector3f velocity = new Vector3f(settings.wind.x, -settings.fallSpeed, settings.wind.z);
        return ParticleInstance.builder(streakTransform(position, length, settings.width))
                .velocity(velocity)
                .color(color)
                .attributes(seed, length, 0)
                .build();
    }

    public ParticleGroupBehavior controller() {
        return (group, updates, level) -> {
            Vector3f origin = center();
            for (ParticleSnapshot particle : group.instances()) {
                requireAttributes(particle);
                Vector3f position = particle.position().add(particle.velocity());
                float cycle = particle.attribute(CYCLE);
                if (outside(position, origin)) {
                    cycle += 1.0f;
                    float seed = particle.attribute(SEED) + cycle * 17.0f;
                    position.set(
                            origin.x + signedHash(seed * 1.13f) * settings.halfExtents.x,
                            origin.y + settings.halfExtents.y,
                            origin.z + signedHash(seed * 2.71f) * settings.halfExtents.z
                    );
                }
                float[] attributes = particle.attributes();
                attributes[CYCLE] = cycle;
                updates.submit(particle.id(), ParticleUpdate.keep(particle)
                        .withTransform(streakTransform(
                                position, particle.attribute(STREAK_LENGTH), settings.width
                        ))
                        .withAttributes(attributes));
            }
        };
    }

    private boolean outside(Vector3f position, Vector3f origin) {
        return position.y < origin.y - settings.halfExtents.y
                || Math.abs(position.x - origin.x) > settings.halfExtents.x
                || Math.abs(position.z - origin.z) > settings.halfExtents.z;
    }

    private static Transform streakTransform(Vector3f position, float length, float width) {
        return new Transform().translate(position).scale(width, length, width);
    }

    private static void requireAttributes(ParticleSnapshot particle) {
        if (particle.attributeCount() != ATTRIBUTE_COUNT) {
            throw new IllegalArgumentException(
                    "Rain instance " + particle.id() + " requires " + ATTRIBUTE_COUNT + " attributes"
            );
        }
    }

    private static float signed(Random random) {
        return random.nextFloat() * 2.0f - 1.0f;
    }

    private static float randomRange(Random random, float minimum, float maximum) {
        return minimum + random.nextFloat() * (maximum - minimum);
    }

    private static float signedHash(float value) {
        double sine = Math.sin(value * 12.9898) * 43758.5453;
        return (float) ((sine - Math.floor(sine)) * 2.0 - 1.0);
    }

    public record Settings(
            Vector3f halfExtents,
            float fallSpeed,
            Vector3f wind,
            float width,
            float minimumLength,
            float maximumLength
    ) {
        public Settings {
            halfExtents = new Vector3f(Objects.requireNonNull(halfExtents, "halfExtents"));
            wind = new Vector3f(Objects.requireNonNull(wind, "wind"));
            if (halfExtents.x <= 0 || halfExtents.y <= 0 || halfExtents.z <= 0) {
                throw new IllegalArgumentException("halfExtents must be positive");
            }
            if (!(fallSpeed > 0)) throw new IllegalArgumentException("fallSpeed must be positive");
            if (!(width > 0)) throw new IllegalArgumentException("width must be positive");
            if (!(minimumLength > 0) || maximumLength < minimumLength) {
                throw new IllegalArgumentException("Invalid rain streak length range");
            }
        }

        @Override
        public Vector3f halfExtents() {
            return new Vector3f(halfExtents);
        }

        @Override
        public Vector3f wind() {
            return new Vector3f(wind);
        }

        public static Settings defaults() {
            return new Settings(
                    new Vector3f(18, 12, 18),
                    0.72f,
                    new Vector3f(0.035f, 0, 0.012f),
                    0.025f,
                    0.7f,
                    1.6f
            );
        }
    }
}
