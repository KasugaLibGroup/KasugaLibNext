package test.kasuga.modelling.demo.nebula;

import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraFollowSettings;
import lib.kasuga.rendering.output.camera.CameraAnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.math.Easing;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import java.util.ArrayList;
import lib.kasuga.rendering.output.camera.CameraProjection;
import lib.kasuga.rendering.output.camera.CameraTarget;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Analytic flight and wake samples in the same local frame as the volume and lightning. */
public final class NebulaFlight {
    public static final float WARP_CYCLE_SECONDS = 15.0f;
    public static final int WAKE_POINTS = 24;
    public static final float WAKE_STEP = .9f;
    public static final float SHIP_SCALE = .90f;
    public static final float WAKE_REACH_MULTIPLIER = 3.2f;
    public static final float BOW_REACH = 2.45f;
    private static final Easing REVEAL_EASING = Easing.quinticHermite(0, 0, 0, 0);
    static final CameraAnimationClip ENDING_LENS = CameraAnimationClip.builder(
                    Id.fromNamespaceAndPath("kasuga_demo", "nebula_ending_lens"), 15)
            .zoom(10.5f, 1, REVEAL_EASING)
            .zoom(15, Math.tan(Math.toRadians(33)) / Math.tan(Math.toRadians(37)), Easing.linear()).build();
    private static final float HULL_HALF_LENGTH = .56f;
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

    public record WarpState(
            float charge,
            float warpActive,
            float stretch,
            float flash,
            float shockProgress,
            float shockIntensity,
            float warpSpeed,
            float warpDistance,
            float fov,
            float cameraRadiusScale,
            float cameraPitchOffset,
            float shakeX,
            float shakeY,
            CameraMotion cameraMotion
    ) {}

    /** Phase offsets vanish throughout both manual-trigger windows, so Space never cuts the camera. */
    public record CameraMotion(float azimuth, float elevation, float radiusScale, float roll,
                               float lookAhead, float lookSide, float lookUp) {
        private static final CameraMotion IDLE = new CameraMotion(0, 0, 1, 0, .12f, 0, .025f);
    }

    // Each row contains orbit, crane, dolly, bank, aim-forward/right/up and vertical FOV.
    private static final float[] CAMERA_TIMES = {5.2f, 7.35f, 8.15f, 9.55f, 11.65f, 13.5f};
    private static final float[][] CAMERA_KEYS = {
            {0, 0, 1, 0, .12f, 0, .025f, 66},
            {14, -1, .96f, -2.8f, .15f, -.035f, .035f, 67},
            {22, -2.5f, .94f, -4.5f, .18f, -.05f, .045f, 66},
            {-17, 1.4f, 1.08f, 4, .24f, .025f, .06f, 90},
            {-12, 1.8f, 1.04f, 2.8f, .20f, .015f, .04f, 86},
            {0, 0, 1, 0, .12f, 0, .025f, 66}
    };
    private static final float[] CAMERA_LAGS = {0, .08f, .05f, .19f, .1f, .14f, .16f, .12f};
    private static final double[][] CAMERA_VELOCITIES = cameraDerivatives(false);
    private static final double[][] CAMERA_ACCELERATIONS = cameraDerivatives(true);

    private static double[][] cameraDerivatives(boolean acceleration) {
        var derivatives = new double[CAMERA_KEYS.length][CAMERA_KEYS[0].length];
        // At the two trigger-window boundaries, position, velocity and acceleration join the idle shot.
        for (int i = 1; i < CAMERA_KEYS.length - 1; i++) {
            double left = CAMERA_TIMES[i] - CAMERA_TIMES[i - 1];
            double right = CAMERA_TIMES[i + 1] - CAMERA_TIMES[i];
            for (int c = 0; c < derivatives[i].length; c++) {
                double v0 = (CAMERA_KEYS[i][c] - CAMERA_KEYS[i - 1][c]) / left;
                double v1 = (CAMERA_KEYS[i + 1][c] - CAMERA_KEYS[i][c]) / right;
                derivatives[i][c] = acceleration ? 2 * (v1 - v0) / (left + right)
                        : (v0 * right + v1 * left) / (left + right);
            }
        }
        return derivatives;
    }

    private static final CameraAnimationClip.Track[] CAMERA_TRACKS = cameraTracks();

