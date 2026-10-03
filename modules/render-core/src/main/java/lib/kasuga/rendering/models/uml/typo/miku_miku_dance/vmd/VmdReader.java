package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd;

import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdMotion.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.*;

/** Parser for legacy VMD and VMD 0002, including all optional keyframe sections. */
public final class VmdReader {
    private static final String SIGNATURE_0002 = "Vocaloid Motion Data 0002";
    private static final String SIGNATURE_LEGACY = "Vocaloid Motion Data file";
    private static final Charset WINDOWS_31J = Charset.forName("windows-31j");
    private static final int MAX_KEYFRAMES = 16_777_216;

    public VmdMotion read(ByteBuffer source) {
        ByteBuffer b = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        try {
            String signature = fixedText(b, 30);
            boolean legacy;
            if (signature.startsWith(SIGNATURE_0002)) legacy = false;
            else if (signature.startsWith(SIGNATURE_LEGACY)) legacy = true;
            else throw error(b, "Invalid VMD signature: " + signature);
            String modelName = fixedText(b, legacy ? 10 : 20);

            Map<String, List<BoneKeyframe>> bones = readBoneTracks(b);
            Map<String, List<MorphKeyframe>> morphs = hasSection(b) ? readMorphTracks(b) : Map.of();
            List<CameraKeyframe> cameras = hasSection(b) ? readCameras(b) : List.of();
            List<LightKeyframe> lights = hasSection(b) ? readLights(b) : List.of();
            List<ShadowKeyframe> shadows = hasSection(b) ? readShadows(b) : List.of();
            List<PropertyKeyframe> properties = hasSection(b) ? readProperties(b) : List.of();
            byte[] trailing = new byte[b.remaining()];
            b.get(trailing);
            return new VmdMotion(signature, modelName, bones, morphs, cameras, lights, shadows, properties, trailing);
        } catch (BufferUnderflowException e) {
            throw new VmdFormatException("Truncated VMD at byte " + b.position(), e);
        }
    }

    private static Map<String, List<BoneKeyframe>> readBoneTracks(ByteBuffer b) {
        int count = count(b, "bone keyframe");
        Map<String, List<BoneKeyframe>> tracks = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String name = fixedText(b, 15);
            long frame = u32(b);
            Vector3f translation = vec3(b);
            Quaternionf rotation = quaternion(b);
            if (rotation.lengthSquared() == 0f) rotation.identity();
            else rotation.normalize();
            byte[] interpolation = bytes(b, 64);
            tracks.computeIfAbsent(name, ignored -> new ArrayList<>())
                    .add(new BoneKeyframe(frame, translation, rotation, BoneInterpolation.from(interpolation)));
        }
        sortTracks(tracks, BoneKeyframe::frame);
        return tracks;
    }

    private static Map<String, List<MorphKeyframe>> readMorphTracks(ByteBuffer b) {
        int count = count(b, "morph keyframe");
        Map<String, List<MorphKeyframe>> tracks = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String name = fixedText(b, 15);
            MorphKeyframe frame = new MorphKeyframe(u32(b), b.getFloat());
            tracks.computeIfAbsent(name, ignored -> new ArrayList<>()).add(frame);
        }
        sortTracks(tracks, MorphKeyframe::frame);
        return tracks;
    }

    private static List<CameraKeyframe> readCameras(ByteBuffer b) {
        int count = count(b, "camera keyframe");
        List<CameraKeyframe> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long frame = u32(b);
            float distance = b.getFloat();
            Vector3f target = vec3(b);
            Vector3f rotation = vec3(b);
            CameraInterpolation interpolation = CameraInterpolation.from(bytes(b, 24));
            long fov = u32(b);
            boolean perspective = b.get() == 0;
            result.add(new CameraKeyframe(frame, distance, target, rotation, interpolation, fov, perspective));
        }
        result.sort(Comparator.comparingLong(CameraKeyframe::frame));
        return result;
    }

    private static List<LightKeyframe> readLights(ByteBuffer b) {
        int count = count(b, "light keyframe");
        List<LightKeyframe> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(new LightKeyframe(u32(b), vec3(b), vec3(b)));
        result.sort(Comparator.comparingLong(LightKeyframe::frame));
        return result;
    }

    private static List<ShadowKeyframe> readShadows(ByteBuffer b) {
        int count = count(b, "shadow keyframe");
        List<ShadowKeyframe> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long frame = u32(b);
            int mode = Byte.toUnsignedInt(b.get());
            if (mode > 2) throw error(b, "Invalid VMD shadow mode: " + mode);
            result.add(new ShadowKeyframe(frame, mode, b.getFloat()));
        }
        result.sort(Comparator.comparingLong(ShadowKeyframe::frame));
        return result;
    }

    private static List<PropertyKeyframe> readProperties(ByteBuffer b) {
        int count = count(b, "property keyframe");
        List<PropertyKeyframe> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long frame = u32(b);
            boolean visible = b.get() != 0;
            int ikCount = count(b, "IK state");
            List<IkState> states = new ArrayList<>(ikCount);
            for (int j = 0; j < ikCount; j++) {
                states.add(new IkState(fixedText(b, 20), b.get() != 0));
            }
            result.add(new PropertyKeyframe(frame, visible, states));
        }
        result.sort(Comparator.comparingLong(PropertyKeyframe::frame));
        return result;
    }

    private static boolean hasSection(ByteBuffer b) {
        return b.remaining() >= Integer.BYTES;
    }

    private static int count(ByteBuffer b, String name) {
        long count = u32(b);
        if (count > MAX_KEYFRAMES) throw error(b, "Invalid " + name + " count: " + count);
        return (int) count;
    }

    private static long u32(ByteBuffer b) {
        return Integer.toUnsignedLong(b.getInt());
    }

    private static String fixedText(ByteBuffer b, int length) {
        byte[] bytes = bytes(b, length);
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, 0, end, WINDOWS_31J);
    }

    private static byte[] bytes(ByteBuffer b, int count) {
        byte[] result = new byte[count];
        b.get(result);
        return result;
    }

    private static Vector3f vec3(ByteBuffer b) {
        return new Vector3f(b.getFloat(), b.getFloat(), b.getFloat());
    }

    private static Quaternionf quaternion(ByteBuffer b) {
        return new Quaternionf(b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat());
    }

    private static <T> void sortTracks(Map<String, List<T>> tracks,
                                       java.util.function.ToLongFunction<T> frame) {
        tracks.values().forEach(track -> track.sort(Comparator.comparingLong(frame)));
    }

    private static VmdFormatException error(ByteBuffer b, String message) {
        return new VmdFormatException(message + " at byte " + b.position());
    }
}
