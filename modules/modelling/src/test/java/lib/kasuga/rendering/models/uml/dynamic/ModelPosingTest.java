package lib.kasuga.rendering.models.uml.dynamic;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import lib.kasuga.formula.Code;
import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationLibrary;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationTimeline;
import lib.kasuga.rendering.models.uml.dynamic.fsm.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.PoseDefinition;
import lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsBuilder;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.skeleton.*;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics.*;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdMotion;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdSampler;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vpd.VpdPose;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vpd.VpdSampler;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelPosingTest {
    @Test
    void clipBindingPreservesTheFsmFormulaNamespace() {
        Namespace namespace = new Namespace(Code.ROOT_NAMESPACE);
        var clip = new AnimationLibrary().register("context", new AnimationSampler<Integer>() {
            public float duration(Integer data) { return 1; }
            public Pose sample(Integer data, float seconds) { return Pose.empty(); }
            public Pose sample(Integer data, float seconds, Namespace context) {
                assertSame(namespace, context);
                return Pose.bone("root", new Transform().translate(data, 0, 0));
            }
        }, 5);
        assertTrue(AnimationLibrary.SAMPLER.sample(clip, 0).isEmpty());
        assertEquals(5, AnimationLibrary.SAMPLER.sample(clip, 0, namespace)
                .bones().get("root").transform().getPosition().x, 1e-5);
    }

    @Test
    void arbitrarySamplerAndStaticPoseShareOneSinkAndIndependentInstanceClocks() {
        ModelInstance first = ModelInstanceFixture.minimal();
        Model model = first.getModel();
        model.getAnimations().register("move", new AnimationSampler<Integer>() {
            public float duration(Integer distance) { return 2; }
            public Pose sample(Integer distance, float seconds) {
                return Pose.bone("root", new Transform().translate(distance * seconds, 0, 0));
            }
        }, 3);
        ModelInstance second = new ModelInstance(model, null, null, null, null, null);
        assertNull(model.getModelData());
        assertTrue(first.getPosing().play("move", false));
        assertTrue(second.getPosing().play("move", false));
        first.animate(1); first.sample(1); second.sample(1);
        assertEquals(3, localX(first), 1e-5);
        assertEquals(0, localX(second), 1e-5);
        assertFalse(first.getPosing().play("missing", false));
        assertEquals("move", first.getPosing().currentClip());

        // A static pose from a format sampler can pose the same plain model.
        Pose staticPose = VpdSampler.INSTANCE.sample(new VpdPose("ignored source label",
                Map.of("root", new Transform().translate(7, 0, 0)), Map.of()), 0);
        first.getPosing().pose(staticPose); first.sample(1);
        assertEquals(7, localX(first), 1e-5);
        first.getPosing().stop(); first.sample(1);
        assertTrue(first.getSkeletonInstance().getTransforms().isEmpty());
        assertEquals(0, localX(second), 1e-5);
    }

    @Test
    void namedFollowersDoNotAdvanceTheirSharedTimeline() {
        ModelInstance first = ModelInstanceFixture.minimal();
        first.getModel().getAnimations().register("hold", VpdSampler.INSTANCE,
                new VpdPose("", Map.of("root", new Transform().translate(4, 0, 0)), Map.of()));
        var timeline = new AnimationTimeline();
        timeline.play(2, true);
        first.getPosing().follow("hold", timeline);
        first.animate(1);
        assertEquals(0, timeline.currentTime(), 1e-5);
        first.sample(1);
        assertEquals(4, localX(first), 1e-5);
    }

    @Test
    void vmdIkPropertiesReachPlainSkeletonAndOnlyOwnedSwitchesReset() {
        Bone root = new Bone("root", new Transform(), null);
        Bone tip = new Bone("tip", new Transform().translate(1, 0, 0), null);
        Bone target = new Bone("target", new Transform().translate(1, 0, 0), null);
        root.setChildren(new Bone[]{tip, target}); tip.setParent(root); target.setParent(root);
        Skeleton skeleton = new Skeleton(new Bone[]{root, tip, target}, root, new Anchor[0], null, new Transform());
        new SkeletonDynamicsBuilder(skeleton)
                .ik(new IkChain("arm", target, tip, List.of(new IkLink(root))))
                .ik(new IkChain("manual", target, tip, List.of(new IkLink(root)))).attach();
        Model model = new Model(new Vertex[0], new Mesh[0], skeleton.getBones(), skeleton,
                ModelInstanceFixture.minimal().getModel().getMaterialSet(), MeshMode.TRIANGLES, null, null);
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        instance.getSkeletonInstance().setIkEnabled("manual", false);
        VmdMotion motion = new VmdMotion("sig", "different source model", Map.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(new VmdMotion.PropertyKeyframe(0, true,
                List.of(new VmdMotion.IkState("arm", false)))), new byte[0]);
        model.getAnimations().register("switch", new VmdSampler(), motion);
        instance.getPosing().play("switch", false); instance.sample(1);
        assertFalse(instance.getSkeletonInstance().isIkEnabled("arm"));
        assertFalse(instance.getSkeletonInstance().isIkEnabled("manual"));
        instance.getPosing().stop(); instance.sample(1);
        assertTrue(instance.getSkeletonInstance().isIkEnabled("arm"));
        assertFalse(instance.getSkeletonInstance().isIkEnabled("manual"));
    }

    @Test
    void ikSwitchesBlendAsDiscreteChannelsAndRoundTripThroughFsmCodec() {
        Pose off = new Pose.Builder().ikEnabled("arm", false).build();
        Pose on = new Pose.Builder().ikEnabled("arm", true).build();
        assertFalse(PoseBlend.blend(off, on, 0.49f).ikEnabled().get("arm"));
        assertTrue(PoseBlend.blend(off, on, 0.5f).ikEnabled().get("arm"));
        Blender blender = new Blender();
        blender.applyLayer(BlendMode.OVERRIDE, off, 1, BoneMask.all());
        blender.applyLayer(BlendMode.BASE, on, 1, BoneMask.all());
        assertFalse(blender.ikEnabled().get("arm").enabled());
        assertFalse(blender.isEmpty());
        blender.reset(); assertTrue(blender.isEmpty());
        var json = JsonParser.parseString("{\"ik_enabled\":{\"arm\":false}}");
        var decoded = PoseDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(Map.of("arm", false), decoded.ikEnabled());
        var encoded = PoseDefinition.CODEC.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow();
        assertEquals(decoded, PoseDefinition.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    private static float localX(ModelInstance instance) {
        Transform local = instance.getSkeletonInstance().getTransforms().get(instance.getModel().getSkeleton().getRoot());
        return local == null ? 0 : local.getPosition().x;
    }
}
