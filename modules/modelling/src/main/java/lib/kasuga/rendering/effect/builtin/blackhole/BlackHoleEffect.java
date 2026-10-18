package lib.kasuga.rendering.effect.builtin.blackhole;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Immutable world-space parameters for one screen-space black-hole lens. */
public record BlackHoleEffect(
        ResourceLocation id,
        Vec3 position,
        float eventHorizonRadius,
        float influenceRadius,
        float distortionStrength,
        float accretionRadius,
        float accretionWidth,
        float glowStrength,
        float chromaticAberration,
        float rotationSpeed,
        Color glowColor,
        boolean depthTest,
        float accretionDiskTilt
) {
    public BlackHoleEffect {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(glowColor, "glowColor");
        positive(eventHorizonRadius, "eventHorizonRadius");
        atLeast(influenceRadius, 1.0f, "influenceRadius");
        nonNegative(distortionStrength, "distortionStrength");
        atLeast(accretionRadius, 1.0f, "accretionRadius");
        positive(accretionWidth, "accretionWidth");
        nonNegative(glowStrength, "glowStrength");
        nonNegative(chromaticAberration, "chromaticAberration");
        finite(rotationSpeed, "rotationSpeed");
        positive(accretionDiskTilt, "accretionDiskTilt");
        if (accretionDiskTilt > 1.0f) {
            throw new IllegalArgumentException("accretionDiskTilt must not exceed 1.0");
        }
    }

    public static Builder builder(ResourceLocation id, Vec3 position) {
        return new Builder(id, position);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    private static void positive(float value, String name) {
        finite(value, name);
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void nonNegative(float value, String name) {
        finite(value, name);
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
    }

    private static void atLeast(float value, float minimum, String name) {
        finite(value, name);
        if (value < minimum) throw new IllegalArgumentException(name + " must be at least " + minimum);
    }

    private static void finite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    public record Color(float red, float green, float blue) {
        public Color {
            nonNegative(red, "red");
            nonNegative(green, "green");
            nonNegative(blue, "blue");
        }
    }

    public static final class Builder {
        private final ResourceLocation id;
        private Vec3 position;
        private float eventHorizonRadius = 1.0f;
        private float influenceRadius = 4.0f;
        private float distortionStrength = 0.7f;
        private float accretionRadius = 1.65f;
        private float accretionWidth = 0.28f;
        private float glowStrength = 1.3f;
        private float chromaticAberration = 0.025f;
        private float rotationSpeed = 1.0f;
        private Color glowColor = new Color(1.0f, 0.32f, 0.06f);
        private boolean depthTest = true;
        private float accretionDiskTilt = 1.0f;

        private Builder(ResourceLocation id, Vec3 position) {
            this.id = Objects.requireNonNull(id, "id");
            this.position = Objects.requireNonNull(position, "position");
        }

        private Builder(BlackHoleEffect effect) {
            id = effect.id;
            position = effect.position;
            eventHorizonRadius = effect.eventHorizonRadius;
            influenceRadius = effect.influenceRadius;
            distortionStrength = effect.distortionStrength;
            accretionRadius = effect.accretionRadius;
            accretionWidth = effect.accretionWidth;
            glowStrength = effect.glowStrength;
            chromaticAberration = effect.chromaticAberration;
            rotationSpeed = effect.rotationSpeed;
            glowColor = effect.glowColor;
            depthTest = effect.depthTest;
            accretionDiskTilt = effect.accretionDiskTilt;
        }

        public Builder position(Vec3 value) { position = Objects.requireNonNull(value); return this; }
        public Builder eventHorizonRadius(float value) { eventHorizonRadius = value; return this; }
        /** Multiplier relative to the event-horizon radius. */
        public Builder influenceRadius(float value) { influenceRadius = value; return this; }
        public Builder distortionStrength(float value) { distortionStrength = value; return this; }
        /** Accretion-ring center, relative to the event-horizon radius. */
        public Builder accretionRadius(float value) { accretionRadius = value; return this; }
        /** Accretion-ring half-width, relative to the event-horizon radius. */
        public Builder accretionWidth(float value) { accretionWidth = value; return this; }
        public Builder glowStrength(float value) { glowStrength = value; return this; }
        /** RGB separation relative to the event-horizon radius. */
        public Builder chromaticAberration(float value) { chromaticAberration = value; return this; }
        public Builder rotationSpeed(float value) { rotationSpeed = value; return this; }
        public Builder glowColor(Color value) { glowColor = Objects.requireNonNull(value); return this; }
        public Builder depthTest(boolean value) { depthTest = value; return this; }
        /** Screen-space minor/major axis ratio: 1 is face-on, values near 0 are edge-on. */
        public Builder accretionDiskTilt(float value) { accretionDiskTilt = value; return this; }

        public BlackHoleEffect build() {
            return new BlackHoleEffect(
                    id, position, eventHorizonRadius, influenceRadius, distortionStrength,
                    accretionRadius, accretionWidth, glowStrength, chromaticAberration,
                    rotationSpeed, glowColor, depthTest, accretionDiskTilt
            );
        }
    }
}
