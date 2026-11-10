package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.ModelPoseBinding;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.RootTransformDriver;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTargetRef;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.FrameInputSnapshot;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderFrameToken;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RigidPartBindingTest {

    @Test
    void modelGroupScopePublishesOnceAndTheBindingWritesThePartRootOnSample() {
        PartMotionSampler sampler = new PartMotionSampler(
                ModelPart.tickTransform(machine -> new Transform().setPosition(new Vector3f(2f, 3f, 4f))));
        ModelGroupFrameState state = new ModelGroupFrameState();
        RigidPartBinding target = new RigidPartBinding(
                0, sampler, () -> null, state, new Transform());
        var instance = ModelInstanceFixture.minimal();
        target.attachTo(instance);
        ModelGroupScope scope = new ModelGroupScope(
                state, new Transform(), java.util.List.of(target));

        scope.tick(0.05f, new Transform().translate(10f, 20f, 30f));
        instance.sample(0.5f);

        Vector3f root = instance.getSkeletonInstance().getTransform().getPosition();
        assertEquals(12f, root.x, 1e-4f);
        assertEquals(23f, root.y, 1e-4f);
        assertEquals(34f, root.z, 1e-4f);
        assertEquals(AnimationTargetRef.part(0), target.ref());
        assertSame(target, instance.getRootTransformDriver());
        assertNull(instance.getPoseDriver(), "rigid part ownership must not occupy the pose slot");
    }

    @Test
    void modelInstanceSamplesTheScopesCommittedTargetBatch() {
        PartMotionSampler sampler = new PartMotionSampler(
                ModelPart.tickTransform(machine ->
                        new Transform().setPosition(new Vector3f(2f, 3f, 4f))));
        ModelGroupFrameState state = new ModelGroupFrameState();
        RigidPartBinding target = new RigidPartBinding(
                0, sampler, () -> null, state, new Transform());
        var instance = ModelInstanceFixture.minimal();
        target.attachTo(instance);
        ModelGroupScope scope = new ModelGroupScope(
                state, new Transform(), java.util.List.of(target));

        scope.tick(0.05f, new Transform().translate(10f, 20f, 30f));
        RenderSampleContext context = new RenderSampleContext(
                new RenderFrameToken("batch-render"), 1.0, FrameInputSnapshot.empty());
        instance.sample(context);

        Vector3f root = instance.getSkeletonInstance().getTransform().getPosition();
        assertEquals(12f, root.x, 1e-4f);
        assertEquals(23f, root.y, 1e-4f);
        assertEquals(34f, root.z, 1e-4f);

        scope.publishFrame(new Transform().translate(40f, 20f, 30f));
        instance.sample(new RenderSampleContext(
                new RenderFrameToken("frame-input-render"), 0.25, FrameInputSnapshot.empty()));

        root = instance.getSkeletonInstance().getTransform().getPosition();
        assertEquals(42f, root.x, 1e-4f);
        assertEquals(23f, root.y, 1e-4f);
        assertEquals(34f, root.z, 1e-4f);
    }

    @Test
    void groupLightProbeIgnoresThePartLocalOffset() {
        ModelGroupFrameState state = new ModelGroupFrameState();
        Transform local = new Transform().translate(0f, 5f, 0f);
        RigidPartBinding target = new RigidPartBinding(
                0, new PartMotionSampler(null), () -> null, state, local,
                new Vector3f(0f, 1.5f, 0f));
        var instance = ModelInstanceFixture.minimal();
        target.attachTo(instance);
        ModelGroupScope scope = new ModelGroupScope(
                state, new Transform(), java.util.List.of(target));

        scope.tick(0.05f, new Transform().translate(10f, 20f, 30f));
        instance.sample(1f);

        assertEquals(25f, instance.getSkeletonInstance().getWorldRootPosition().y, 1e-4f);
        assertEquals(new Vector3d(10f, 21.5f, 30f), target.worldLightPosition());
    }

    @Test
    void rebindAndCloseAreIdempotentAndPreserveTheOtherDriverSlot() {
        ModelGroupFrameState state = new ModelGroupFrameState();
        RigidPartBinding target = new RigidPartBinding(
                0, new PartMotionSampler(null), () -> null, state, new Transform());
        var oldInstance = ModelInstanceFixture.minimal();
        var fresh = ModelInstanceFixture.minimal();
        PoseDriver poseDriver = new PoseDriver() {};
        ModelPoseBinding poseBinding = ModelPoseBinding.install(fresh, poseDriver);
        target.attachTo(oldInstance);

        target.rebind(fresh);
        target.rebind(fresh);

        assertNull(oldInstance.getRootTransformDriver());
        assertSame(target, fresh.getRootTransformDriver());
        assertSame(poseBinding, fresh.getPoseDriver());

        ModelGroupScope scope = new ModelGroupScope(
                state, new Transform(), java.util.List.of(target));
        scope.close();
        scope.close();

        assertNull(fresh.getRootTransformDriver());
        assertSame(poseBinding, fresh.getPoseDriver(),
                "closing the rigid root owner must not close or clear the pose driver");
    }

    @Test
    void invalidPartialTickIsRejectedAtTheBindingBoundary() {
        ModelGroupFrameState state = new ModelGroupFrameState();
        RigidPartBinding target = new RigidPartBinding(
                0, new PartMotionSampler(null), () -> null, state, new Transform());

        assertThrows(IllegalArgumentException.class, () -> target.sampleRoot(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> target.sampleRoot(Float.POSITIVE_INFINITY));
    }

    @Test
    void closedModelDetachesTheBindingWhichCanAttachAgain() {
        ModelGroupFrameState state = new ModelGroupFrameState();
        RigidPartBinding target = new RigidPartBinding(
                0, new PartMotionSampler(null), () -> null, state, new Transform());
        var previous = ModelInstanceFixture.minimal();
        var fresh = ModelInstanceFixture.minimal();
        target.attachTo(previous);

        previous.close();

        assertNull(previous.getRootTransformDriver());
        target.rebind(fresh);
        assertSame(target, fresh.getRootTransformDriver());
    }

    @Test
    void attachingToAnInstanceWithAnotherRootOwnerFailsWithoutStealingIt() {
        ModelGroupFrameState state = new ModelGroupFrameState();
        RigidPartBinding target = new RigidPartBinding(
                0, new PartMotionSampler(null), () -> null, state, new Transform());
        var oldInstance = ModelInstanceFixture.minimal();
        var foreignInstance = ModelInstanceFixture.minimal();
        RootTransformDriver foreignRoot = partialTick -> {
        };
        target.attachTo(oldInstance);
        foreignInstance.setRootTransformDriver(foreignRoot);

        assertThrows(IllegalStateException.class, () -> target.rebind(foreignInstance));

        assertSame(foreignRoot, foreignInstance.getRootTransformDriver());
        assertSame(target, oldInstance.getRootTransformDriver(),
                "a rejected rebind must leave the old ownership intact");
    }
}
