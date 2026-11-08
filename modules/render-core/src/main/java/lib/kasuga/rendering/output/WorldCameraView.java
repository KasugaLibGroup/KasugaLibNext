package lib.kasuga.rendering.output;

import org.joml.Matrix4f;
import org.joml.Vector3f;

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

    public Vector3f forward(Vector3f out) {
        double yawRadians = Math.toRadians(yaw), pitchRadians = Math.toRadians(pitch);
        return out.set((float) (-Math.sin(yawRadians) * Math.cos(pitchRadians)),
                (float) -Math.sin(pitchRadians), (float) (Math.cos(yawRadians) * Math.cos(pitchRadians)));
    }

    /** Camera-to-world transform, including Minecraft Euler roll. Coordinates are in the view's frame. */
    public Matrix4f inverseView(Matrix4f out) {
        var eye = new Vector3f((float) x, (float) y, (float) z);
        return out.identity().lookAt(eye, forward(new Vector3f()).add(eye), new Vector3f(0, 1, 0))
                .rotateLocalZ((float) Math.toRadians(roll)).invert();
    }

    public Matrix4f projection(float near, float far, Matrix4f out) {
        if (!Float.isFinite(near) || !Float.isFinite(far) || near <= 0 || far <= near)
            throw new IllegalArgumentException("Projection requires finite 0 < near < far");
        return out.identity().perspective((float) Math.toRadians(verticalFov), aspectRatio(), near, far);
    }

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