    private static CameraAnimationClip.Track[] cameraTracks() {
        // Rig channels: yaw, pitch, dolly, roll, aim forward/right/up, lens.
        var channels = new CameraAnimationClip.Channel[]{CameraAnimationClip.Channel.YAW,
                CameraAnimationClip.Channel.PITCH, CameraAnimationClip.Channel.ZOOM,
                CameraAnimationClip.Channel.ROLL, CameraAnimationClip.Channel.Z,
                CameraAnimationClip.Channel.X, CameraAnimationClip.Channel.Y, CameraAnimationClip.Channel.ZOOM};
        var tracks = new CameraAnimationClip.Track[channels.length];
        for (int c = 0; c < channels.length; c++) {
            var keys = new ArrayList<CameraAnimationClip.Keyframe>();
            for (int i = 0; i < CAMERA_KEYS.length; i++) {
                Easing easing = Easing.linear();
                if (i + 1 < CAMERA_KEYS.length) {
                    double h = CAMERA_TIMES[i + 1] - CAMERA_TIMES[i];
                    double delta = CAMERA_KEYS[i + 1][c] - CAMERA_KEYS[i][c];
                    easing = Easing.quinticHermite(CAMERA_VELOCITIES[i][c] * h / delta,
                            CAMERA_VELOCITIES[i + 1][c] * h / delta,
                            CAMERA_ACCELERATIONS[i][c] * h * h / delta,
                            CAMERA_ACCELERATIONS[i + 1][c] * h * h / delta);
                }
                keys.add(new CameraAnimationClip.Keyframe(CAMERA_TIMES[i] + CAMERA_LAGS[c], CAMERA_KEYS[i][c], easing));
            }
            tracks[c] = new CameraAnimationClip.Track(channels[c], keys);
        }
        return tracks;
    }

    private static float cameraChannel(float phase, int channel) {
        return (float) CAMERA_TRACKS[channel].sample(phase);
    }

    private static CameraMotion warpCamera(float phase) {
        return new CameraMotion(cameraChannel(phase, 0), cameraChannel(phase, 1), cameraChannel(phase, 2),
                cameraChannel(phase, 3), cameraChannel(phase, 4), cameraChannel(phase, 5), cameraChannel(phase, 6));
    }

