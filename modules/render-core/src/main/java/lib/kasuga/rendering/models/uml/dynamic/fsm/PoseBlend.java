package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.math.Transform;

import java.util.HashSet;
import java.util.Set;

/**
 * Cross-fade blend of two {@link Pose}s at alpha {@code [0,1]}. Pure function shared by the main-thread pose
 * composition ({@link Layer#activePose()}) and the render-thread sampler ({@code FsmPoseDriver#sample}) so both
 * paths produce identical cross-fades.
 *
 * <p>Morphs/bones are linearly interpolated; frames snap to the nearer end (there is no meaningful
 * interpolation between material-frame indices).
 */
public final class PoseBlend {

    private PoseBlend() {
    }

    /**
     * Blend {@code from} → {@code to} by {@code alpha}. {@code alpha<=0} returns {@code from}; {@code alpha>=1}
     * returns {@code to}; otherwise a new interpolated pose. Channels present on only one side pass through
     * (the missing side reads as the identity: morph value 0 / factor 1, bone transform from the present side).
     */
    public static Pose blend(Pose from, Pose to, float alpha) {
        if (alpha <= 0f) {
            return from;
        }
        if (alpha >= 1f) {
            return to;
        }

        Pose.Builder builder = new Pose.Builder();

        Set<Object> morphKeys = new HashSet<>();
        morphKeys.addAll(from.morphs().keySet());
        morphKeys.addAll(to.morphs().keySet());
        for (Object key : morphKeys) {
            Pose.Morph mf = from.morphs().get(key);
            Pose.Morph mt = to.morphs().get(key);
            float valueFrom = mf != null ? mf.value() : 0f;
            float valueTo = mt != null ? mt.value() : 0f;
            float factorFrom = mf != null ? mf.factor() : 1f;
            float factorTo = mt != null ? mt.factor() : 1f;
            builder.morph(key, lerp(valueFrom, valueTo, alpha), lerp(factorFrom, factorTo, alpha));
        }

        Set<String> boneKeys = new HashSet<>();
        boneKeys.addAll(from.bones().keySet());
        boneKeys.addAll(to.bones().keySet());
        Transform scratch = new Transform();
        TransformLerp.Scratch lerpScratch = new TransformLerp.Scratch(); // reused across bones (one alloc per blend)
        for (String key : boneKeys) {
            Pose.Bone bf = from.bones().get(key);
            Pose.Bone bt = to.bones().get(key);
            ApplyMode applyMode = bt != null ? bt.mode() : (bf != null ? bf.mode() : ApplyMode.REPLACE);
            if (bf != null && bt != null) {
                TransformLerp.lerpInto(bf.transform(), bt.transform(), alpha, scratch, lerpScratch);
                builder.bone(key, scratch, applyMode);
            } else if (bf != null) {
                builder.bone(key, bf.transform(), applyMode);
            } else {
                builder.bone(key, bt.transform(), applyMode);
            }
        }

        Set<Object> frameKeys = new HashSet<>();
        frameKeys.addAll(from.frames().keySet());
        frameKeys.addAll(to.frames().keySet());
        for (Object key : frameKeys) {
            Pose.Frame ff = from.frames().get(key);
            Pose.Frame tf = to.frames().get(key);
            int frame;
            if (alpha < 0.5f) {
                frame = ff != null ? ff.frame() : (tf != null ? tf.frame() : 0);
            } else {
                frame = tf != null ? tf.frame() : (ff != null ? ff.frame() : 0);
            }
            builder.frame(key, frame);
        }

        return builder.build();
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
