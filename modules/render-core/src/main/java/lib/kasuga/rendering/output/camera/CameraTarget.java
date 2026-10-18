package lib.kasuga.rendering.output.camera;

/** Host-neutral follow target. Sample once per rendered view; null means temporarily unavailable. */
@FunctionalInterface
public interface CameraTarget {
    Pose sample(float partialTick);

    /** World coordinates and Euler angles in degrees, using the camera yaw/pitch/roll convention. */
    record Pose(double x, double y, double z, float yaw, float pitch, float roll) {
        public Pose {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(roll))
                throw new IllegalArgumentException("Target pose must be finite");
        }
    }
}
