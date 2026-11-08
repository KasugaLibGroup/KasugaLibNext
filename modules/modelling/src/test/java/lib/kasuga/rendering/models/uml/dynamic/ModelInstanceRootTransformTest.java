package lib.kasuga.rendering.models.uml.dynamic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInstanceRootTransformTest {

    @Test
    void rootIsSampledBeforeInternalPoseExactlyOnce() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        List<String> order = new ArrayList<>();
        CountingRoot root = new CountingRoot(order, instance);
        CountingPose pose = new CountingPose(order);
        instance.setRootTransformDriver(root);
        ModelPoseBinding.install(instance, pose);

        instance.sample(0.25f);

        assertEquals(List.of("root", "pose"), order);
        assertEquals(1, root.samples);
        assertEquals(1, pose.samples);
    }

    @Test
    void replacingOneSlotDoesNotClearOrCloseTheOther() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        CountingRoot root = new CountingRoot(new ArrayList<>(), instance);
        CountingPose pose = new CountingPose(new ArrayList<>());
        instance.setRootTransformDriver(root);
        ModelPoseBinding poseBinding = ModelPoseBinding.install(instance, pose);

        instance.setRootTransformDriver(null);

        assertSame(poseBinding, instance.getPoseDriver());
        assertFalse(root.closed);

        instance.setPoseDriver(null);

        assertNull(instance.getRootTransformDriver());
    }

    @Test
    void preparedPhysicsFrameSamplesBothDriversOnlyOnce() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        List<String> order = new ArrayList<>();
        CountingRoot root = new CountingRoot(order, instance);
        CountingPose pose = new CountingPose(order);
        instance.setRootTransformDriver(root);
        ModelPoseBinding.install(instance, pose);

        instance.prepareSharedPhysicsFrame(0.25f);
        instance.sample(0.75f);

        assertEquals(List.of("root", "pose"), order);
        assertEquals(1, root.samples);
        assertEquals(1, pose.samples);
    }

    @Test
    void consecutivePreparationsForOneFrameSampleOnlyOnce() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        List<String> order = new ArrayList<>();
        CountingRoot root = new CountingRoot(order, instance);
        CountingPose pose = new CountingPose(order);
        instance.setRootTransformDriver(root);
        ModelPoseBinding.install(instance, pose);

        instance.prepareSharedPhysicsFrame(0.25f);
        instance.prepareSharedPhysicsFrame(0.5f);
        instance.sample(0.75f);

        assertEquals(List.of("root", "pose"), order);
        assertEquals(1, root.samples);
        assertEquals(1, pose.samples);
    }

    @Test
    void closingTheInstanceDetachesButDoesNotTerminallyCloseDrivers() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        CountingRoot root = new CountingRoot(new ArrayList<>(), instance);
        CountingPose pose = new CountingPose(new ArrayList<>());
        instance.setRootTransformDriver(root);
        ModelPoseBinding.install(instance, pose);

        instance.close();

        assertTrue(root.detached);
        assertFalse(root.closed, "pipeline instance close must leave the root owner reusable");
        assertNull(instance.getRootTransformDriver());
        assertNull(instance.getPoseDriver());
    }

    @Test
    void crossInstanceRootDriverIsRejectedWithoutChangingEitherSlot() {
        ModelInstance owner = ModelInstanceFixture.minimal();
        ModelInstance target = ModelInstanceFixture.minimal();
        CountingRoot ownerRoot = new CountingRoot(new ArrayList<>(), owner);
        CountingRoot targetRoot = new CountingRoot(new ArrayList<>(), target);
        owner.setRootTransformDriver(ownerRoot);
        target.setRootTransformDriver(targetRoot);

        assertThrows(IllegalArgumentException.class, () -> target.setRootTransformDriver(ownerRoot));

        assertSame(ownerRoot, owner.getRootTransformDriver());
        assertSame(targetRoot, target.getRootTransformDriver());
    }

    private static final class CountingRoot implements RootTransformDriver {
        private final List<String> order;
        private final ModelInstance owner;
        private int samples;
        private boolean closed;
        private boolean detached;

        private CountingRoot(List<String> order, ModelInstance owner) {
            this.order = order;
            this.owner = owner;
        }

        @Override
        public void sampleRoot(float partialTick) {
            samples++;
            order.add("root");
        }

        @Override
        public ModelInstance owner() {
            return owner;
        }

        @Override
        public void detach(ModelInstance instance) {
            detached = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class CountingPose implements PoseDriver {
        private final List<String> order;
        private int samples;

        private CountingPose(List<String> order) {
            this.order = order;
        }

        @Override
        public void sample(float partialTick) {
            samples++;
            order.add("pose");
        }
    }
}
