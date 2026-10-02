package lib.kasuga.rendering.models.uml.dynamic.tick_loop;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.AnchorModule;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule;
import lib.kasuga.rendering.models.uml.math.Transform;import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTickLoopTest {
    @Test
    void renderFrameEvaluatesProceduralStagesOnceAndRetriesFailedPreparation() {
        Fixture fixture = fixture();
        int[] calls = {0};
        fixture.instance().getTickLoop().addPreIk("probe", new ModelTickLoopModule() {
            @Override public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float dt) {
                if (++calls[0] == 1) throw new IllegalStateException("injected module failure");
            }
            @Override public void destroy(Model model) {}
        });
        Object frame = new Object();
        assertThrows(IllegalStateException.class, () -> fixture.instance().prepareRenderFrame(frame));
        fixture.instance().prepareRenderFrame(frame);
        fixture.instance().prepareRenderFrame(frame);
        fixture.instance().prepareRenderFrame(frame);
        assertEquals(2,calls[0]);
        fixture.instance().prepareRenderFrame(new Object());
        assertEquals(3,calls[0]);
        fixture.instance().forceUpdate();
        Object nextFrame = new Object();
        fixture.instance().prepareRenderFrame(nextFrame);
        fixture.instance().forceUpdate();
        fixture.instance().prepareRenderFrame(nextFrame);
        assertEquals(5,calls[0]);
    }


    @Test
    void allocatesRootAndBoneTransformsInSkeletonOrder() {
        Fixture fixture = fixture();
        ModelTickLoop loop = new ModelTickLoop(fixture.instance());

        assertEquals(3, loop.getTransforms().length);
        assertSame(loop.getTransforms()[0], loop.rootTransform());
        assertSame(loop.getTransforms()[1], loop.boneTransform(0));
        assertSame(loop.getTransforms()[1], loop.boneTransform(fixture.root()));
        assertSame(loop.getTransforms()[2], loop.boneTransform(1));
        assertSame(loop.getTransforms()[2], loop.boneTransform(fixture.child()));
    }

    @Test
    void defaultPipelineExposesStandardSlotsInCanonicalOrder() {
        Fixture fixture = fixture();
        ModelTickLoop loop = new ModelTickLoop(fixture.instance());

        assertEquals(List.of(ModelTickLoop.SLOT_APPLY, ModelTickLoop.SLOT_IK,
                ModelTickLoop.SLOT_PHYSICS, ModelTickLoop.SLOT_ANCHOR), loop.getPipeline().ids());
        assertFalse(loop.hasProceduralModules());

        loop.addPreIk("probe", noop());
        assertTrue(loop.hasProceduralModules());
        assertEquals(List.of(ModelTickLoop.SLOT_APPLY, "probe", ModelTickLoop.SLOT_IK,
                ModelTickLoop.SLOT_PHYSICS, ModelTickLoop.SLOT_ANCHOR), loop.getPipeline().ids());

        loop.addPostPhysics("late", noop());
        assertEquals(List.of(ModelTickLoop.SLOT_APPLY, "probe", ModelTickLoop.SLOT_IK,
                ModelTickLoop.SLOT_PHYSICS, "late", ModelTickLoop.SLOT_ANCHOR),
                loop.getPipeline().ids());
    }

    @Test
    void modulesComposeThenApplyOneTransformPerRootAndBoneWithoutAccumulating() {
        Fixture fixture = fixture();
        ModelTickLoop loop = new ModelTickLoop(fixture.instance());
        loop.addPreIk("pose", new ModelTickLoopModule() {
            @Override
            public void tick(Model model, PendingTransform[] transforms,
                             ModelTickLoop tickLoop, float deltaTime) {
                transforms[0].setOffsetZ(4f);
                transforms[fixture.root().getIndex() + 1].setOffsetX(2f);
                transforms[fixture.child().getIndex() + 1].setOffsetY(3f);
            }

            @Override
            public void destroy(Model model) {
            }
        });

        loop.tick(1f / 20f);
        loop.tick(1f / 20f);

        assertEquals(new Vector3f(0f, 0f, 4f),
                fixture.instance().getSkeletonInstance().getTransform().getPosition());
        assertEquals(new Vector3f(2f, 0f, 0f),
                fixture.instance().getSkeletonInstance().getTransforms().get(fixture.root()).getPosition());
        assertEquals(new Vector3f(0f, 3f, 0f),
                fixture.instance().getSkeletonInstance().getTransforms().get(fixture.child()).getPosition());
        assertEquals(new Vector3f(2f, 3f, 4f),
                fixture.instance().getSkeletonInstance().getAbsoluteTransforms().get(fixture.child()).getPosition());
    }

    @Test
    void applyStageLeavesDriverOwnedBonesUntouched() {
        Fixture fixture = fixture();
        ModelTickLoop loop = new ModelTickLoop(fixture.instance());
        // Simulate a pose driver writing a bone local directly on the skeleton.
        fixture.instance().getSkeletonInstance()
                .transform(fixture.root(), new Transform().translate(7f, 0f, 0f));
        // The pending channel only touches the child bone.
        loop.boneTransform(fixture.child()).setOffsetY(1f);

        loop.tick(1f / 20f);

        assertEquals(new Vector3f(7f, 0f, 0f),
                fixture.instance().getSkeletonInstance().getTransforms().get(fixture.root()).getPosition(),
                "an identity pending slot must not clobber driver-written locals");
        assertEquals(new Vector3f(0f, 1f, 0f),
                fixture.instance().getSkeletonInstance().getTransforms().get(fixture.child()).getPosition());
    }

    @Test
    void anchorModulePublishesWorldTransformsOfBoundAnchors() {
        Bone root = new Bone("root", new Transform(), null);
        Bone child = new Bone("child", new Transform().translate(0f, 1f, 0f), null);
        root.setChildren(new Bone[]{child});
        child.setParent(root);
        child.setChildren(new Bone[0]);
        Bone[] bones = {root, child};
        @SuppressWarnings("unchecked")
        BoneBinding binding = new BoneBinding(
                new Pair[]{Pair.of(child, 1.0f)}, BoneBindingFunc.IDENTITY, null);
        Anchor anchor = new Anchor("hand", binding,
                new Transform().translate(0f, 1f, 0f), null);
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[]{anchor}, null, new Transform());
        Model model = new Model(new Vertex[0], new Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);

        AtomicReference<Transform> captured = new AtomicReference<>();
        AtomicReference<Transform> missing = new AtomicReference<>();
        assertTrue(instance.attachToAnchor("hand", captured::set));
        assertTrue(instance.attachToAnchor("missing", missing::set));

        instance.tick(1f / 20f);

        assertEquals(new Vector3f(0f, 1f, 0f),
                captured.get().getPosition(),
                "at bind pose every bone delta is identity, so the anchor sits at its authored transform");

        instance.getTickLoop().boneTransform(child).setOffsetY(2f);
        instance.tick(1f / 20f);

        assertEquals(new Vector3f(0f, 3f, 0f),
                captured.get().getPosition(),
                "the anchor must follow the procedural bone motion");
        assertNull(missing.get(), "unresolvable anchors report null");
    }

    @Test
    void rejectsArraysThatDoNotMatchRootPlusBoneLayout() {
        Fixture fixture = fixture();

        assertThrows(IllegalArgumentException.class,
                () -> new ModelTickLoop(fixture.instance(), new PendingTransform[2]));
        PendingTransform[] withNullSlot = {
                new PendingTransform(), new PendingTransform(), null
        };
        assertThrows(IllegalArgumentException.class,
                () -> new ModelTickLoop(fixture.instance(), withNullSlot));
    }

    private static ModelTickLoopModule noop() {
        return new ModelTickLoopModule() {
            @Override public void tick(Model model, PendingTransform[] transforms,
                                       ModelTickLoop loop, float deltaTime) {}
            @Override public void destroy(Model model) {}
        };
    }

    private static Fixture fixture() {
        Bone root = new Bone("root", new Transform(), null);
        Bone child = new Bone("child", new Transform(), null);
        root.setChildren(new Bone[]{child});
        child.setParent(root);
        child.setChildren(new Bone[0]);
        Bone[] bones = {root, child};
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[0], null, new Transform());
        Model model = new Model(new Vertex[0], new Mesh[0], bones, skeleton,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        return new Fixture(new ModelInstance(model, null, null, null, null, null), root, child);
    }

    private record Fixture(ModelInstance instance, Bone root, Bone child) {
    }
}
