package lib.kasuga.rendering.models.uml.typo.miku_miku_dance;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.IkTargetModule;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.*;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MmdSkeletonCompatibilityTest {
    @Test
    void evaluatesGrantTranslationRotationAndFixedAxis() {
        Bone root = bone("root", new Transform(), data("root", -1, plainFlags(), null, null, null));
        Bone source = bone("source", new Transform(), data("source", 0, plainFlags(), null, null, null));
        boolean[] grantBits = new boolean[6];
        grantBits[0] = true;
        grantBits[1] = true;
        Bone grant = bone("grant", new Transform(), data("grant", 0,
                flags(false, grantBits), new ParentBoneInherit(1, 0.5f), null, null));
        boolean[] axisBits = new boolean[6];
        axisBits[2] = true;
        Bone axis = bone("axis", new Transform(), data("axis", 0,
                flags(false, axisBits), null, new Vector3f(1, 0, 0), null));
        connect(root, source, grant, axis);
        ModelInstance instance = instance(root, source, grant, axis);

        instance.getSkeletonInstance().transform(source,
                new Transform().translate(2, 0, 0).mul(new Quaternionf().rotateZ((float) Math.PI / 2)));
        instance.getSkeletonInstance().transform(axis,
                new Transform().mul(new Quaternionf().rotateXYZ(0.7f, 0.5f, 0.3f)));
        instance.updateImmediate();

        Transform evaluatedGrant = instance.getSkeletonInstance().getEvaluatedTransforms().get(grant);
        assertEquals(1f, evaluatedGrant.getPosition().x, 1e-5f);
        assertEquals((float) Math.PI / 4,
                evaluatedGrant.getRotation().getEulerAnglesXYZ(new Vector3f()).z, 1e-4f);
        Quaternionf fixed = instance.getSkeletonInstance().getEvaluatedTransforms().get(axis).getRotation();
        assertEquals(0f, fixed.y, 1e-5f);
        assertEquals(0f, fixed.z, 1e-5f);
    }

    @Test
    void ccdIkMovesEffectorToControllerAndHonorsVmdSwitch() {
        Bone root = bone("root", new Transform(), data("root", -1, plainFlags(), null, null, null));
        Bone link = bone("link", new Transform(), data("link", 0, plainFlags(), null, null, null));
        Bone effector = bone("effector", new Transform().translate(1, 0, 0),
                data("effector", 1, plainFlags(), null, null, null));
        PmxIKBone ik = new PmxIKBone(2, 8, (float) Math.PI,
                new PmxIKChain[]{new PmxIKChain(1, false, null)});
        Bone controller = bone("controller", new Transform().translate(0, 1, 0),
                data("controller", 0, flags(true, new boolean[6]), null, null, ik));
        connect(root, link, controller);
        connect(link, effector);
        ModelInstance instance = instance(root, link, effector, controller);

        instance.updateImmediate();
        Vector3f target = instance.getSkeletonInstance().getAbsoluteTransforms().get(effector).getPosition();
        Vector3f goal = instance.getSkeletonInstance().getAbsoluteTransforms().get(controller).getPosition();
        assertTrue(target.distance(goal) < 1e-4f, "IK should converge to its controller");

        instance.getSkeletonInstance().setIkEnabled("controller", false);
        instance.updateImmediate();
        target = instance.getSkeletonInstance().getAbsoluteTransforms().get(effector).getPosition();
        assertEquals(new Vector3f(1, 0, 0), target);
    }

    @Test
    void ccdIkConvertsWorldCorrectionIntoRotatedModelSpace() {
        Bone root = bone("root", new Transform(), data("root", -1, plainFlags(), null, null, null));
        Bone link = bone("link", new Transform(), data("link", 0, plainFlags(), null, null, null));
        Bone effector = bone("effector", new Transform().translate(1, 0, 0),
                data("effector", 1, plainFlags(), null, null, null));
        PmxIKBone ik = new PmxIKBone(2, 16, (float) Math.PI,
                new PmxIKChain[]{new PmxIKChain(1, false, null)});
        Bone controller = bone("controller", new Transform().translate(0, 1, 0),
                data("controller", 0, flags(true, new boolean[6]), null, null, ik));
        connect(root, link, controller);
        connect(link, effector);
        Transform modelTransform = new Transform().mul(new Quaternionf().rotateXYZ(0.6f, 0.8f, -0.4f));
        ModelInstance instance = instance(modelTransform, root, link, effector, controller);

        instance.updateImmediate();

        Vector3f target = instance.getSkeletonInstance().getAbsoluteTransforms().get(effector).getPosition();
        Vector3f goal = instance.getSkeletonInstance().getAbsoluteTransforms().get(controller).getPosition();
        assertTrue(target.distance(goal) < 1e-4f, "IK should converge under a rotated model transform");
    }

    @Test
    void externalIkTargetModuleOverridesTheAuthoredControllerTargetForOneTick() {
        Bone root = bone("root", new Transform(), data("root", -1, plainFlags(), null, null, null));
        Bone link = bone("link", new Transform(), data("link", 0, plainFlags(), null, null, null));
        Bone effector = bone("effector", new Transform().translate(1, 0, 0),
                data("effector", 1, plainFlags(), null, null, null));
        PmxIKBone ik = new PmxIKBone(2, 16, (float) Math.PI,
                new PmxIKChain[]{new PmxIKChain(1, false, null)});
        Bone controller = bone("controller", new Transform().translate(0, 1, 0),
                data("controller", 0, flags(true, new boolean[6]), null, null, ik));
        connect(root, link, controller);
        connect(link, effector);
        ModelInstance instance = instance(root, link, effector, controller);
        Vector3f externalTarget = new Vector3f(-1f, 0f, 0f);
        IkTargetModule hand = new IkTargetModule("controller", externalTarget);
        instance.getTickLoop().addPreIk("hand-target", hand);

        instance.updateImmediate();

        Vector3f solved = instance.getSkeletonInstance().getAbsoluteTransforms().get(effector).getPosition();
        assertTrue(solved.distance(externalTarget) < 1e-4f,
                "pre-IK tick loop modules must feed the same-frame PMX IK solve");
    }

    @Test
    void ikSolverOverridesAuthoredRotationOnChainLinks() {
        Bone root = bone("root", new Transform(), data("root", -1, plainFlags(), null, null, null));
        Bone link = bone("link", new Transform(), data("link", 0, plainFlags(), null, null, null));
        Bone effector = bone("effector", new Transform().translate(1, 0, 0),
                data("effector", 1, plainFlags(), null, null, null));
        PmxIKBone ik = new PmxIKBone(2, 8, (float) Math.PI,
                new PmxIKChain[]{new PmxIKChain(1, false, null)});
        Bone controller = bone("controller", new Transform().translate(0, 1, 0),
                data("controller", 0, flags(true, new boolean[6]), null, null, ik));
        connect(root, link, controller);
        connect(link, effector);
        ModelInstance instance = instance(root, link, effector, controller);

        // MMD 语义：IK 启用时链上骨由解算器接管。给链上骨一个巨大的直接旋转
        // （模拟 VMD 同时动画大腿 + IK 的双驱动）—— IK 必须无视它并正常收敛。
        instance.getSkeletonInstance().transform(link,
                new Transform().mul(new Quaternionf().rotateX((float) Math.PI / 2)));
        instance.updateImmediate();
        Vector3f solved = instance.getSkeletonInstance().getAbsoluteTransforms().get(effector).getPosition();
        Vector3f goal = instance.getSkeletonInstance().getAbsoluteTransforms().get(controller).getPosition();
        assertTrue(solved.distance(goal) < 1e-3f,
                "authored link rotation must not fight the IK solve while IK is enabled");

        instance.getSkeletonInstance().setIkEnabled("controller", false);
        instance.updateImmediate();
        Vector3f rest = instance.getSkeletonInstance().getAbsoluteTransforms().get(effector).getPosition();
        assertTrue(rest.distance(goal) > 0.5f,
                "authored link rotation must take effect again once IK is disabled");
    }

    @Test
    void singleChainCoincidentIkUsesDirectionSemantics() {
        // つま先ＩＫ 型：单链 IK + 控制器与 effector bind 重合。控制器被外部大幅移动时，
        // 方向语义下 effector 只对齐方向、不被拖走（位置语义会把它拽向控制器 → 脚底板翻起）。
        Bone root = bone("root", new Transform(), data("root", -1, plainFlags(), null, null, null));
        Bone link = bone("link", new Transform(), data("link", 0, plainFlags(), null, null, null));
        Bone effector = bone("effector", new Transform().translate(0, 0, -1),
                data("effector", 1, plainFlags(), null, null, null));
        PmxIKBone ik = new PmxIKBone(2, 8, (float) Math.PI,
                new PmxIKChain[]{new PmxIKChain(1, false, null)});
        // 控制器 bind 位置与 effector 重合（方向骨惯例）
        Bone controller = bone("controller", new Transform().translate(0, 0, -1),
                data("controller", 0, flags(true, new boolean[6]), null, null, ik));
        connect(root, link, controller);
        connect(link, effector);
        ModelInstance instance = instance(root, link, effector, controller);

        instance.updateImmediate();
        Vector3f bindPos = instance.getSkeletonInstance().getAbsoluteTransforms().get(effector).getPosition();
        assertEquals(0f, bindPos.distance(new Vector3f(0, 0, -1)), 1e-4f);

        // 把控制器大幅上移 —— 位置语义会把 effector 拽上去，方向语义应保持 effector 原地。
        instance.getSkeletonInstance().transform(controller, new Transform().translate(0, 5, -1));
        instance.updateImmediate();
        Vector3f solved = instance.getSkeletonInstance().getAbsoluteTransforms().get(effector).getPosition();
        assertTrue(solved.distance(bindPos) < 0.1f,
                "direction-type IK must not drag the effector toward a translated controller");
    }

    private static ModelInstance instance(Bone... bones) {
        return instance(null, bones);
    }

    private static ModelInstance instance(Transform transform, Bone... bones) {
        Skeleton skeleton = new Skeleton(bones, bones[0], new Anchor[0], null, new Transform());
        var dynamics = new lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsBuilder(skeleton);
        MmdSkeletonDynamicsReader.readBoneConstraints(dynamics);
        MmdSkeletonDynamicsReader.readIk(dynamics);
        dynamics.attach();
        Model model = new Model(new lib.kasuga.rendering.models.uml.structure.basic.Vertex[0],
                new lib.kasuga.rendering.models.uml.structure.basic.Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        return new ModelInstance(model, transform, null, null, null, null);
    }

    private static Bone bone(String name, Transform transform, PmxBone data) {
        return new Bone(name, transform, data);
    }

    private static void connect(Bone parent, Bone... children) {
        parent.setChildren(children);
        for (Bone child : children) child.setParent(parent);
    }

    private static PmxBone data(String name, int parent, PmxBoneFlags flags,
                                ParentBoneInherit inherit, Vector3f fixedAxis, PmxIKBone ik) {
        return new PmxBone(name, name, new Vector3f(), parent, 0, flags, new Vector3f(),
                inherit, fixedAxis, null, -1, ik);
    }

    private static PmxBoneFlags plainFlags() {
        return new PmxBoneFlags();
    }

    private static PmxBoneFlags flags(boolean ik, boolean[] second) {
        boolean[] first = new boolean[6];
        first[1] = true;
        first[2] = true;
        first[4] = true;
        first[5] = ik;
        return new PmxBoneFlags(first, second);
    }
}
