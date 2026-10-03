package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.infrastructure.Formula;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import lib.kasuga.rendering.models.uml.dynamic.math.Easing;
import lib.kasuga.rendering.models.uml.dynamic.math.Lerp;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;
import java.util.Optional;

/**
 * {@link AnimationSampler} for {@link AnimationClip}: keyframe interpolation for bones, morphs and
 * material frames. Bones and morphs interpolate between keyframes (the time axis shaped by each
 * segment's {@link Easing}); material frames snap to the nearer keyframe.
 *
 * <p>This class owns the whole {@link AnimationClip} sampling logic (formerly the static
 * {@code AnimationSampler} utility, deleted). The {@code time} passed to {@link #sample} is already
 * normalized by the player (loop → modulo, non-loop → clamp) — this class performs <em>pure
 * interpolation only</em>, no loop semantics.
 */
public final class ClipSampler implements AnimationSampler<AnimationClip> {

    public static final ClipSampler INSTANCE = new ClipSampler();

    private ClipSampler() {
    }

    @Override
    public float duration(AnimationClip data) {
        return data.durationSeconds();
    }

    @Override
    public Pose sample(AnimationClip data, float time) {
        return sample(data, time, null);
    }

    /**
     * Samples the clip; when {@code namespace} is non-null, formula {@link AnimationClip.FunctionTrack}s
     * are evaluated against it (assign the clip's query.* variables into this namespace before calling).
     * The formula branch evaluates each non-blank axis via {@code namespace.decodeFormula(axis).getResult()}.
     * Blank axes fall back to the identity value for the channel (rotate 0 / translate 0 / scale 1).
     */
    public Pose sample(AnimationClip data, float time, @Nullable Namespace namespace) {
        Pose.Builder builder = new Pose.Builder();
        for (AnimationClip.BoneTrack track : data.bones()) {
            Transform transform = sampleBone(track.keyframes(), time);
            if (transform != null) {
                builder.bone(track.bone(), transform, ApplyMode.REPLACE);
            }
        }
        for (AnimationClip.MorphTrack track : data.morphs()) {
            builder.morph(track.morph(), sampleMorph(track.keyframes(), time), 1f);
        }
        for (AnimationClip.FrameTrack track : data.frames()) {
            builder.frame(track.material(), sampleFrame(track.keyframes(), time));
        }
        if (namespace != null) {
            for (AnimationClip.FunctionTrack track : data.functions()) {
                TransformDefinition def = sampleFunction(track, namespace);
                if (def != null) {
                    builder.bone(track.bone(), toTransform(def), ApplyMode.REPLACE);
                }
            }
        }
        return builder.build();
    }

    private static TransformDefinition sampleFunction(AnimationClip.FunctionTrack track, Namespace ns) {
        float rx = eval(track.x(), 0f, ns);
        float ry = eval(track.y(), 0f, ns);
        float rz = eval(track.z(), 0f, ns);
        switch (track.channel()) {
            case ROTATE -> { return new TransformDefinition(Optional.empty(), Optional.of(new Vector3f(rx, ry, rz)), Optional.empty()); }
            case TRANSLATE -> { return new TransformDefinition(Optional.of(new Vector3f(rx, ry, rz)), Optional.empty(), Optional.empty()); }
            case SCALE -> { return new TransformDefinition(Optional.empty(), Optional.empty(), Optional.of(new Vector3f(rx, ry, rz))); }
        }
        return null;
    }

    private static float eval(String axis, float fallback, Namespace ns) {
        if (axis == null || axis.isBlank()) return fallback;
        try {
            return ns.decodeFormula(axis).getResult();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static Transform sampleBone(List<AnimationClip.Keyframe> keyframes, float t) {
        if (keyframes.isEmpty()) {
            return null;
        }
        if (t <= keyframes.get(0).time()) {
            return toTransform(keyframes.get(0).transform());
        }
        AnimationClip.Keyframe last = keyframes.get(keyframes.size() - 1);
        if (t >= last.time()) {
            return toTransform(last.transform());
        }
        for (int i = 0; i < keyframes.size() - 1; i++) {
            AnimationClip.Keyframe from = keyframes.get(i);
            AnimationClip.Keyframe to = keyframes.get(i + 1);
            if (t >= from.time() && t < to.time()) {
                float span = to.time() - from.time();
                float u = span <= 0f ? 1f : (t - from.time()) / span;
                Easing easing = from.easing() != null ? from.easing() : Easing.linear();
                float eased = easing.apply(u);
                return Lerp.lerpInto(toTransform(from.transform()), toTransform(to.transform()), eased, new Transform());
            }
        }
        return toTransform(last.transform());
    }

    private static float sampleMorph(List<AnimationClip.MorphKeyframe> keyframes, float t) {
        if (keyframes.isEmpty()) {
            return 0f;
        }
        if (t <= keyframes.get(0).time()) {
            return keyframes.get(0).value();
        }
        AnimationClip.MorphKeyframe last = keyframes.get(keyframes.size() - 1);
        if (t >= last.time()) {
            return last.value();
        }
        for (int i = 0; i < keyframes.size() - 1; i++) {
            AnimationClip.MorphKeyframe from = keyframes.get(i);
            AnimationClip.MorphKeyframe to = keyframes.get(i + 1);
            if (t >= from.time() && t < to.time()) {
                float span = to.time() - from.time();
                float u = span <= 0f ? 1f : (t - from.time()) / span;
                Easing easing = from.easing() != null ? from.easing() : Easing.linear();
                return Lerp.lerp(from.value(), to.value(), easing.apply(u));
            }
        }
        return last.value();
    }

    private static int sampleFrame(List<AnimationClip.FrameKeyframe> keyframes, float t) {
        if (keyframes.isEmpty()) {
            return 0;
        }
        AnimationClip.FrameKeyframe first = keyframes.get(0);
        if (t <= first.time()) {
            return first.frame();
        }
        AnimationClip.FrameKeyframe last = keyframes.get(keyframes.size() - 1);
        if (t >= last.time()) {
            return last.frame();
        }
        for (int i = 0; i < keyframes.size() - 1; i++) {
            AnimationClip.FrameKeyframe from = keyframes.get(i);
            AnimationClip.FrameKeyframe to = keyframes.get(i + 1);
            if (t >= from.time() && t < to.time()) {
                float span = to.time() - from.time();
                float u = span <= 0f ? 1f : (t - from.time()) / span;
                return u < 0.5f ? from.frame() : to.frame();
            }
        }
        return last.frame();
    }

    /** Mirrors {@code DefinitionStateMachineFactory.buildTransform}: translate → rotate(degrees) → scale. */
    public static Transform toTransform(TransformDefinition def) {
        Transform transform = new Transform();
        transform.translate(def.translate().x, def.translate().y, def.translate().z);
        transform.rotate(def.rotate().x, def.rotate().y, def.rotate().z, true);
        transform.scale(def.scale().x, def.scale().y, def.scale().z);
        return transform;
    }
}