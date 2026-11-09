package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPoseBindingTest {

    @Test
    void poseSlotRejectsRawDriversAndAcceptsNullCleanup() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        CountingDriver source = new CountingDriver();
        ModelPoseBinding binding = ModelPoseBinding.install(instance, source);

        assertThrows(IllegalArgumentException.class,
                () -> instance.setPoseDriver(new PoseDriver() {}));
        assertSame(binding, instance.getPoseDriver());

        instance.setPoseDriver(null);

        assertNull(instance.getPoseDriver());
    }

    @Test
    void crossInstancePoseBindingIsRejectedWithoutChangingEitherSlot() {
        ModelInstance owner = ModelInstanceFixture.minimal();
        ModelInstance target = ModelInstanceFixture.minimal();
        ModelPoseBinding ownerBinding = ModelPoseBinding.install(owner, new CountingDriver());
        ModelPoseBinding targetBinding = ModelPoseBinding.install(target, new CountingDriver());

        assertThrows(IllegalArgumentException.class, () -> target.setPoseDriver(ownerBinding));

        assertSame(ownerBinding, owner.getPoseDriver());
        assertSame(targetBinding, target.getPoseDriver());
    }

    @Test
    void installForwardsTickAndSampleOnceAndCloseIsIdempotent() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        CountingDriver source = new CountingDriver();

        ModelPoseBinding binding = ModelPoseBinding.install(instance, source);

        assertSame(binding, instance.getPoseDriver());
        assertSame(source, binding.source());
        instance.animate(0.05f);
        instance.sample(0.25f);
        assertEquals(1, source.ticks);
        assertEquals(1, source.samples);
        assertEquals(0.05f, source.lastDt, 1e-6f);
        assertEquals(0.25f, source.lastPartialTick, 1e-6f);

        binding.close();
        binding.close();
        assertNull(instance.getPoseDriver());
        instance.animate(0.05f);
        instance.sample(0.5f);
        assertEquals(1, source.ticks);
        assertEquals(1, source.samples);
    }

    @Test
    void installReusesTheBindingForTheSameSource() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        CountingDriver source = new CountingDriver();

        ModelPoseBinding first = ModelPoseBinding.install(instance, source);
        ModelPoseBinding second = ModelPoseBinding.install(instance, source);

        assertSame(first, second);
        assertSame(first, instance.getPoseDriver());
    }

    @Test
    void closingTheOwningInstanceOnlyDetachesTheBindingForLaterRebind() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        ModelInstance fresh = ModelInstanceFixture.minimal();
        CountingDriver source = new CountingDriver();
        ModelPoseBinding binding = ModelPoseBinding.install(instance, source);

        instance.close();

        assertNull(instance.getPoseDriver());
        assertNull(binding.model());
        assertFalse(binding.isClosed(), "pipeline close must leave the binding rebindable");

        binding.rebind(fresh);

        assertSame(binding, fresh.getPoseDriver());
        assertSame(fresh, binding.model());
        fresh.animate(0.05f);
        assertEquals(1, source.ticks);
    }

    @Test
    void rebindDetachesTheOldInstanceAndInstallsTheFreshOne() {
        ModelInstance previous = ModelInstanceFixture.minimal();
        ModelInstance fresh = ModelInstanceFixture.minimal();
        CountingDriver source = new CountingDriver();
        ModelPoseBinding binding = ModelPoseBinding.install(previous, source);

        binding.rebind(fresh);

        assertNull(previous.getPoseDriver());
        assertSame(binding, fresh.getPoseDriver());
        assertSame(fresh, binding.model());
        assertEquals(1, source.rebinds);
        assertSame(fresh, source.destination);

        fresh.animate(0.05f);
        fresh.sample(0.75f);
        assertEquals(1, source.ticks);
        assertEquals(1, source.samples);
    }

    @Test
    void rebindRejectsAStaticSourceWithoutDetachingTheOldInstance() {
        ModelInstance previous = ModelInstanceFixture.minimal();
        ModelInstance fresh = ModelInstanceFixture.minimal();
        ModelPoseBinding binding = ModelPoseBinding.install(previous, new PoseDriver() {});

        assertThrows(IllegalStateException.class, () -> binding.rebind(fresh));

        assertSame(binding, previous.getPoseDriver());
        assertNull(fresh.getPoseDriver());
        assertSame(previous, binding.model());
    }

    @Test
    void failedSourceRebindLeavesTheOldBindingAndInstanceUntouched() {
        ModelInstance previous = ModelInstanceFixture.minimal();
        ModelInstance fresh = ModelInstanceFixture.minimal();
        CountingDriver source = new CountingDriver();
        source.failRebind = true;
        ModelPoseBinding binding = ModelPoseBinding.install(previous, source);

        assertThrows(IllegalStateException.class, () -> binding.rebind(fresh));

        assertSame(binding, previous.getPoseDriver());
        assertNull(fresh.getPoseDriver());
        assertSame(previous, binding.model());
        previous.animate(0.05f);
        assertEquals(1, source.ticks, "the old binding must remain operational after a failed rebind");
    }

    @Test
    void modelPosingUsesTheBindingAsItsInstalledWrapper() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        ModelPosing posing = instance.getPosing();

        posing.pose(Pose.empty());

        assertTrue(instance.getPoseDriver() instanceof ModelPoseBinding);
        ModelPoseBinding binding = (ModelPoseBinding) instance.getPoseDriver();
        assertSame(posing, binding.source());
        assertSame(binding, ModelPoseBinding.installedFor(instance, posing));
        assertSame(posing, instance.getPosing());
    }

    private static final class CountingDriver implements RebindablePoseDriver {
        private int ticks;
        private int samples;
        private int rebinds;
        private float lastDt;
        private float lastPartialTick;
        private ModelInstance destination;
        private boolean failRebind;

        @Override
        public void tick(float dt) {
            ticks++;
            lastDt = dt;
        }

        @Override
        public void sample(float partialTick) {
            samples++;
            lastPartialTick = partialTick;
        }

        @Override
        public void rebind(ModelInstance fresh) {
            rebinds++;
            destination = fresh;
            if (failRebind) {
                throw new IllegalStateException("rebind failed");
            }
        }
    }
}