    public static WarpState sampleWarp(float seconds) {
        if (!Float.isFinite(seconds)) throw new IllegalArgumentException("Warp time must be finite");
        if (seconds < 0) {
            return new WarpState(0f, 0f, 1.0f, 0f, 0f, 0f, 0f, 0f, 66.0f, 1.0f, 0f, 0f, 0f,
                    CameraMotion.IDLE);
        }
        double cycleT = seconds % WARP_CYCLE_SECONDS;

        // Phase 2: Warp charge (5.2 .. 8.2s rises, 12.2 .. 13.8s falls)
        float charge = 0.0f;
        if (cycleT >= 5.2 && cycleT < 8.2) {
            double u = (cycleT - 5.2) / 3.0;
            charge = (float) (0.5 * (1.0 - Math.cos(Math.PI * u)));
        } else if (cycleT >= 8.2 && cycleT < 12.2) {
            charge = 1.0f;
        } else if (cycleT >= 12.2 && cycleT < 13.8) {
            double u = (cycleT - 12.2) / 1.6;
            charge = (float) (0.5 * (1.0 + Math.cos(Math.PI * u)));
        }

        // Phase 4: Warp active (8.45 .. 8.95s rises, 12.15 .. 12.85s falls)
        float warpActive = 0.0f;
        if (cycleT >= 8.45 && cycleT < 8.95) {
            double u = (cycleT - 8.45) / 0.50;
            warpActive = (float) (0.5 * (1.0 - Math.cos(Math.PI * u)));
        } else if (cycleT >= 8.95 && cycleT < 12.15) {
            warpActive = 1.0f;
        } else if (cycleT >= 12.15 && cycleT < 12.85) {
            double u = (cycleT - 12.15) / 0.70;
            warpActive = (float) (0.5 * (1.0 + Math.cos(Math.PI * u)));
        }

        // Warp speed: V_MAX = 16.0f in [9.00, 12.40], cosine ramps in [8.40, 9.00] and [12.40, 13.00]
        float warpSpeed = 0.0f;
        if (cycleT >= 8.40 && cycleT < 9.00) {
            double u = (cycleT - 8.40) / 0.60;
            warpSpeed = (float) (16.0 * 0.5 * (1.0 - Math.cos(Math.PI * u)));
        } else if (cycleT >= 9.00 && cycleT < 12.40) {
            warpSpeed = 16.0f;
        } else if (cycleT >= 12.40 && cycleT < 13.00) {
            double u = (cycleT - 12.40) / 0.60;
            warpSpeed = (float) (16.0 * 0.5 * (1.0 + Math.cos(Math.PI * u)));
        }

        // Monotonic analytic warp distance integral: exactly 64.0 per cycle
        double cycleIntegral;
        if (cycleT < 8.40) {
            cycleIntegral = 0.0;
        } else if (cycleT < 9.00) {
            double dt = cycleT - 8.40;
            double u = dt / 0.60;
            cycleIntegral = 16.0 * 0.5 * (dt - (0.60 / Math.PI) * Math.sin(Math.PI * u));
        } else if (cycleT < 12.40) {
            cycleIntegral = 4.80 + 16.0 * (cycleT - 9.00);
        } else if (cycleT < 13.00) {
            double dt = cycleT - 12.40;
            double u = dt / 0.60;
            cycleIntegral = 59.20 + 16.0 * 0.5 * (dt + (0.60 / Math.PI) * Math.sin(Math.PI * u));
        } else {
            cycleIntegral = 64.0;
        }
        double fullCycles = Math.floor(seconds / WARP_CYCLE_SECONDS);
        float warpDistance = (float) (fullCycles * 64.0 + cycleIntegral);

        // Phase 3: Rubber-band stretch
        float entryStretch = 0.0f;
        if (cycleT >= 7.8 && cycleT <= 9.3) {
            double diff = (cycleT - 8.50) / 0.24;
            entryStretch = (float) (1.75 * Math.exp(-diff * diff));
        }
        float cruiseStretch = warpActive * 0.25f;
        float exitStretch = 0.0f;
        if (cycleT >= 11.8 && cycleT <= 13.2) {
            double diff = (cycleT - 12.45) / 0.22;
            exitStretch = (float) (0.50 * Math.exp(-diff * diff));
        }
        float stretch = 1.0f + entryStretch + cruiseStretch + exitStretch;
        if (cycleT < 0.05 || cycleT > 14.95) {
            stretch = 1.0f;
        }

        // Warp flash
        float entryFlash = 0.0f;
        if (cycleT >= 8.1 && cycleT <= 9.1) {
            double diff = (cycleT - 8.50) / 0.15;
            entryFlash = (float) (1.0 * Math.exp(-diff * diff));
        }
        float exitFlash = 0.0f;
        if (cycleT >= 11.9 && cycleT <= 13.0) {
            double diff = (cycleT - 12.42) / 0.14;
            exitFlash = (float) (0.55 * Math.exp(-diff * diff));
        }
        float flash = entryFlash + exitFlash;
        if (cycleT < 0.05 || cycleT > 14.95) {
            flash = 0.0f;
        }

        // Subspace shockwave
        float shockProgress = 0.0f, shockIntensity = 0.0f;
        if (cycleT >= 8.55 && cycleT <= 9.75) {
            float p = (float) ((cycleT - 8.55) / 1.20);
            shockProgress = p;
            shockIntensity = (float) Math.sin(Math.PI * p);
        } else if (cycleT >= 12.35 && cycleT <= 13.75) {
            float p = (float) ((cycleT - 12.35) / 1.40);
            shockProgress = p;
            shockIntensity = (float) (Math.sin(Math.PI * p) * 0.75);
        }

        // One continuous camera rig; bank, dolly and lens trail the orbit by different response times.
        var cameraMotion = warpCamera((float) cycleT);
        float cameraRadiusScale = cameraMotion.radiusScale();
        float cameraPitchOffset = cameraMotion.elevation();
        float fov = cameraChannel((float) cycleT, 7);

        // High frequency engine shake
        float shakeFactor = 0.0f;
        if (charge > 0.4f || warpActive > 0.0f) {
            shakeFactor = Math.max((charge - 0.4f) / 0.6f * 0.35f, warpActive);
        }
        float shakeX = (float) (Math.sin(seconds * 62.0) * 0.007 + Math.cos(seconds * 117.0) * 0.005) * shakeFactor;
        float shakeY = (float) (Math.cos(seconds * 73.0) * 0.007 + Math.sin(seconds * 131.0) * 0.005) * shakeFactor;

        return new WarpState(
                charge,
                warpActive,
                stretch,
                flash,
                shockProgress,
                shockIntensity,
                warpSpeed,
                warpDistance,
                fov,
                cameraRadiusScale,
                cameraPitchOffset,
                shakeX,
                shakeY,
                cameraMotion
        );
    }

