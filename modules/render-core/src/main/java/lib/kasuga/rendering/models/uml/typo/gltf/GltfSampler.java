package lib.kasuga.rendering.models.uml.typo.gltf;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Pure glTF TRS interpolation: evaluates an {@link GltfAsset.AnimationClip} into a bone {@link Pose}. */
public final class GltfSampler implements AnimationSampler<GltfAsset.AnimationClip> {

    private final GltfAsset.NodeHierarchy nodes;
    private final Map<Integer, String> nodeBoneNames;

    public GltfSampler(GltfModelData data) {
        Objects.requireNonNull(data, "data");
        this.nodes = data.asset().nodes();
        Map<Integer, String> names = new HashMap<>();
        for (Map.Entry<Integer, Bone> entry : data.boneByNode().entrySet()) {
            names.put(entry.getKey(), entry.getValue().getName());
        }
        this.nodeBoneNames = Map.copyOf(names);
    }

    @Override
    public float duration(GltfAsset.AnimationClip data) {
        return data.duration();
    }

    @Override
    public Pose sample(GltfAsset.AnimationClip data, float time) {
        float absoluteTime = data.startTime() + time;
        Vector3f[] translations = java.util.Arrays.stream(nodes.translations())
                .map(Vector3f::new).toArray(Vector3f[]::new);
        Quaternionf[] rotations = java.util.Arrays.stream(nodes.rotations())
                .map(Quaternionf::new).toArray(Quaternionf[]::new);
        Vector3f[] scales = java.util.Arrays.stream(nodes.scales())
                .map(Vector3f::new).toArray(Vector3f[]::new);
        for (GltfAsset.AnimationTrack track : data.tracks()) {
            if (track.nodeIndex() < 0 || track.nodeIndex() >= nodes.size() || track.times().length == 0) continue;
            int key = segment(track.times(), absoluteTime);
            int next = Math.min(key + 1, track.times().length - 1);
            float frame = track.times()[next] - track.times()[key];
            float amount = next == key || frame <= 1e-8f ? 0f
                    : Math.clamp((absoluteTime - track.times()[key]) / frame, 0f, 1f);
            switch (track.path()) {
                case TRANSLATION -> sampleVector(track, key, next, amount, translations[track.nodeIndex()]);
                case SCALE -> sampleVector(track, key, next, amount, scales[track.nodeIndex()]);
                case ROTATION -> sampleRotation(track, key, next, amount, rotations[track.nodeIndex()]);
            }
        }
        Pose.Builder builder = new Pose.Builder();
        for (int node = 0; node < nodes.size(); node++) {
            String boneName = boneName(node);
            if (boneName == null) continue;
            Matrix4f bindLocal = new Matrix4f().translationRotateScale(nodes.translations()[node],
                    nodes.rotations()[node], nodes.scales()[node]);
            Matrix4f animatedLocal = new Matrix4f().translationRotateScale(
                    translations[node], rotations[node], scales[node]);
            Matrix4f delta = bindLocal.invert().mul(animatedLocal);
            builder.bone(boneName, new Transform().set(delta), ApplyMode.REPLACE);
        }
        return builder.build();
    }

    private String boneName(int nodeIndex) {
        return nodeBoneNames.get(nodeIndex);
    }

    private static int segment(float[] times, float time) {
        int low = 0, high = times.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (times[mid] <= time) low = mid; else high = mid - 1;
        }
        return low;
    }

    private static void sampleVector(GltfAsset.AnimationTrack track, int key, int next,
                                     float amount, Vector3f target) {
        if (track.interpolation() == GltfAsset.Interpolation.CUBIC_SPLINE) {
            target.set(cubic(track, key, next, amount, 0), cubic(track, key, next, amount, 1),
                    cubic(track, key, next, amount, 2));
            return;
        }
        int first = key * 3, second = next * 3;
        float t = track.interpolation() == GltfAsset.Interpolation.STEP ? 0f : amount;
        target.set(mix(track.values()[first], track.values()[second], t),
                mix(track.values()[first + 1], track.values()[second + 1], t),
                mix(track.values()[first + 2], track.values()[second + 2], t));
    }

    private static void sampleRotation(GltfAsset.AnimationTrack track, int key, int next,
                                       float amount, Quaternionf target) {
        if (track.interpolation() == GltfAsset.Interpolation.CUBIC_SPLINE) {
            target.set(cubic(track, key, next, amount, 0), cubic(track, key, next, amount, 1),
                    cubic(track, key, next, amount, 2), cubic(track, key, next, amount, 3)).normalize();
            return;
        }
        int first = key * 4, second = next * 4;
        Quaternionf a = new Quaternionf(track.values()[first], track.values()[first + 1],
                track.values()[first + 2], track.values()[first + 3]).normalize();
        if (track.interpolation() == GltfAsset.Interpolation.STEP || key == next) {
            target.set(a);
        } else {
            Quaternionf b = new Quaternionf(track.values()[second], track.values()[second + 1],
                    track.values()[second + 2], track.values()[second + 3]).normalize();
            target.set(a).slerp(b, amount).normalize();
        }
    }

    private static float cubic(GltfAsset.AnimationTrack track, int key, int next,
                               float amount, int component) {
        int components = track.path().components();
        int value0 = (key * 3 + 1) * components + component;
        if (key == next) return track.values()[value0];
        int tangent0 = (key * 3 + 2) * components + component;
        int tangent1 = (next * 3) * components + component;
        int value1 = (next * 3 + 1) * components + component;
        float duration = Math.max(0f, track.times()[next] - track.times()[key]);
        float t2 = amount * amount, t3 = t2 * amount;
        return (2f * t3 - 3f * t2 + 1f) * track.values()[value0]
                + (t3 - 2f * t2 + amount) * duration * track.values()[tangent0]
                + (-2f * t3 + 3f * t2) * track.values()[value1]
                + (t3 - t2) * duration * track.values()[tangent1];
    }

    private static float mix(float a, float b, float t) { return Math.fma(b - a, t, a); }
}