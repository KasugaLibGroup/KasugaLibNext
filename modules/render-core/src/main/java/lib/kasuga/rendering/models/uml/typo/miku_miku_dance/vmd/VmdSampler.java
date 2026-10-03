package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdMotion.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * {@link AnimationSampler} for {@link VmdMotion}: per-channel cubic Bezier interpolation into a bone
 * {@link Pose}. Replaces the former {@code VmdPlayer} (deleted) — the same sampling math now feeds the
 * animation pipeline ({@code AnimationPlayer} / FSM clip states) instead of writing a model directly.
 *
 * <p>Time is seconds (the pipeline's unit); VMD frames convert at {@link #FRAMES_PER_SECOND}. Bone
 * transforms are local (REPLACE) — the pipeline flushes them through {@code PoseSink.applyPose}.
 * Morph weights are linear. Camera / light / shadow tracks are not part of a model pose and are not
 * sampled here (raw keyframes stay available on {@link VmdMotion}).
 *
 * <p>MMD-specific per-frame state that the pose has no channel for — IK enable/disable — is exposed via
 * {@link #sampleIkStates(VmdMotion, float)} for the caller to apply ({@code setIkEnabled}).
 */
public final class VmdSampler implements AnimationSampler<VmdMotion> {

    /** MMD's fixed animation frame rate. */
    public static final float FRAMES_PER_SECOND = 30f;

    private final Vector3f translationScale;

    public VmdSampler() {
        this(new Vector3f(1f));
    }

    /** @param translationScale multiplies VMD bone translations (MMD units → model units). */
    public VmdSampler(Vector3f translationScale) {
        this.translationScale = new Vector3f(Objects.requireNonNull(translationScale, "translationScale"));
    }

    @Override
    public float duration(VmdMotion data) {
        return maxFrame(data) / FRAMES_PER_SECOND;
    }

    @Override
    public Pose sample(VmdMotion data, float time) {
        double frame = (double) Math.max(0f, time) * FRAMES_PER_SECOND;
        Pose.Builder builder = new Pose.Builder();
        data.boneTracks().forEach((name, track) ->
                builder.bone(name, sampleBone(track, frame), ApplyMode.REPLACE));
        data.morphTracks().forEach((name, track) ->
                builder.morph(name, sampleMorph(track, frame), 1f));
        return builder.build();
    }

    /**
     * IK enable/disable state at the given time (from the last property keyframe ≤ {@code time}). The pose
     * shape has no IK channel, so the caller applies these ({@code SkeletonInstance.setIkEnabled}) after
     * flushing the sampled pose. Empty when the motion has no property track.
     */
    public Map<String, Boolean> sampleIkStates(VmdMotion data, float time) {
        double frame = (double) Math.max(0f, time) * FRAMES_PER_SECOND;
        List<PropertyKeyframe> track = data.propertyTrack();
        if (track.isEmpty()) {
            return Map.of();
        }
        PropertyKeyframe sample = segment(track, frame, PropertyKeyframe::frame).before;
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (IkState state : sample.ikStates()) {
            result.put(state.name(), state.enabled());
        }
        return result;
    }

    private Transform sampleBone(List<BoneKeyframe> track, double frame) {
        Segment<BoneKeyframe> segment = segment(track, frame, BoneKeyframe::frame);
        if (segment.before == segment.after) {
            return boneTransform(segment.before.translation(), segment.before.rotation());
        }
        float linear = segment.progress(frame, BoneKeyframe::frame);
        BoneInterpolation curve = segment.after.interpolation();
        Vector3f a = segment.before.translation();
        Vector3f b = segment.after.translation();
        Vector3f translation = new Vector3f(
                lerp(a.x, b.x, curve.x().evaluate(linear)),
                lerp(a.y, b.y, curve.y().evaluate(linear)),
                lerp(a.z, b.z, curve.z().evaluate(linear)));
        Quaternionf rotation = new Quaternionf(segment.before.rotation())
                .slerp(segment.after.rotation(), curve.rotation().evaluate(linear));
        return boneTransform(translation, rotation);
    }

    private Transform boneTransform(Vector3f translation, Quaternionf rotation) {
        return new Transform().translate(new Vector3f(translation).mul(translationScale)).mul(rotation);
    }

    private static float sampleMorph(List<MorphKeyframe> track, double frame) {
        Segment<MorphKeyframe> segment = segment(track, frame, MorphKeyframe::frame);
        if (segment.before == segment.after) {
            return segment.before.weight();
        }
        return lerp(segment.before.weight(), segment.after.weight(),
                segment.progress(frame, MorphKeyframe::frame));
    }

    private static long maxFrame(VmdMotion motion) {
        long max = 0;
        for (List<BoneKeyframe> track : motion.boneTracks().values()) {
            max = Math.max(max, lastFrame(track, BoneKeyframe::frame));
        }
        for (List<MorphKeyframe> track : motion.morphTracks().values()) {
            max = Math.max(max, lastFrame(track, MorphKeyframe::frame));
        }
        max = Math.max(max, lastFrame(motion.cameraTrack(), CameraKeyframe::frame));
        max = Math.max(max, lastFrame(motion.lightTrack(), LightKeyframe::frame));
        max = Math.max(max, lastFrame(motion.shadowTrack(), ShadowKeyframe::frame));
        return Math.max(max, lastFrame(motion.propertyTrack(), PropertyKeyframe::frame));
    }

    private static <T> Segment<T> segment(List<T> track, double frame, ToLongFunction<T> frameGetter) {
        if (track.isEmpty()) {
            throw new IllegalArgumentException("Cannot sample an empty VMD track");
        }
        if (frame <= frameGetter.applyAsLong(track.getFirst())) {
            return new Segment<>(track.getFirst(), track.getFirst());
        }
        if (frame >= frameGetter.applyAsLong(track.getLast())) {
            return new Segment<>(track.getLast(), track.getLast());
        }
        int low = 0, high = track.size() - 1;
        while (low + 1 < high) {
            int mid = (low + high) >>> 1;
            if (frameGetter.applyAsLong(track.get(mid)) <= frame) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return new Segment<>(track.get(low), track.get(high));
    }

    private static <T> long lastFrame(List<T> track, ToLongFunction<T> frameGetter) {
        return track.isEmpty() ? 0 : frameGetter.applyAsLong(track.getLast());
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private record Segment<T>(T before, T after) {
        float progress(double frame, ToLongFunction<T> frameGetter) {
            long start = frameGetter.applyAsLong(before);
            long end = frameGetter.applyAsLong(after);
            return end == start ? 0f : (float) ((frame - start) / (end - start));
        }
    }
}