    public static WorldCameraView camera(Pose pose, int width, int height, float azimuth, float elevation) {
        return camera(pose, sampleWarp(0f), width, height, azimuth, elevation);
    }

    public static WorldCameraView camera(Pose pose, WarpState warp, int width, int height, float azimuth, float elevation) {
        return camera(pose, warp, 0, width, height, azimuth, elevation);
    }

    /** Flight time drives the unbroken drift; warp time drives only the phase offsets in WarpState. */
    public static WorldCameraView camera(Pose pose, WarpState warp, float seconds,
                                         int width, int height, float azimuth, float elevation) {
        return ENDING_LENS.sample(cameraBase(pose, warp, seconds, width, height, azimuth, elevation), seconds);
    }

    static WorldCameraView cameraBase(Pose pose, WarpState warp, float seconds,
                                      int width, int height, float azimuth, float elevation) {
        if (!Float.isFinite(seconds)) throw new IllegalArgumentException("Camera time must be finite");
        var motion = warp.cameraMotion();
        // A single establishing reveal follows the shared flight clock, not the seekable/repeating warp phase.
        // Keep the wide ending after 15s; another warp or Space must not cut back to the opening close shot.
        float reveal = Math.clamp((seconds - 10.5f) / 4.5f, 0, 1);
        reveal = REVEAL_EASING.apply(reveal);
        float chaseWeight = 1 - .65f * reveal;
        // Analytic, bounded crane/orbit motion: no frame-dependent spring or history to reset on pause/capture.
        float drift = (float) Math.sin(seconds * .24);
        float crane = (float) Math.sin(seconds * .31);
        double angle = Math.toRadians(45 + azimuth + (motion.azimuth() + 14 * drift) * chaseWeight);
        double pitch = Math.toRadians(Math.clamp(14 + elevation + warp.cameraPitchOffset() * chaseWeight
                + 4 * crane + 8 * reveal, -5, 70));
        // Open the rig with the physical stretch, smoothly preserving clearance and the full silhouette.
        float radius = 2.25f * (1 + (warp.cameraRadiusScale() - 1) * (1 - reveal))
                * (1 + .55f * (warp.stretch() - 1) * (1 - reveal))
                * (1 - .09f * (float) Math.sin(seconds * .19)) * (1 + 2.2f * reveal);
        var offset = new Vector3f(pose.right()).mul((float) (Math.sin(angle) * Math.cos(pitch)) * radius)
                .fma((float) Math.sin(pitch) * radius, pose.up())
                .fma(-(float) (Math.cos(angle) * Math.cos(pitch)) * radius, pose.forward());
        var direction = new Vector3f(pose.forward()).mul(motion.lookAhead() + .25f * reveal).sub(offset)
                .fma(motion.lookSide() + warp.shakeX(), pose.right())
                .fma(motion.lookUp() + warp.shakeY(), pose.up());
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float lookPitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
        float roll = (-8 + motion.roll() * chaseWeight + 2 * drift) * (1 - .35f * reveal);
        var follow = new CameraFollowSettings(offset.x, offset.y, offset.z, yaw, lookPitch, roll, false);
        return follow.apply(new CameraTarget.Pose(pose.position().x, pose.position().y, pose.position().z, 0, 0, 0),
                new CameraProjection(warp.fov(), width, height));
    }

    /** The same optical axis is used for CPU particle culling and the shader's world-space rays. */
    public static Vector3f cameraForward(WorldCameraView camera, Vector3f out) {
        return camera.forward(out);
    }

    public static Matrix4f inverseView(WorldCameraView camera, Matrix4f out) {
        return camera.inverseView(out);
    }

    /** Expanding turbulent wake envelope in compact cloud units, shared by tests and mirrored in GLSL. */
    public static float wakeRadius(float age) {
        if (!Float.isFinite(age)) throw new IllegalArgumentException("Wake age must be finite");
        return .15f + .165f * (float) Math.pow(Math.max(0f, age) + .05f, .72);
    }

