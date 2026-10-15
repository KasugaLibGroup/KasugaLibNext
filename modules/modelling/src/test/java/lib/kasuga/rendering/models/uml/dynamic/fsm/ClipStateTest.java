package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.animation.ClipSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clip-as-State: a state may reference an {@link AnimationClip}; the FSM holds its own clip
 * clock (no {@code AnimationPlayer} — no double-flush) and samples at render rate via
 * {@code lerp(prevClipTime, curClipTime, partialTick)}. Cross-fades between clip states blend sampled
 * poses via {@link PoseBlend}; parallel layers compose a BASE clip with an ADDITIVE static morph;
 * static-pose-only states behave exactly as before.
 */
class ClipStateTest {

    private static final float DT = 1f / 20f;

    /** One bone track "root" rotating 0° → {@code endDeg} linearly over 1 second. */
    private static AnimationClip rotClip(String id, float endDeg) {
        return new AnimationClip(Id.parse(id), 1f,
                List.of(new AnimationClip.BoneTrack("root", List.of(
                        new AnimationClip.Keyframe(0f,
                                new TransformDefinition(new Vector3f(), new Vector3f(), new Vector3f(1f, 1f, 1f)), null),
                        new AnimationClip.Keyframe(1f,
                                new TransformDefinition(new Vector3f(), new Vector3f(0f, endDeg, 0f), new Vector3f(1f, 1f, 1f)), null)))),
                List.of(), List.of(), List.of());
    }

    /** Captures the last flushed {@link Blender} (morphs can't be asserted via the fixture's skeleton). */
    static final class RecordingSink implements PoseSink {
        Blender last;

        @Override
        public void apply(Blender blender) {
            last = blender;
        }
    }

    private static double boneAngleDeg(Blender blender, String bone) {
        Blender.BoneAccum accum = blender.bones().get(bone);
        assertNotNull(accum, "bone '" + bone + "' missing from blender");
        assertNotNull(accum.base, "bone '" + bone + "' has no base write");
        return Math.toDegrees(accum.base.getRotation().angle());
    }

    private static float morphValue(Blender blender, Object id) {
        Blender.MorphAccum accum = blender.morphs().get(id);
        return accum != null ? accum.value() : Float.NaN;
    }

    @Test
    void clipStateBoneAngleAdvancesThroughSink() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("l", layer -> {
                    State<Object> still = layer.state("still").morph("m", 0f);
                    State<Object> spin = layer.state("spin").clip(ClipSampler.INSTANCE, rotClip("test:wheel", 180f), true);
                    layer.initial(still);
                    layer.transition("go", still, spin).when(ctx -> true);
                })
                .sink(new ModelInstancePoseSink(instance))
                .build();

