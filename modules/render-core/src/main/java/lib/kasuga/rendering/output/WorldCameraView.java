package lib.kasuga.rendering.output;

/** Immutable world-space camera pose, vertical FOV in degrees, and output size. */
public record WorldCameraView(double x, double y, double z, float yaw, float pitch, float roll,
                              float verticalFov, int width, int height) {
    public WorldCameraView {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(roll))
            throw new IllegalArgumentException("Camera pose must be finite");
        if (!Float.isFinite(verticalFov) || verticalFov <= 0 || verticalFov >= 180)
            throw new IllegalArgumentException("Vertical FOV must be between 0 and 180 degrees");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Camera size must be positive");
    }

    public float aspectRatio() { return (float) width / height; }

    public WorldCameraView withPosition(double x, double y, double z) {
        return new WorldCameraView(x, y, z, yaw, pitch, roll, verticalFov, width, height);
    }

    /** Translate in world coordinates. */
    public WorldCameraView moveBy(double dx, double dy, double dz) {
        return withPosition(x + dx, y + dy, z + dz);
    }

    /** Euler angles in degrees, using Minecraft's yaw/pitch/roll convention. */
    public WorldCameraView withRotation(float yaw, float pitch, float roll) {
        return new WorldCameraView(x, y, z, yaw, pitch, roll, verticalFov, width, height);
    }

    public WorldCameraView rotateBy(float yaw, float pitch, float roll) {
        return withRotation(this.yaw + yaw, this.pitch + pitch, this.roll + roll);
    }

    public WorldCameraView withVerticalFov(float degrees) {
        return new WorldCameraView(x, y, z, yaw, pitch, roll, degrees, width, height);
    }

    /** Optical magnification: 2 doubles projected size, 0.5 halves it. Output dimensions are retained. */
    public WorldCameraView zoom(double factor) {
        if (!Double.isFinite(factor) || factor <= 0)
            throw new IllegalArgumentException("Zoom must be finite and positive");
        float fov = (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(verticalFov) / 2) / factor));
        return withVerticalFov(Math.clamp(fov, Math.nextUp(0f), Math.nextDown(180f)));
    }
}
