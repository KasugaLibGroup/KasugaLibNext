package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsBuilder;
import lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics.*;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkeletonDynamicsTest {
    @Test
    void alreadySatisfiedPmxStyleKneeKeepsTheNeutralPose() {
        Bone root = bone("root", 0, 0, 0), base = bone("base", 0, 0, 0);
        Bone knee = bone("knee", 0.01f, -0.46f, 0.03f), tip = bone("tip", 0.004f, -0.56f, 0.065f);
        Bone target = bone("target", 0.014f, -1.02f, 0.095f);
        connect(root, base, target); connect(base, knee); connect(knee, tip);
        Skeleton skeleton = new Skeleton(new Bone[]{root, base, knee, tip, target}, root,
                new Anchor[0], null, new Transform());
        RotationLimit limit = new RotationLimit(new Vector3f(-(float) Math.PI, 0, 0),
                new Vector3f(-0.0001f, 0, 0));
        Hinge hinge = new Hinge(new Vector3f(1, 0, 0), new Vector3f(0, -0.56f, 0.065f).normalize(),
                -(float) Math.PI, -0.0001f);
        new SkeletonDynamicsBuilder(skeleton).ik(new IkChain("leg", target, tip,
                List.of(new IkLink(base), new IkLink(knee, limit, hinge)))).attach();
        ModelInstance instance = new ModelInstance(model(skeleton), null, null, null, null, null);
        instance.updateImmediate();
        assertEquals(0, position(instance, tip).distance(new Vector3f(0.014f, -1.02f, 0.095f)), 1e-6f);
        assertTrue(instance.getSkeletonInstance().getIkTransforms().isEmpty(),
                "an already satisfied target must not acquire another knee bend solution");
    }

    @Test
    void plainSkeletonSolvesTargetsUnderRotatedAndScaledRoot() {
        Rig rig = arm(null, null);
        Model model = model(rig.skeleton);
        Transform root = new Transform().translate(4, 2, 3).mul(new Quaternionf().rotationY(0.7f)).scale(2, 2, 2);
        ModelInstance instance = new ModelInstance(model, root, null, null, null, null);
        Vector3f target = root.copy().apply(new Vector3f(0, 1, 0));
        assertTrue(instance.getSkeletonInstance().setIkTarget("arm", target, 1f));
        instance.updateImmediate();
        assertEquals(0, position(instance, rig.tip).distance(target), 1e-4f);
        assertTrue(instance.getSkeletonInstance().ikSolveResults().get("arm").satisfied());
    }

    @Test
    void calikoRotorStopsAtConeAndReportsUnreachableTarget() {
        Rig rig = arm(new Rotor((float) Math.toRadians(30)), null);
        ModelInstance instance = new ModelInstance(model(rig.skeleton), null, null, null, null, null);
        instance.getSkeletonInstance().setIkTarget("arm", new Vector3f(0, 1, 0), 1f);
        instance.updateImmediate();
        Vector3f tip = position(instance, rig.tip);
        assertEquals((float) Math.sqrt(3) / 2, tip.x, 1e-4f);
        assertEquals(0.5f, tip.y, 1e-4f);
        assertFalse(instance.getSkeletonInstance().ikSolveResults().get("arm").satisfied());
    }

    @Test
    void calikoHingeHonorsSignedRangeAndInverseMapping() {
        Hinge hinge = new Hinge(new Vector3f(0, 0, 1), new Vector3f(1, 0, 0),
                -(float) Math.PI / 2, 0);
        Rig rig = arm(hinge, null);
        ModelInstance instance = new ModelInstance(model(rig.skeleton), null, null, null, null, null);
        Vector3f goal = new Vector3f(1, -1, 0).normalize();
        instance.getSkeletonInstance().setIkTarget("arm", goal, 1f);
        instance.updateImmediate();
        assertEquals(0, position(instance, rig.tip).distance(goal), 1e-4f);
        instance.getSkeletonInstance().setIkTarget("arm", new Vector3f(0, 1, 0), 1f);
        instance.updateImmediate();
        assertEquals(0, position(instance, rig.tip).distance(new Vector3f(1, 0, 0)), 1e-4f);
        assertFalse(instance.getSkeletonInstance().ikSolveResults().get("arm").satisfied());
    }

    @Test
    void sharedDefinitionsKeepPerInstanceTargetsAndCorrectionsIndependent() {
        Rig rig = arm(null, null);
        Model model = model(rig.skeleton);
        ModelInstance first = new ModelInstance(model, null, null, null, null, null);
        ModelInstance second = new ModelInstance(model, null, null, null, null, null);
        first.getSkeletonInstance().setIkTarget("arm", new Vector3f(0, 1, 0), 1f);
        first.updateImmediate(); second.updateImmediate();
        assertEquals(0, position(first, rig.tip).distance(new Vector3f(0, 1, 0)), 1e-4f);
        assertEquals(0, position(second, rig.tip).distance(new Vector3f(1, 0, 0)), 1e-4f);
        assertEquals(0, rig.tip.getTransform().getPosition().distance(new Vector3f(1, 0, 0)), 1e-6f);
    }

    @Test
    void diamondClosesTwoIndependentBranchesAndKeepsSegmentLengths() {
        Skeleton skeleton = diamond(false);
        ModelInstance instance = new ModelInstance(model(skeleton), null, null, null, null, null);
        instance.updateImmediate();
        Vector3f first = position(instance, skeleton.getBoneMap().get("a-tip"));
        Vector3f second = position(instance, skeleton.getBoneMap().get("b-tip"));
        assertEquals(0, first.distance(second), 1e-4f);
        assertEquals(0, first.distance(new Vector3f(0, 2, 0)), 1e-4f);
        assertTrue(instance.getSkeletonInstance().ikSolveResults().get("diamond").satisfied());
        for (String prefix : List.of("a", "b")) {
            assertEquals(Math.sqrt(2), position(instance, skeleton.getBoneMap().get(prefix + "-mid"))
                    .distance(position(instance, skeleton.getBoneMap().get(prefix + "-base"))), 1e-4f);
            assertEquals(Math.sqrt(2), position(instance, skeleton.getBoneMap().get(prefix + "-tip"))
                    .distance(position(instance, skeleton.getBoneMap().get(prefix + "-mid"))), 1e-4f);
        }
    }

    @Test
    void impossibleDiamondReportsClosureResidualWithoutNaN() {
        Skeleton skeleton = diamond(true);
        ModelInstance instance = new ModelInstance(model(skeleton), null, null, null, null, null);
        instance.updateImmediate();
        var result = instance.getSkeletonInstance().ikSolveResults().get("diamond");
        assertFalse(result.satisfied());
        assertEquals(4f, result.residual(), 1e-4f);
        assertTrue(position(instance, skeleton.getBoneMap().get("a-tip")).isFinite());
    }

    @Test
    void externalReaderCanAttachRigAndRejectsForeignBones() {
        Rig rig = arm(null, null);
        new SkeletonDynamicsBuilder(rig.skeleton).read("arm", (source, builder) ->
                builder.ik(new IkChain(source, rig.target, rig.tip, List.of(new IkLink(rig.base))))).attach();
        Bone foreign = bone("foreign", 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new SkeletonDynamicsBuilder(rig.skeleton)
                .ik(new IkChain("invalid", rig.target, rig.tip, List.of(new IkLink(foreign)))).build());
    }

    @Test
    @Tag("box3d")
    void commonRigidBodyRunsWithoutAnyFormatMetadata() {
        Rig rig = arm(null, null);
        Vector3f size = new Vector3f(0.2f), position = new Vector3f(0, 2, 0);
        RigidBody body = new RigidBody("body", "", rig.base, 0, 0, RigidBody.SPHERE,
                size, position, new Vector3f(), 1, 0, 0, 0, 0.5f, RigidBody.DYNAMIC);
        new SkeletonDynamicsBuilder(rig.skeleton).physics(List.of(body), List.of()).attach();
        size.set(50); position.zero();
        assertEquals(0.2f, body.size().x);
        body.position().zero();
        assertEquals(2f, body.position().y);
        ModelInstance instance = new ModelInstance(model(rig.skeleton), null, null, null, null, null);
        try (var ragdoll = instance.enablePhysics()) {
            assertSame(rig.base, ragdoll.bodies().getFirst().source().bone());
            ragdoll.step(1f / 30);
            assertTrue(ragdoll.bodies().getFirst().position().y < 2f);
            assertTrue(position(instance, rig.tip).isFinite());
        }
    }

    @Test
    @Tag("box3d")
    void symmetricProfileCapsulesRemainStableAcrossRegistrationOrders() {
        Bone pelvis = bone("pelvis", 0, 0, 0), left = bone("left", 1, -1, 0), right = bone("right", -1, -1, 0);
        connect(pelvis, left, right);
        Skeleton skeleton = new Skeleton(new Bone[]{pelvis, left, right}, pelvis, new Anchor[0], null, new Transform());
        List<RigidBody> bodies = List.of(pelvis, left, right).stream().map(bone ->
                new RigidBody(bone.getName(), "", bone, 0, 0, RigidBody.CAPSULE, new Vector3f(0.1f),
                        new Vector3f(), new Vector3f(), 1, 0, 0, 0, 0.5f, RigidBody.DYNAMIC)).toList();
        new SkeletonDynamicsBuilder(skeleton).physics(bodies, List.of()).attach();
        var root = new MmdRagdoll.Registration(0, MmdRagdoll.BodyRole.PELVIS);
        var leftBody = new MmdRagdoll.Registration(1, 0, MmdRagdoll.BodyRole.UPPER_LEG);
        var rightBody = new MmdRagdoll.Registration(2, 0, MmdRagdoll.BodyRole.UPPER_LEG);
        for (var profile : List.of(MmdRagdoll.Profile.of(root, leftBody, rightBody),
                MmdRagdoll.Profile.of(rightBody, leftBody, root))) {
            ModelInstance instance = new ModelInstance(model(skeleton), null, null, null, null, null);
            try (var ragdoll = instance.enablePhysics(profile)) {
                assertEquals(0, ragdoll.body(0).orElseThrow().position().distance(new Vector3f(0.5f, -0.5f, 0)), 1e-6f);
            }
        }
    }

    private record Rig(Skeleton skeleton, Bone base, Bone tip, Bone target) {}
    private static Rig arm(IkJoint joint, RotationLimit limit) {
        Bone root = bone("root", 0, 0, 0), base = bone("base", 0, 0, 0);
        Bone tip = bone("tip", 1, 0, 0), target = bone("target", 1, 0, 0);
        connect(root, base, target); connect(base, tip);
        Skeleton skeleton = new Skeleton(new Bone[]{root, base, tip, target}, root,
                new Anchor[0], null, new Transform());
        new SkeletonDynamicsBuilder(skeleton).ik(new IkChain("arm", target, tip,
                List.of(new IkLink(base, limit, joint)))).attach();
        return new Rig(skeleton, base, tip, target);
    }
    private static Skeleton diamond(boolean locked) {
        Bone root = bone("root", 0, 0, 0), target = bone("target", 0, 2, 0);
        Bone a = bone("a-base", 0, 0, 0), am = bone("a-mid", 1, 1, 0), at = bone("a-tip", 1, -1, 0);
        Bone b = bone("b-base", 0, 0, 0), bm = bone("b-mid", -1, 1, 0), bt = bone("b-tip", -1, -1, 0);
        connect(root, a, b, target); connect(a, am); connect(am, at); connect(b, bm); connect(bm, bt);
        Skeleton skeleton = new Skeleton(new Bone[]{root, a, am, at, b, bm, bt, target}, root,
                new Anchor[0], null, new Transform());
        RotationLimit limit = locked ? new RotationLimit(new Vector3f(), new Vector3f()) : null;
        new SkeletonDynamicsBuilder(skeleton)
                .ik(new IkChain("a", target, at, List.of(new IkLink(a, limit), new IkLink(am, limit))))
                .ik(new IkChain("b", target, bt, List.of(new IkLink(b, limit), new IkLink(bm, limit))))
                .diamond(new DiamondConstraint("diamond", "a", "b", 8, 1e-4f)).attach();
        return skeleton;
    }
    private static Bone bone(String name, float x, float y, float z) {
        return new Bone(name, new Transform().translate(x, y, z), null);
    }
    private static void connect(Bone parent, Bone... children) {
        parent.setChildren(children);
        for (Bone child : children) child.setParent(parent);
    }
    private static Model model(Skeleton skeleton) {
        return new Model(new Vertex[0], new Mesh[0], skeleton.getBones(), skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
    }
    private static Vector3f position(ModelInstance instance, Bone bone) {
        return instance.getSkeletonInstance().getAbsoluteTransforms().get(bone).getPosition();
    }
}
