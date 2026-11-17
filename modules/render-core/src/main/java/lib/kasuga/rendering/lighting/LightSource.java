package lib.kasuga.rendering.lighting;

import java.util.Objects;

/** Immutable world-space light settings, independent of Minecraft and graphics APIs. */
public record LightSource(Type type, double x, double y, double z, float range,
                          LightColor color, float intensity, float width, float height,
                          float yaw, float pitch, boolean twoSided, float density, float anisotropy) {
    public enum Type { POINT, AREA, VOLUMETRIC }

    public LightSource {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(color, "color");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Light position must be finite");
        }
        positive(range, "range");
        positive(width, "width");
        positive(height, "height");
        nonnegative(intensity, "intensity");
        nonnegative(density, "density");
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !Float.isFinite(anisotropy) || Math.abs(anisotropy) > .9f) {
            throw new IllegalArgumentException("Rotation must be finite; anisotropy must be in [-0.9, 0.9]");
        }
    }

    public static Builder point(double x, double y, double z) {
        return new Builder(Type.POINT, x, y, z);
    }

    public static Builder area(double x, double y, double z, float width, float height) {
        return new Builder(Type.AREA, x, y, z).size(width, height);
    }

    public static Builder volumetric(double x, double y, double z) {
        return new Builder(Type.VOLUMETRIC, x, y, z);
    }

    public Builder toBuilder() {
        return new Builder(type, x, y, z).range(range).color(color).intensity(intensity)
                .size(width, height).rotation(yaw, pitch).twoSided(twoSided).medium(density, anisotropy);
    }

    /** Conservative spherical bound including the emitting rectangle. */
    public double boundsRadius() {
        return range + (type == Type.AREA ? Math.hypot(width, height) * .5 : 0);
    }

    private static void positive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0) throw new IllegalArgumentException(name + " must be finite and positive");
    }

    private static void nonnegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0) throw new IllegalArgumentException(name + " must be finite and nonnegative");
    }

    public static final class Builder {
        private final Type type;
        private double x;
        private double y;
        private double z;
        private float range = 16;
        private LightColor color = LightColor.WHITE;
        private float intensity = 4;
        private float width = 1;
        private float height = 1;
        private float yaw;
        private float pitch;
        private boolean twoSided;
        private float density = .08f;
        private float anisotropy;

        private Builder(Type type, double x, double y, double z) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Builder position(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        public Builder range(float value) { range = value; return this; }
        public Builder color(LightColor value) { color = Objects.requireNonNull(value); return this; }
        /** Optional: omitting this keeps a neutral white tint; color() can replace it later. */
        public Builder temperature(float kelvin) { return color(LightColor.temperature(kelvin)); }
        /** Relative artistic intensity, not a calibrated photometric unit. Zero disables emission. */
        public Builder intensity(float value) { intensity = value; return this; }
        public Builder size(float width, float height) { this.width = width; this.height = height; return this; }
        /** Degrees. The rectangle faces +Z at zero rotation; pitch +90 points it downward. */
        public Builder rotation(float yaw, float pitch) { this.yaw = yaw; this.pitch = pitch; return this; }
        public Builder twoSided(boolean value) { twoSided = value; return this; }
        /** Scattering/extinction per block and Henyey-Greenstein anisotropy. Used by VOLUMETRIC only. */
        public Builder medium(float density, float anisotropy) {
            this.density = density;
            this.anisotropy = anisotropy;
            return this;
        }

        public LightSource build() {
            return new LightSource(type, x, y, z, range, color, intensity, width, height,
                    yaw, pitch, twoSided, density, anisotropy);
        }
    }
}