    /** Fixed memory footprint and no per-frame history integration: stern locations remain in world space. */
    public static float[] wake(float seconds) {
        var data = new float[WAKE_POINTS * 4];
        fillWake(seconds, data);
        return data;
    }

    /** Writes 24 stern samples and 4 exact world-space AABBs (total + 3 segment groups) without heap allocation. */
    public static void fillWakeAndBounds(float seconds, float[] wakeOut, float[] boundsOut) {
        fillWake(seconds, wakeOut);
        double phase = Math.max(-1, seconds * .075 + .72);
        float cx = (float) (2.2 * Math.sin(phase));
        float cy = (float) (.8 * Math.sin(phase * .73));
        float cz = (float) (9 * Math.sin(phase * .65 - .9));
        boundsOut[0] = cx - BOW_REACH; boundsOut[1] = cy - BOW_REACH; boundsOut[2] = cz - BOW_REACH;
        boundsOut[3] = cx + BOW_REACH; boundsOut[4] = cy + BOW_REACH; boundsOut[5] = cz + BOW_REACH;

        fillGroupBounds(wakeOut, 0, 8, boundsOut, 6);
        fillGroupBounds(wakeOut, 8, 16, boundsOut, 12);
        fillGroupBounds(wakeOut, 16, WAKE_POINTS - 1, boundsOut, 18);
        for (int g = 6; g <= 18; g += 6) {
            boundsOut[0] = Math.min(boundsOut[0], boundsOut[g]);
            boundsOut[1] = Math.min(boundsOut[1], boundsOut[g + 1]);
            boundsOut[2] = Math.min(boundsOut[2], boundsOut[g + 2]);
            boundsOut[3] = Math.max(boundsOut[3], boundsOut[g + 3]);
            boundsOut[4] = Math.max(boundsOut[4], boundsOut[g + 4]);
            boundsOut[5] = Math.max(boundsOut[5], boundsOut[g + 5]);
        }
    }

    private static void fillWake(float seconds, float[] wakeOut) {
        if (!Float.isFinite(seconds)) throw new IllegalArgumentException("Flight time must be finite");
        float sternOffset = -HULL_HALF_LENGTH * SHIP_SCALE;
        for (int i = 0; i < WAKE_POINTS; i++) {
            float sampleSeconds = seconds - i * WAKE_STEP;
            double phase = Math.max(-1, sampleSeconds * .075 + .72);
            float px = (float) (2.2 * Math.sin(phase));
            float py = (float) (.8 * Math.sin(phase * .73));
            float pz = (float) (9 * Math.sin(phase * .65 - .9));
            float fx = (float) (.165 * Math.cos(phase));
            float fy = (float) (.0438 * Math.cos(phase * .73));
            float fz = (float) (.43875 * Math.cos(phase * .65 - .9));
            float invLen = org.joml.Math.invsqrt(fx * fx + fy * fy + fz * fz);
            int k = i * 4;
            wakeOut[k] = org.joml.Math.fma(sternOffset, fx * invLen, px);
            wakeOut[k + 1] = org.joml.Math.fma(sternOffset, fy * invLen, py);
            wakeOut[k + 2] = org.joml.Math.fma(sternOffset, fz * invLen, pz);
            wakeOut[k + 3] = i * WAKE_STEP;
        }
    }

    private static void fillGroupBounds(float[] wake, int segStart, int segEndExclusive, float[] out, int offset) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = segStart; i < segEndExclusive; i++) {
            int a = i * 4, b = (i + 1) * 4;
            float pad = wakeRadius(wake[b + 3]) * WAKE_REACH_MULTIPLIER + .001f;
            minX = Math.min(minX, Math.min(wake[a], wake[b]) - pad);
            minY = Math.min(minY, Math.min(wake[a + 1], wake[b + 1]) - pad);
            minZ = Math.min(minZ, Math.min(wake[a + 2], wake[b + 2]) - pad);
            maxX = Math.max(maxX, Math.max(wake[a], wake[b]) + pad);
            maxY = Math.max(maxY, Math.max(wake[a + 1], wake[b + 1]) + pad);
            maxZ = Math.max(maxZ, Math.max(wake[a + 2], wake[b + 2]) + pad);
        }
        out[offset] = minX; out[offset + 1] = minY; out[offset + 2] = minZ;
        out[offset + 3] = maxX; out[offset + 4] = maxY; out[offset + 5] = maxZ;
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
