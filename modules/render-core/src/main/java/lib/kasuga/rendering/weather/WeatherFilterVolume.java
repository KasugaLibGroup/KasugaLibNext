package lib.kasuga.rendering.weather;

import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Immutable oriented bounding box (OBB) filter volume that blocks or attenuates
 * precipitation inside its local {@code [-0.5, +0.5]^3} unit box after applying
 * center offset, Euler rotation, and extents.
 */
public record WeatherFilterVolume(
        double x,
        double y,
        double z,
        double sizeX,
        double sizeY,
        double sizeZ,
        float yaw,
        float pitch,
        float roll,
        float strength,
        float feather
) {
    public WeatherFilterVolume {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Weather filter volume position must be finite");
        }
        positive(sizeX, "sizeX");
        positive(sizeY, "sizeY");
        positive(sizeZ, "sizeZ");
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(roll)) {
            throw new IllegalArgumentException("Weather filter volume rotation must be finite");
        }
        if (!Float.isFinite(strength) || strength < 0.0f || strength > 1.0f) {
            throw new IllegalArgumentException("Weather filter strength must be in [0.0, 1.0]");
        }
        if (!Float.isFinite(feather) || feather < 0.0f || feather > 0.49f) {
            throw new IllegalArgumentException("Weather filter feather must be in [0.0, 0.49]");
        }
    }

    public static Builder box(double sizeX, double sizeY, double sizeZ) {
        return new Builder(sizeX, sizeY, sizeZ);
    }

    public Builder toBuilder() {
        return new Builder(sizeX, sizeY, sizeZ)
                .position(x, y, z)
                .rotation(yaw, pitch, roll)
                .strength(strength)
                .feather(feather);
    }

    private static void positive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    @Accessors(fluent = true, chain = true)
    public static final class Builder {
        private double x;
        private double y;
        private double z;
        private double sizeX;
        private double sizeY;
        private double sizeZ;
        private float yaw;
        private float pitch;
        private float roll;
        @Setter
        private float strength = 1.0f;
        @Setter
        private float feather = 0.0f;

        private Builder(double sizeX, double sizeY, double sizeZ) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }

        public Builder position(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        public Builder size(double sizeX, double sizeY, double sizeZ) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            return this;
        }

        public Builder rotation(float yaw, float pitch, float roll) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            return this;
        }

        public WeatherFilterVolume build() {
            return new WeatherFilterVolume(
                    x, y, z, sizeX, sizeY, sizeZ, yaw, pitch, roll, strength, feather
            );
        }
    }
}