        machine.tick(DT); // instant fire → active=spin, clip clock 0 → root 0°
        machine.tick(DT); // clip clock 0.05 → 9° into a 0→180° loop
        double first = skeletonBoneAngle(instance);
        machine.tick(DT); // clip clock 0.10 → 18°
        double second = skeletonBoneAngle(instance);
        assertTrue(first > 0.0, "the clip pose must land in the skeleton (bone angle " + first + "°)");
        assertTrue(second > first, "a clip state's pose must differ over time (" + first + " → " + second + "°)");
    }

    private static double skeletonBoneAngle(ModelInstance instance) {
        return Math.toDegrees(instance.getSkeletonInstance().getTransforms()
                .values().iterator().next().getRotation().angle());
    }

    @Test
    void clipStatePoseDiffersOverTime() {
        // Drive the machine directly with a recording sink; the sampled bone angle advances each tick.
        RecordingSink sink = new RecordingSink();
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("l", layer -> {
                    State<Object> still = layer.state("still");
                    State<Object> spin = layer.state("spin").clip(ClipSampler.INSTANCE, rotClip("test:wheel", 180f), true);
                    layer.initial(still);
                    layer.transition("go", still, spin).when(ctx -> true);
                })
                .sink(sink)
                .build();

        machine.tick(DT);
        machine.tick(DT);
        double atTick2 = boneAngleDeg(sink.last, "root");
        machine.tick(DT);
        double atTick3 = boneAngleDeg(sink.last, "root");
        assertNotEquals(atTick2, atTick3, 1e-4f);
        assertTrue(atTick3 > atTick2, "clip clock must advance monotonically pre-loop");
    }

    @Test
    void crossFadeFromStaticIntoClipBlends() {
        // still (static morph m=0) → spin (static morph m=1 + clip): cross-fade blends both channels.
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("l", layer -> {
                    State<Object> still = layer.state("still").morph("m", 0f);
                    State<Object> spin = layer.state("spin")
                            .morph("m", 1f)
                            .clip(ClipSampler.INSTANCE, rotClip("test:wheel", 90f), true);
                    layer.initial(still);
                    layer.transition("go", still, spin).when(ctx -> true).crossFade(0.2f);
                })
                .build();
        FsmPoseDriver driver = new FsmPoseDriver(machine, ModelInstanceFixture.minimal());
        driver.tick(DT); // transition in flight, active stays "still" (no clip) → clip target null

        Blender mid = new Blender();
        driver.compose(mid, driver.currentTarget(), 0f);
        assertEquals(0f, morphValue(mid, "m"), 1e-4f, "alpha=0 → static from pose");
        assertTrue(mid.bones().isEmpty(), "from state is static; no bones at alpha=0");

        driver.compose(mid, driver.currentTarget(), 1f);
        float alpha = (DT) / 0.2f;
        assertEquals(alpha, morphValue(mid, "m"), 1e-3f, "morph lerps with the cross-fade");
        assertEquals(0.0, boneAngleDeg(mid, "root"), 1e-3f, "to-state clip sampled at time 0 passes through");
    }

    @Test
    void crossFadeBetweenClipsBlendsSampledPoses() {
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("l", layer -> {
                    State<Object> a = layer.state("a").clip(ClipSampler.INSTANCE, rotClip("test:a", 90f), true);
                    State<Object> b = layer.state("b").clip(ClipSampler.INSTANCE, rotClip("test:b", 180f), true);
                    layer.initial(a);
                    layer.transition("a_to_b", a, b).when(ctx -> true).crossFade(0.2f);
                })
                .build();
        FsmPoseDriver driver = new FsmPoseDriver(machine, ModelInstanceFixture.minimal());
        driver.tick(DT); // transition in flight, active stays "a", clip clock advanced to 0.05

        PoseTarget.LayerTarget layer = driver.currentTarget().layers().get(0);
        assertEquals("a", layer.activeState().id());
        assertNotNull(layer.clip(), "a clip state's clock must be published on the target");

        // partialTick=0.5 → clip time lerp(0, 0.05, 0.5)=0.025 → clipA 2.25°; alpha=(0+0.5·DT)/0.2=0.125
        Blender mid = new Blender();
        driver.compose(mid, driver.currentTarget(), 0.5f);
        double fromAngle = 2.25;
        double toAngle = 0.0;
        double expected = fromAngle + (toAngle - fromAngle) * 0.125f;
        assertEquals(expected, boneAngleDeg(mid, "root"), 0.2, "blended pose interpolates both sampled clips");
    }

    @Test
    void parallelLayersCombineClipBoneAndStaticMorph() {
        RecordingSink sink = new RecordingSink();
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("base", layer -> {
                    State<Object> spin = layer.state("spin").clip(ClipSampler.INSTANCE, rotClip("test:wheel", 180f), true);
                    layer.initial(spin);
                })
                .layer("upper", layer -> {
                    layer.additive();
                    State<Object> wink = layer.state("wink").morph("m", 0.5f);
                    layer.initial(wink);
                })
                .sink(sink)
                .build();

        machine.tick(DT);
        machine.tick(DT);

        assertTrue(sink.last.bones().containsKey("root"), "BASE clip layer contributes the bone channel");
        assertEquals(0.5f, morphValue(sink.last, "m"), 1e-4f, "ADDITIVE static morph lands in the same blender");
    }

    @Test
    void staticPoseOnlyStateUnchanged() {
        // No clip anywhere: pose composition is byte-for-byte the pre-clip behavior.
        RecordingSink sink = new RecordingSink();
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("l", layer -> {
                    State<Object> idle = layer.state("idle").morph("m", 0f);
                    State<Object> active = layer.state("active").morph("m", 1f);
                    layer.initial(idle);
                    layer.transition("t", idle, active).when(ctx -> true).crossFade(DT);
                })
                .sink(sink)
                .build();
        FsmPoseDriver driver = new FsmPoseDriver(machine, ModelInstanceFixture.minimal());
        driver.tick(DT); // cross-fade in flight

        Blender mid = new Blender();
        driver.compose(mid, driver.currentTarget(), 0.5f);
        assertEquals(0.5f, morphValue(mid, "m"), 1e-4f, "static cross-fade blends exactly as before");
        assertTrue(mid.bones().isEmpty());

        machine.tick(DT); // completes → active=active
        assertTrue(machine.layer("l").clipTarget() == null, "static states carry no clip target");
    }
}