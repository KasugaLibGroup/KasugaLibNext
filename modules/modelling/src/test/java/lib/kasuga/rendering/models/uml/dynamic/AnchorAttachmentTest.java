package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.AnchorModule;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.*;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AnchorAttachmentTest {
    private static ModelInstance instance(Transform bind) {
        Bone root = new Bone("root", bind, null);
        Bone hand = new Bone("hand", new Transform().translate(1, 2, 3).rotate(0, 25, 0, true), null);
        root.setChildren(new Bone[]{hand}); hand.setParent(root); hand.setChildren(new Bone[0]);
        Skeleton rig = new Skeleton(new Bone[]{root, hand}, root, new Anchor[0], null, new Transform());
        Model model = new Model(new Vertex[0], new Mesh[0], rig.getBones(), rig,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        return new ModelInstance(model, null, null, null, null, null);
    }

    @Test void rigidAnchorMatchesCurrentBoneTimesLocalOffsetWithNoncommutingBindAndPose() {
        try (var instance = instance(new Transform().translate(7, 5, -2).rotate(10, 0, 35, true).scale(2, 3, 4))) {
            var rig = instance.getModel().getSkeleton();
            var hand = rig.getBoneMap().get("hand");
            var offset = new Transform().translate(.3f, .4f, .5f).rotate(0, 60, 0, true);
            rig.defineAnchor("grip", hand, offset);
            instance.getSkeletonInstance().rotate("hand", new Quaternionf().rotateX(.8f));
            instance.getSkeletonInstance().transformRoot(new Transform().translate(10, -2, 5).rotate(0, 40, 0, true));
            instance.updateImmediate();
            Matrix4f expected = instance.getSkeletonInstance().getAbsoluteTransforms().get(hand).copy().mul(offset).transform();
            Matrix4f actual = instance.anchorTransform("grip").transform();
            assertTrue(actual.equals(expected, 2e-5f), "anchor must match the same current * inverse-bind deformation as skinning");
        }
    }

    @Test void cameraRelativeAnchorPreservesMillimetersAtLargeWorldCoordinates() {
        Bone root = new Bone("root", new Transform(), null);
        Skeleton rig = new Skeleton(new Bone[]{root}, root, new Anchor[0], null, new Transform());
        rig.defineAnchor("grip", root, new Transform().translate(.001f, .002f, .003f));
        Model model = new Model(new Vertex[0], new Mesh[0], rig.getBones(), rig,
                new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        try (var instance = new ModelInstance(model, null, null, null, null, null)) {
            var origin = new Vector3d(70_000_000.125, 70, -70_000_000.375);
            instance.getSkeletonInstance().enableFloatingOrigin(origin);
            instance.updateImmediate();
            var relative = instance.getSkeletonInstance().anchorTransformRelative("grip", origin);
            assertEquals(.001, relative.getPosition().x, 1e-7);
            assertEquals(.002, relative.getPosition().y, 1e-7);
            assertEquals(.003, relative.getPosition().z, 1e-7);
            assertNull(instance.getSkeletonInstance().anchorTransformRelative("missing", origin));
            assertThrows(IllegalArgumentException.class, () -> instance.getSkeletonInstance().anchorTransformRelative("grip", new Vector3d(Double.NaN, 0, 0)));
        }
    }

    @Test void authoringCopiesOffsetAndRejectsDuplicateOrForeignBones() {
        try (var instance = instance(new Transform())) {
            var rig = instance.getModel().getSkeleton(); var root = rig.getRoot();
            var offset = new Transform().translate(.25f, 0, 0);
            rig.defineAnchor("head", root, offset); offset.translate(1, 0, 0);
            instance.updateImmediate();
            assertEquals(.25, instance.anchorTransform("head").getPosition().x, 1e-6);
            assertEquals(1, rig.getAnchors().length);
            assertThrows(IllegalArgumentException.class, () -> rig.defineAnchor("head", root, new Transform()));
            assertThrows(IllegalArgumentException.class, () -> rig.defineAnchor("other", new Bone("foreign", new Transform(), null), new Transform()));
        }
    }

    @Test void oneReceiverCanUseTwoAnchorsAndDetachDuringCallbacks() {
        try (var instance = instance(new Transform())) {
            var rig = instance.getModel().getSkeleton();
            rig.defineAnchor("first", rig.getRoot(), new Transform());
            rig.defineAnchor("second", rig.getRoot(), new Transform());
            AtomicInteger count = new AtomicInteger();
            AnchorModule.Attachment callback = transform -> count.incrementAndGet();
            instance.attachToAnchor("first", callback); instance.attachToAnchor("first", callback);
            instance.attachToAnchor("second", callback);
            assertEquals(2, instance.getTickLoop().module("kasuga:anchor", AnchorModule.class).attachmentCount());
            instance.tick(0); assertEquals(2, count.get());
            assertTrue(instance.detachFromAnchor("first", callback));
            instance.tick(0); assertEquals(3, count.get());
            AnchorModule.Attachment[] self = new AnchorModule.Attachment[1];
            self[0] = transform -> instance.detachFromAnchor("second", self[0]);
            instance.attachToAnchor("second", self[0]);
            assertDoesNotThrow(() -> instance.tick(0));
            assertEquals(1, instance.getTickLoop().module("kasuga:anchor", AnchorModule.class).attachmentCount());
        }
    }
}
