package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationPlayer;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.dynamic.fsm.State;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdMotion.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VmdSampler Bezier sampling tests (formerly VmdPlayerTest; the player was replaced by the sampler). */
class VmdSamplerTest {

    private static final float EPS = 1e-4f;

    @Test
    void samplesBoneMorphAndDurationAtSeconds() {
        VmdMotion motion = motion();
        VmdSampler sampler = new VmdSampler(new Vector3f(0.5f));

        Pose pose = sampler.sample(motion, 5f / VmdSampler.FRAMES_PER_SECOND); // frame 5 of 0..10

        // root: translation 0→(20,40,60) × 0.5 scale at linear midpoint → (5,10,15)
        assertEquals(new Vector3f(5, 10, 15), pose.bones().get("root").transform().getPosition());
        assertEquals(ApplyMode.REPLACE, pose.bones().get("root").mode());
        assertEquals(0.5f, pose.morphs().get("smile").value(), EPS);
        assertEquals(10f / VmdSampler.FRAMES_PER_SECOND, sampler.duration(motion), EPS);
    }

    @Test
    void clampsBeforeFirstAndAfterLastKeyframe() {
        VmdMotion motion = motion();
        VmdSampler sampler = new VmdSampler();
        Pose before = sampler.sample(motion, -1f); // clamped to frame 0
        Pose after = sampler.sample(motion, 100f); // clamped to frame 10
        assertEquals(0f, before.bones().get("root").transform().getPosition().x, EPS);
        assertEquals(new Vector3f(20, 40, 60), after.bones().get("root").transform().getPosition());
    }

    @Test
    void singleKeyframeTrackIsConstant() {
        VmdMotion motion = new VmdMotion("sig", "model",
                Map.of("root", List.of(new BoneKeyframe(0, new Vector3f(1, 2, 3), new Quaternionf(), null))),
                Map.of(), List.of(), List.of(), List.of(), List.of(), new byte[0]);
        VmdSampler sampler = new VmdSampler();
        Pose pose = sampler.sample(motion, 0.5f);
        assertEquals(new Vector3f(1, 2, 3), pose.bones().get("root").transform().getPosition());
    }

    @Test
    void ikStatesSampledFromPropertyTrack() {
        VmdMotion motion = motion();
        VmdSampler sampler = new VmdSampler();
        // property: frame 0 → visible true, iki enabled; frame 10 → visible false, iki disabled
        Map<String, Boolean> early = sampler.sampleIkStates(motion, 0f);
        Map<String, Boolean> late = sampler.sampleIkStates(motion, 1f);
        assertTrue(early.getOrDefault("iki", false));
        assertFalse(late.getOrDefault("iki", true));
        assertEquals(early, sampler.sample(motion, 0).ikEnabled());
        assertEquals(late, sampler.sample(motion, 1).ikEnabled());
    }

    @Test
    void playsThroughAnimationPlayer() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        AnimationPlayer<VmdMotion> player = new AnimationPlayer<>(instance);
        instance.setPoseDriver(player);
        VmdMotion motion = motion();
        player.play(new VmdSampler(new Vector3f(0.5f)), motion, false);
        player.tick(5f / VmdSampler.FRAMES_PER_SECOND); // 0.1667s → frame 5
        player.sample(1f);
        assertEquals(new Vector3f(5, 10, 15),
                instance.getSkeletonInstance().getTransforms().values().iterator().next().getPosition());
    }

    @Test
    void worksAsFsmClipState() {
        // Clip-as-State: a state references VMD via VmdSampler; the machine samples it.
        RecordingSink sink = new RecordingSink();
        VmdMotion motion = motion();
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("l", layer -> {
                    State<Object> hold = layer.state("hold").morph("smile", 0f);
                    State<Object> dance = layer.state("dance").clip(new VmdSampler(new Vector3f(0.5f)), motion, true);
                    layer.initial(hold);
                    layer.transition("go", hold, dance).when(ctx -> true);
                })
                .sink(sink)
                .build();
        machine.tick(1f / 20f); // instant fire → dance active
        assertTrue(sink.last != null && sink.last.bones().containsKey("root"),
                "VMD bone must land in the layer blender");
    }

    /** Captures the last flushed {@link lib.kasuga.rendering.models.uml.dynamic.fsm.Blender}. */
    static final class RecordingSink implements lib.kasuga.rendering.models.uml.dynamic.fsm.PoseSink {
        lib.kasuga.rendering.models.uml.dynamic.fsm.Blender last;

        @Override
        public void apply(lib.kasuga.rendering.models.uml.dynamic.fsm.Blender blender) {
            last = blender;
        }
    }

    private static VmdMotion motion() {
        BoneInterpolation interpolation = BoneInterpolation.from(linearBoneInterpolation());
        List<BoneKeyframe> bones = List.of(
                new BoneKeyframe(0, new Vector3f(), new Quaternionf(), interpolation),
                new BoneKeyframe(10, new Vector3f(20, 40, 60), new Quaternionf(), interpolation));
        List<MorphKeyframe> morphs = List.of(new MorphKeyframe(0, 0), new MorphKeyframe(10, 1));
        List<PropertyKeyframe> properties = List.of(
                new PropertyKeyframe(0, true, List.of(new IkState("iki", true))),
                new PropertyKeyframe(10, false, List.of(new IkState("iki", false))));
        return new VmdMotion("Vocaloid Motion Data 0002", "model", Map.of("root", bones),
                Map.of("smile", morphs), List.of(), List.of(), List.of(), properties, new byte[0]);
    }

    private static byte[] linearBoneInterpolation() {
        byte[] raw = new byte[64];
        for (int channel = 0; channel < 4; channel++) {
            raw[channel] = 20;
            raw[channel + 4] = 20;
            raw[channel + 8] = 107;
            raw[channel + 12] = 107;
        }
        raw[17] = 20;
        raw[18] = 20;
        return raw;
    }
}
