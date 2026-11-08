package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.Continuity;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmStepController;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.FrameInputSnapshot;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.OutputState;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderFrameToken;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublication;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelGroupScopeTest {

    @Test
    void fourPartsReadTheSameRootSnapshotAcrossTickBoundaries() {
        ModelGroupFrameState state = new ModelGroupFrameState();
        List<RigidPartBinding> targets = new ArrayList<>();
        List<ModelInstance> instances = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RigidPartBinding target = new RigidPartBinding(
                    i, new PartMotionSampler(null), () -> null,
                    state, new Transform().translate(i + 1f, 0f, 0f));
            ModelInstance instance = ModelInstanceFixture.minimal();
            target.attachTo(instance);
            targets.add(target);
            instances.add(instance);
        }
        ModelGroupScope scope = new ModelGroupScope(state, new Transform(), targets);

        scope.tick(0.05f, new Transform().translate(10f, 0f, 0f));
        scope.tick(0.05f, new Transform().translate(20f, 0f, 0f));

        for (int i = 0; i < 4; i++) {
            RigidPartBinding target = targets.get(i);
            target.sampleRoot(0f);
            float atStart = instances.get(i).getSkeletonInstance().getTransform().getPosition().x;
            target.sampleRoot(0.5f);
            float halfway = instances.get(i).getSkeletonInstance().getTransform().getPosition().x;
            target.sampleRoot(1f);
            float atEnd = instances.get(i).getSkeletonInstance().getTransform().getPosition().x;

            assertEquals(11f + i, atStart, 1e-5f);
            assertEquals(16f + i, halfway, 1e-5f);
            assertEquals(21f + i, atEnd, 1e-5f);
        }
    }

    @Test
    void publishingAFrameChangesRootWithoutAdvancingLogicOrInterpolating() {
        ModelGroupFrameState state = new ModelGroupFrameState();
        AtomicInteger motionTicks = new AtomicInteger();
        PartMotionSampler sampler = new PartMotionSampler(ModelPart.tickTransform(machine -> {
            motionTicks.incrementAndGet();
            return new Transform().setPosition(new Vector3f(1f, 0f, 0f));
        }));
        RigidPartBinding target = new RigidPartBinding(
                0, sampler, () -> null, state, new Transform());
        ModelInstance instance = ModelInstanceFixture.minimal();
        target.attachTo(instance);
        ModelGroupScope scope = new ModelGroupScope(state, new Transform(), List.of(target));

        scope.tick(0.05f, new Transform().translate(10f, 0f, 0f));
        assertEquals(1, motionTicks.get());

        scope.publishFrame(new Transform().translate(40f, 0f, 0f));
        assertEquals(1, motionTicks.get());

        target.sampleRoot(0f);
        assertEquals(41f, instance.getSkeletonInstance().getTransform().getPosition().x, 1e-5f);
        target.sampleRoot(0.5f);
        assertEquals(41f, instance.getSkeletonInstance().getTransform().getPosition().x, 1e-5f);
        target.sampleRoot(1f);
        assertEquals(41f, instance.getSkeletonInstance().getTransform().getPosition().x, 1e-5f);
    }

    @Test
    void publishingAFrameSnapshotsEveryFrameMotionBeforeRenderSampling() {
        ModelGroupFrameState state = new ModelGroupFrameState();
        AtomicInteger frameReads = new AtomicInteger();
        PartMotionSampler sampler = new PartMotionSampler(ModelPart.frameTransform(() -> {
            frameReads.incrementAndGet();
            return new Transform().translate(2f, 0f, 0f);
        }));
        RigidPartBinding target = new RigidPartBinding(
                0, sampler, () -> null, state, new Transform());
        ModelInstance instance = ModelInstanceFixture.minimal();
        target.attachTo(instance);
        ModelGroupScope scope = new ModelGroupScope(state, new Transform(), List.of(target));

        scope.publishFrame(new Transform().translate(40f, 0f, 0f));
        assertEquals(1, frameReads.get());

        target.sampleRoot(0f);
        assertEquals(42f, instance.getSkeletonInstance().getTransform().getPosition().x, 1e-5f);
        target.sampleRoot(0.5f);
        assertEquals(42f, instance.getSkeletonInstance().getTransform().getPosition().x, 1e-5f);
        target.sampleRoot(1f);
        assertEquals(42f, instance.getSkeletonInstance().getTransform().getPosition().x, 1e-5f);
        assertEquals(1, frameReads.get(), "sampleRoot must not read the frame source");
    }

    @Test
    void framePublicationStillInterpolatesTickDomainMotion() {
        ModelGroupFrameState state = new ModelGroupFrameState();
        AtomicInteger tickValue = new AtomicInteger();
        PartMotionSampler tickSampler = new PartMotionSampler(
                ModelPart.tickTransform(machine -> new Transform().translate(
                        tickValue.incrementAndGet(), 0f, 0f)));
        RigidPartBinding target = new RigidPartBinding(
                0, tickSampler, () -> null, state, new Transform());
        ModelInstance instance = ModelInstanceFixture.minimal();
        target.attachTo(instance);
        ModelGroupScope scope = new ModelGroupScope(state, new Transform(), List.of(target));

        scope.tick(0.05f, new Transform());
        scope.tick(0.05f, new Transform());
        scope.publishFrame(new Transform().translate(30f, 0f, 0f));
        instance.sample(new RenderSampleContext(
                new RenderFrameToken("mixed-domain"),
                0.5,
                FrameInputSnapshot.empty()));

        assertEquals(31.5f,
                instance.getSkeletonInstance().getTransform().getPosition().x,
                1e-5f);
    }

    @Test
    void groupFrameIsPassedThroughTheRenderContextInsteadOfATargetSideChannel() {
        ModelGroupFrameState state = new ModelGroupFrameState();
        RigidPartBinding target = new RigidPartBinding(
                0,
                new PartMotionSampler(ModelPart.frameTransform(
                        () -> new Transform().translate(2f, 0f, 0f))),
                () -> null,
                state,
                new Transform());
        ModelInstance instance = ModelInstanceFixture.minimal();
        target.attachTo(instance);
        new ModelGroupScope(state, new Transform(), List.of(target));

        ModelGroupFrame providedFrame = new ModelGroupFrame(
                new RootSnapshot(
                        new Transform(),
                        new Transform().translate(40f, 0f, 0f)),
                List.of(new PartMotionSnapshot.FrameTransform(
                        new Transform().translate(2f, 0f, 0f))));
        RenderSampleContext context = new RenderSampleContext(
                new RenderFrameToken("group-frame-input"),
                1.0,
                FrameInputSnapshot.empty().with(
                        ModelGroupRenderPort.FRAME_INPUT,
                        providedFrame));
        target.publish(new TargetPublication<>(
                target.ref(),
                1,
                new OutputState.Active<>(
                        null,
                        new RigidPartOutput(
                                new RootSnapshot(new Transform(), new Transform()),
                                PartMotionSnapshot.staticSnapshot(),
                                new Transform()),
                        Continuity.SNAP)));

        target.sample(context);

        assertEquals(42f,
                instance.getSkeletonInstance().getTransform().getPosition().x,
                1e-5f);
    }

    @Test
    void concurrentTickAndFramePublishersNeverExposeMixedGenerations() throws Exception {
        ModelGroupFrameState state = new ModelGroupFrameState();
        AtomicInteger motionReads = new AtomicInteger();
        CountDownLatch tickSourceEntered = new CountDownLatch(1);
        CountDownLatch releaseTickSource = new CountDownLatch(1);
        PartMotionSampler sampler = new PartMotionSampler(ModelPart.tickTransform(machine -> {
            int generation = motionReads.getAndIncrement();
            if (generation == 1) {
                tickSourceEntered.countDown();
                await(releaseTickSource);
            }
            return new Transform().translate(generation == 0 ? 2f : 20f, 0f, 0f);
        }));
        RigidPartBinding target = new RigidPartBinding(
                0, sampler, () -> null, state, new Transform());
        ModelInstance instance = ModelInstanceFixture.minimal();
        target.attachTo(instance);
        ModelGroupScope scope = new ModelGroupScope(state, new Transform(), List.of(target));

        scope.tick(0.05f, new Transform().translate(1f, 0f, 0f));
        target.sampleRoot(1f);
        assertEquals(3f, instance.getSkeletonInstance().getTransform().getPosition().x, 1e-5f);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        AtomicReference<Throwable> renderFailure = new AtomicReference<>();
        try {
            Future<?> tickPublisher = executor.submit(
                    () -> scope.tick(0.05f, new Transform().translate(10f, 0f, 0f)));
            assertTrue(tickSourceEntered.await(5, TimeUnit.SECONDS));
            Future<?> framePublisher = executor.submit(
                    () -> scope.publishFrame(new Transform().translate(10f, 0f, 0f)));

            List<Future<?>> renderers = new ArrayList<>();
            for (int renderer = 0; renderer < 2; renderer++) {
                renderers.add(executor.submit(() -> {
                    try {
                        for (int sample = 0; sample < 1_000; sample++) {
                            target.sampleRoot(1f);
                            float x = instance.getSkeletonInstance().getTransform().getPosition().x;
                            assertEquals(3f, x, 1e-5f,
                                    "render saw a root/motion pair from different publications");
                        }
                    } catch (Throwable failure) {
                        renderFailure.compareAndSet(null, failure);
                    }
                }));
            }
            for (Future<?> renderer : renderers) {
                renderer.get(5, TimeUnit.SECONDS);
            }
            releaseTickSource.countDown();
            tickPublisher.get(5, TimeUnit.SECONDS);
            framePublisher.get(5, TimeUnit.SECONDS);
        } finally {
            releaseTickSource.countDown();
            executor.shutdownNow();
        }

        assertNull(renderFailure.get(), () -> "render observed a mixed frame: " + renderFailure.get());
        target.sampleRoot(1f);
        assertEquals(30f, instance.getSkeletonInstance().getTransform().getPosition().x, 1e-5f);
    }

    @Test
    void fourPartsShareOneFrameRootAndIndexOrderedMotionSnapshots() {
        ModelGroupFrameState frameState = new ModelGroupFrameState();
        List<RigidPartBinding> targets = new ArrayList<>();
        List<PartMotionSampler> samplers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int offset = i;
            PartMotionSampler sampler = new PartMotionSampler(ModelPart.tickTransform(
                    machine -> new Transform().translate(offset + 1f, 0f, 0f)));
            RigidPartBinding target = new RigidPartBinding(
                    i, sampler, () -> null, frameState, new Transform());
            ModelInstance instance = ModelInstanceFixture.minimal();
            target.attachTo(instance);
            targets.add(target);
            samplers.add(sampler);
        }
        ModelGroupScope scope = new ModelGroupScope(
                frameState, new Transform(), targets);

        scope.tick(0.05f, new Transform().translate(10f, 0f, 0f));

        ModelGroupFrame frame = frameState.current();
        assertSame(frame, frameState.current());
        assertEquals(4, frame.motions().size());
        for (int i = 0; i < 4; i++) {
            assertSame(samplers.get(i).snapshot(), frame.motions().get(i));
        }
    }

    @Test
    void oneModelGroupAdvanceTicksItsStateMachineExactlyOnce() {
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("main", layer -> layer.initial(layer.state("idle")))
                .build();
        ModelGroupFrameState frameState = new ModelGroupFrameState();
        RigidPartBinding target = new RigidPartBinding(
                0, new PartMotionSampler(null), () -> machine,
                frameState, new Transform());
        ModelGroupScope scope = new ModelGroupScope(
                frameState, new Transform(), List.of(target),
                new FsmStepController(machine));

        scope.tick(0.05f, new Transform());
        assertEquals(1, machine.tickCount());

        scope.pause();
        scope.tick(0.05f, new Transform());
        assertEquals(1, machine.tickCount());
    }

    @Test
    void proxyMachineControllerRunsPullTickVarProjectionAndPushOnce() {
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("main", layer -> layer.initial(layer.state("idle")))
                .build();
        AtomicInteger pulls = new AtomicInteger();
        AtomicInteger projections = new AtomicInteger();
        AtomicInteger pushes = new AtomicInteger();
        ModelGroupFrameState frameState = new ModelGroupFrameState();
        RigidPartBinding target = new RigidPartBinding(
                0, new PartMotionSampler(null), () -> machine,
                frameState, new Transform());
        ProxyMachineController controller = new ProxyMachineController(
                () -> machine,
                ignored -> pulls.incrementAndGet(),
                ignored -> pushes.incrementAndGet(),
                (ignored, dt, out) -> projections.incrementAndGet());
        ModelGroupScope scope = new ModelGroupScope(
                frameState, new Transform(), List.of(target), controller);

        scope.tick(0.05f, new Transform());

        assertEquals(1, pulls.get());
        assertEquals(1, machine.tickCount());
        assertEquals(1, projections.get());
        assertEquals(1, pushes.get());
    }

    @Test
    void proxyMachineControllerCanAttachAMachineAfterGroupConstruction() {
        ModelGroupFrameState frameState = new ModelGroupFrameState();
        AtomicReference<StateMachine<Object>> machine = new AtomicReference<>();
        RigidPartBinding target = new RigidPartBinding(
                0, new PartMotionSampler(null), machine::get,
                frameState, new Transform());
        ProxyMachineController controller = new ProxyMachineController(
                machine::get, ignored -> {}, ignored -> {}, null);
        ModelGroupScope scope = new ModelGroupScope(
                frameState, new Transform(), List.of(target), controller);
        scope.tick(0.05f, new Transform());

        StateMachine<Object> late = StateMachine.<Object>builder(new Object())
                .layer("main", layer -> layer.initial(layer.state("idle")))
                .build();
        machine.set(late);
        scope.tick(0.05f, new Transform());

        assertEquals(1, late.tickCount());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for the test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
