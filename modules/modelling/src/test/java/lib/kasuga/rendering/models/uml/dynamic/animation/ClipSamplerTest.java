package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import lib.kasuga.rendering.models.uml.dynamic.math.Easing;
import lib.kasuga.rendering.models.uml.dynamic.math.Lerp;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ClipSampler keyframe interpolation tests (formerly AnimationSamplerTest; loop assertions moved to AnimationPlayerTest). */
class ClipSamplerTest {

    private static final float EPS = 1e-3f;

    private static AnimationClip.Keyframe kf(float time, float rotYDeg, String easing) {
        return new AnimationClip.Keyframe(time,
                new TransformDefinition(new Vector3f(), new Vector3f(0f, rotYDeg, 0f), new Vector3f(1f, 1f, 1f)),
                easing == null ? null : Easing.byName(easing));
    }

    private static AnimationClip clipWithBone(float duration, List<AnimationClip.Keyframe> keyframes) {
        return new AnimationClip(Id.parse("kasuga_lib:test"), duration,
                List.of(new AnimationClip.BoneTrack("bone_a", keyframes)), List.of(), List.of(), List.of());
    }

    private static double rotAngle(Pose pose) {
        return Math.toDegrees(pose.bones().get("bone_a").transform().getRotation().angle());
    }

    @Test
    void linearInterpolationAtMidpoint() {
        AnimationClip clip = clipWithBone(1f, List.of(kf(0f, 0f, "linear"), kf(1f, 90f, "linear")));
        Pose pose = ClipSampler.INSTANCE.sample(clip, 0.5f);
        Pose.Bone bone = pose.bones().get("bone_a");
        assertEquals(45.0, rotAngle(pose), 0.1);
        assertEquals(ApplyMode.REPLACE, bone.mode());
    }

    @Test
    void easingShapesTimeAxis() {
        // The segment's easing is taken from the segment's start keyframe.
        // ease_in_quad(0.25) = 0.0625 → angle = 90 * 0.0625 = 5.625°
        AnimationClip clip = clipWithBone(1f, List.of(kf(0f, 0f, "ease_in_quad"), kf(1f, 90f, "linear")));
        Pose pose = ClipSampler.INSTANCE.sample(clip, 0.25f);
        assertEquals(5.625, rotAngle(pose), 0.1);
    }

    @Test
    void clampsBeforeFirstAndAfterLastKeyframe() {
        AnimationClip clip = clipWithBone(2f, List.of(kf(0.5f, 30f, null), kf(1f, 90f, null)));
        assertEquals(30.0, rotAngle(ClipSampler.INSTANCE.sample(clip, 0f)), EPS);
        assertEquals(90.0, rotAngle(ClipSampler.INSTANCE.sample(clip, 1.5f)), EPS);
    }

    @Test
    void singleKeyframeIsConstant() {
        AnimationClip clip = clipWithBone(1f, List.of(kf(0f, 45f, null)));
        assertEquals(45.0, rotAngle(ClipSampler.INSTANCE.sample(clip, 0.7f)), EPS);
    }

    @Test
    void emptyClipYieldsEmptyPose() {
        AnimationClip clip = new AnimationClip(Id.parse("kasuga_lib:empty"), 1f, List.of(), List.of(), List.of(), List.of());
        assertTrue(ClipSampler.INSTANCE.sample(clip, 0.5f).isEmpty());
    }

    @Test
    void morphTrackInterpolates() {
        AnimationClip clip = new AnimationClip(Id.parse("kasuga_lib:morph"), 1f, List.of(),
                List.of(new AnimationClip.MorphTrack("blink",
                        List.of(new AnimationClip.MorphKeyframe(0f, 0f, Easing.linear()),
                                new AnimationClip.MorphKeyframe(1f, 1f, Easing.linear())))),
                List.of(), List.of());
        Pose pose = ClipSampler.INSTANCE.sample(clip, 0.5f);
        assertEquals(0.5f, pose.morphs().get("blink").value(), EPS);
        assertEquals(1f, pose.morphs().get("blink").factor(), EPS);
    }

    @Test
    void frameTrackSnapsToNearerKeyframe() {
        AnimationClip clip = new AnimationClip(Id.parse("kasuga_lib:frame"), 1f, List.of(), List.of(),
                List.of(new AnimationClip.FrameTrack("mat_a",
                        List.of(new AnimationClip.FrameKeyframe(0f, 1), new AnimationClip.FrameKeyframe(1f, 5)))),
                List.of());
        assertEquals(1, ClipSampler.INSTANCE.sample(clip, 0.49f).frames().get("mat_a").frame());
        assertEquals(5, ClipSampler.INSTANCE.sample(clip, 0.51f).frames().get("mat_a").frame());
    }

    @Test
    void transformDefinitionIncludesTranslateAndScale() {
        AnimationClip.Keyframe kf = new AnimationClip.Keyframe(0f,
                new TransformDefinition(new Vector3f(1f, 2f, 3f), new Vector3f(), new Vector3f(2f, 2f, 2f)),
                Easing.linear());
        Transform t = ClipSampler.toTransform(kf.transform());
        assertEquals(1f, t.getPosition().x, EPS);
        assertEquals(2f, t.getPosition().y, EPS);
        assertEquals(3f, t.getPosition().z, EPS);
        assertEquals(2f, Lerp.getScale(t).x, EPS);
    }

    @Test
    void unknownEasingFallsBackToLinear() {
        AnimationClip clip = clipWithBone(1f, List.of(kf(0f, 0f, "not_a_real_easing"), kf(1f, 90f, "not_a_real_easing")));
        Pose pose = ClipSampler.INSTANCE.sample(clip, 0.5f);
        assertEquals(45.0, rotAngle(pose), 0.1);
    }

    @Test
    void emptyBoneTrackIsAbsent() {
        AnimationClip clip = new AnimationClip(Id.parse("kasuga_lib:nobone"), 1f,
                List.of(new AnimationClip.BoneTrack("bone_a", List.of())), List.of(), List.of(), List.of());
        assertTrue(ClipSampler.INSTANCE.sample(clip, 0.25f).bones().isEmpty());
    }

    @Test
    void durationIsClipDurationSeconds() {
        AnimationClip clip = clipWithBone(2.5f, List.of(kf(0f, 0f, "linear"), kf(1f, 90f, "linear")));
        assertEquals(2.5f, ClipSampler.INSTANCE.duration(clip), EPS);
    }
}