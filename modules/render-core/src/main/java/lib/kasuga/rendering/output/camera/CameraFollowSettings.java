package lib.kasuga.rendering.output.camera;

import lib.kasuga.rendering.output.WorldCameraView;

/** Offsets in world coordinates; angles are offsets when following rotation, otherwise absolute. */
public record CameraFollowSettings(double offsetX, double offsetY, double offsetZ,
                                   float yaw, float pitch, float roll, boolean followRotation) {
    public CameraFollowSettings {
        new CameraTarget.Pose(offsetX, offsetY, offsetZ, yaw, pitch, roll);
    }
    public static CameraFollowSettings defaults() { return new CameraFollowSettings(0, 0, 0, 0, 0, 0, true); }

    public WorldCameraView apply(CameraTarget.Pose target, CameraProjection projection) {
        return new WorldCameraView(target.x() + offsetX, target.y() + offsetY, target.z() + offsetZ,
                (followRotation ? target.yaw() : 0) + yaw,
                (followRotation ? target.pitch() : 0) + pitch,
                (followRotation ? target.roll() : 0) + roll,
                projection.verticalFov(), projection.width(), projection.height());
    }
}
