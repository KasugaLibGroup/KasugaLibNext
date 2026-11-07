package test.kasuga.modelling.nebula;

import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraFollowSettings;
import lib.kasuga.rendering.output.camera.CameraProjection;
import lib.kasuga.rendering.output.camera.CameraTarget;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Analytic flight and wake samples in the same local frame as the volume and lightning. */
public final class NebulaFlight {
    public static final int WAKE_POINTS = 24;
    public static final float WAKE_STEP = .9f;
    public static final float SHIP_SCALE = .58f;
    private static final float HULL_HALF_LENGTH = .45f;
    private NebulaFlight() {}

    public static Pose sample(float seconds) {
        if (!Float.isFinite(seconds)) throw new IllegalArgumentException("Flight time must be finite");
        // Enter an already-running flight; negative wake samples describe the seconds before the demo opened.
        double phase = Math.max(-1,seconds*.075+.72);
        var position = new Vector3f((float) (2.2 * Math.sin(phase)), (float) (.8 * Math.sin(phase * .73)),
                (float) (9 * Math.sin(phase * .65 - .9)));
        var forward = new Vector3f((float) (.165 * Math.cos(phase)), (float) (.0438 * Math.cos(phase * .73)),
                (float) (.43875 * Math.cos(phase * .65 - .9))).normalize();
        var right = new Vector3f(0, 1, 0).cross(forward).normalize();
        var up = new Vector3f(forward).cross(right).normalize();
        float bank = (float) Math.sin(phase) * .10f;
        var bankedRight = new Vector3f(right).mul((float) Math.cos(bank)).fma((float) Math.sin(bank), up);
        up.mul((float) Math.cos(bank)).fma(-(float) Math.sin(bank), right);
        return new Pose(position, bankedRight, up, forward);
    }

    public static WorldCameraView camera(Pose pose, int width, int height, float azimuth, float elevation) {
        double angle = Math.toRadians(35 + azimuth);
        double pitch = Math.toRadians(Math.clamp(22 + elevation, -5, 70));
        float radius = 5.0f;
        var offset = new Vector3f(pose.right()).mul((float) (Math.sin(angle) * Math.cos(pitch)) * radius)
                .fma((float) Math.sin(pitch) * radius, pose.up())
                .fma(-(float) (Math.cos(angle) * Math.cos(pitch)) * radius, pose.forward());
        var direction = new Vector3f(pose.forward()).mul(.7f).sub(offset);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float lookPitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
        var follow = new CameraFollowSettings(offset.x, offset.y, offset.z, yaw, lookPitch, -8, false);
        return follow.apply(new CameraTarget.Pose(pose.position().x, pose.position().y, pose.position().z, 0, 0, 0),
                new CameraProjection(76, width, height));
    }

    /** Expanding turbulent wake envelope in compact cloud units, shared by tests and mirrored in GLSL. */
    public static float wakeRadius(float age) {
        if (!Float.isFinite(age)) throw new IllegalArgumentException("Wake age must be finite");
        return .15f + .165f * (float) Math.pow(Math.max(0f, age) + .05f, .72);
    }

    /** Fixed memory footprint and no per-frame history integration: stern locations remain in world space. */
    public static float[] wake(float seconds) {
        var data = new float[WAKE_POINTS * 4];
        for (int i = 0; i < WAKE_POINTS; i++) {
            var position = sample(seconds - i * WAKE_STEP).stern();
            data[i*4] = position.x; data[i*4+1] = position.y; data[i*4+2] = position.z;
            data[i*4+3] = i * WAKE_STEP;
        }
        return data;
    }

    public record Pose(Vector3f position, Vector3f right, Vector3f up, Vector3f forward) {
        public Vector3f stern() {
            return new Vector3f(position).fma(-HULL_HALF_LENGTH * SHIP_SCALE, forward);
        }

        public Matrix4f toLocal() {
            return new Matrix4f().setColumn(0, new Vector4f(right, 0)).setColumn(1, new Vector4f(up, 0))
                    .setColumn(2, new Vector4f(forward, 0)).transpose();
        }
    }
}
