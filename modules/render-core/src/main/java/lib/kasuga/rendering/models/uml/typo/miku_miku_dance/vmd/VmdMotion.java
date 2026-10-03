package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/** Lossless representation of both legacy VMD and VMD 0002 motion files. */
public record VmdMotion(
        String signature,
        String modelName,
        Map<String, List<BoneKeyframe>> boneTracks,
        Map<String, List<MorphKeyframe>> morphTracks,
        List<CameraKeyframe> cameraTrack,
        List<LightKeyframe> lightTrack,
        List<ShadowKeyframe> shadowTrack,
        List<PropertyKeyframe> propertyTrack,
        byte[] trailingData
) {
    public VmdMotion {
        boneTracks = copyTracks(boneTracks);
        morphTracks = copyTracks(morphTracks);
        cameraTrack = List.copyOf(cameraTrack);
        lightTrack = List.copyOf(lightTrack);
        shadowTrack = List.copyOf(shadowTrack);
        propertyTrack = List.copyOf(propertyTrack);
        trailingData = trailingData.clone();
    }

    private static <T> Map<String, List<T>> copyTracks(Map<String, List<T>> source) {
        return source.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    @Override public byte[] trailingData() { return trailingData.clone(); }

    public record BoneKeyframe(
            long frame,
            Vector3f translation,
            Quaternionf rotation,
            BoneInterpolation interpolation
    ) {}

    public record MorphKeyframe(long frame, float weight) {}

    public record CameraKeyframe(
            long frame,
            float distance,
            Vector3f target,
            Vector3f rotation,
            CameraInterpolation interpolation,
            long fieldOfViewDegrees,
            boolean perspective
    ) {}

    public record LightKeyframe(long frame, Vector3f color, Vector3f direction) {}

    /** distance stores the VMD file value without application-specific remapping. */
    public record ShadowKeyframe(long frame, int mode, float distance) {}

    public record PropertyKeyframe(
            long frame,
            boolean visible,
            List<IkState> ikStates
    ) {
        public PropertyKeyframe { ikStates = List.copyOf(ikStates); }
    }

    public record IkState(String name, boolean enabled) {}

    public record BoneInterpolation(
            VmdBezier x,
            VmdBezier y,
            VmdBezier z,
            VmdBezier rotation,
            boolean physicsEnabled,
            byte[] raw
    ) {
        public BoneInterpolation { raw = raw.clone(); }
        @Override public byte[] raw() { return raw.clone(); }

        public static BoneInterpolation from(byte[] raw) {
            if (raw.length != 64) throw new IllegalArgumentException("Bone interpolation must be 64 bytes");
            return new BoneInterpolation(
                    boneCurve(raw, 0), boneCurve(raw, 1), boneCurve(raw, 2), boneCurve(raw, 3),
                    !(Byte.toUnsignedInt(raw[2]) == 99 && Byte.toUnsignedInt(raw[3]) == 15), raw);
        }

        private static VmdBezier boneCurve(byte[] b, int channel) {
            // VMD stores all channels' A.x, A.y, B.x and B.y in four rows.
            byte x1 = switch (channel) {
                // The first row's Z/rotation A.x bytes double as MMD's physics
                // flag. Their unmodified copies survive in the shifted row.
                case 2 -> b[17];
                case 3 -> b[18];
                default -> b[channel];
            };
            return VmdBezier.bytes(x1, b[channel + 4], b[channel + 8], b[channel + 12]);
        }
    }

    public record CameraInterpolation(
            VmdBezier x,
            VmdBezier y,
            VmdBezier z,
            VmdBezier rotation,
            VmdBezier distance,
            VmdBezier fieldOfView,
            byte[] raw
    ) {
        public CameraInterpolation { raw = raw.clone(); }
        @Override public byte[] raw() { return raw.clone(); }

        public static CameraInterpolation from(byte[] raw) {
            if (raw.length != 24) throw new IllegalArgumentException("Camera interpolation must be 24 bytes");
            return new CameraInterpolation(cameraCurve(raw, 0), cameraCurve(raw, 1),
                    cameraCurve(raw, 2), cameraCurve(raw, 3), cameraCurve(raw, 4),
                    cameraCurve(raw, 5), raw);
        }

        private static VmdBezier cameraCurve(byte[] b, int channel) {
            int offset = channel * 4;
            // Camera curves are stored channel-major as A.x, B.x, A.y, B.y.
            return VmdBezier.bytes(b[offset], b[offset + 2], b[offset + 1], b[offset + 3]);
        }
    }
}
