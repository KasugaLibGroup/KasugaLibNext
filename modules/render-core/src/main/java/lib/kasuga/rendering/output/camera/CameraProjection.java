package lib.kasuga.rendering.output.camera;

import lib.kasuga.rendering.output.WorldCameraView;

/** Vertical field of view in degrees and framebuffer dimensions. */
public record CameraProjection(float verticalFov, int width, int height) {
    public CameraProjection {
        if (!Float.isFinite(verticalFov) || verticalFov <= 0 || verticalFov >= 180)
            throw new IllegalArgumentException("Vertical FOV must be between 0 and 180 degrees");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Camera size must be positive");
    }

    public static CameraProjection from(WorldCameraView view) {
        return new CameraProjection(view.verticalFov(), view.width(), view.height());
    }

    public CameraProjection withVerticalFov(float degrees) { return new CameraProjection(degrees, width, height); }
    public CameraProjection zoom(double factor) {
        return from(new WorldCameraView(0, 0, 0, 0, 0, 0, verticalFov, width, height).zoom(factor));
    }
    public WorldCameraView apply(WorldCameraView view) {
        return new WorldCameraView(view.x(), view.y(), view.z(), view.yaw(), view.pitch(), view.roll(), verticalFov, width, height);
    }
}
