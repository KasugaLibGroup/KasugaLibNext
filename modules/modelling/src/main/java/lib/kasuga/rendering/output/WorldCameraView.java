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
}
