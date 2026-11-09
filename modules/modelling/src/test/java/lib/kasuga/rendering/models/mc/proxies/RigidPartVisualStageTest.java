package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.mc.api.McModelHandleTestSupport;
import lib.kasuga.rendering.models.mc.api.McModelHandle;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.FrameInputSnapshot;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ManagedAnimationScope;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderFrameToken;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvance;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvanceCause;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetBinding;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigidPartVisualStageTest {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("test", "rigid_part");
    private static final ResourceLocation BASE =
            ResourceLocation.fromNamespaceAndPath("test", "proxy_instance");

    @Test
    void sessionOwnedTargetsComposeRootLocalAndBothMotionDomainsWithoutAFrameRevision() {
        AtomicInteger tickReads = new AtomicInteger();
        AtomicInteger frameReads = new AtomicInteger();
        List<ModelInstance> instances = new ArrayList<>();
        RigidPartVisualStage stage = new RigidPartVisualStage(
                BASE,
                List.of(
                        ModelPart.at(MODEL, new Transform().translate(2f, 0f, 0f))
                                .moving(ModelPart.tickTransform(machine -> {
                                    tickReads.incrementAndGet();
                                    return new Transform().translate(3f, 0f, 0f);
                                })),
                        ModelPart.at(MODEL, new Transform().translate(4f, 0f, 0f))
                                .moving(ModelPart.frameTransform(() -> {
                                    frameReads.incrementAndGet();
                                    return new Transform().translate(5f, 0f, 0f);
                                }))),
                () -> null,
                new Transform().translate(10f, 0f, 0f),
                (spec, instanceLoc) -> {
                    ModelInstance instance = ModelInstanceFixture.minimal();
                    instances.add(instance);
                    return McModelHandleTestSupport.custom(MODEL, instanceLoc, instance);
                });
        int initialTickReads = tickReads.get();
        ManagedAnimationScope scope = scopeWith(stage);
        stage.bind(scope.renderPort());
        try {
            stage.prepareLogicalStep(new Transform().translate(20f, 0f, 0f));
            scope.advance(new ScopeAdvance(0.05, ScopeAdvanceCause.GAME_TICK));
            stage.maintainVisuals();
            assertEquals(initialTickReads + 1, tickReads.get(),
                    "one committed step must sample tick motion once");

            long revision = scope.view().revision();
            stage.publishFrame(new Transform().translate(40f, 0f, 0f));
            assertEquals(revision, scope.view().revision(),
                    "frame publication must not advance logical revision");
            assertEquals(2, frameReads.get(),
                    "initial frame snapshot plus one host frame, not one read per render sample");

            RenderSampleContext context = new RenderSampleContext(
                    new RenderFrameToken("rigid-stage"),
                    0.5,
                    FrameInputSnapshot.empty());
            instances.get(0).sample(context);
            instances.get(1).sample(context);

            assertEquals(45f, rootX(instances.get(0)), 1e-5f);
            assertEquals(49f, rootX(instances.get(1)), 1e-5f);
            assertEquals(2, frameReads.get(), "render sampling must not read the frame source");
        } finally {
            scope.close();
            stage.close();
        }
    }

    @Test
    void repeatedMaintenanceIsIdempotentAndCloseDestroysEveryPartHandle() {
        AtomicInteger creates = new AtomicInteger();
        List<ModelInstance> instances = new ArrayList<>();
        List<lib.kasuga.rendering.models.mc.api.McModelHandle> handles = new ArrayList<>();
        RigidPartVisualStage stage = new RigidPartVisualStage(
                BASE,
                List.of(ModelPart.of(MODEL), ModelPart.of(MODEL)),
                () -> null,
                new Transform(),
                (spec, instanceLoc) -> {
                    creates.incrementAndGet();
                    ModelInstance instance = ModelInstanceFixture.minimal();
                    var handle = McModelHandleTestSupport.custom(MODEL, instanceLoc, instance);
                    instances.add(instance);
                    handles.add(handle);
                    return handle;
                });

        stage.maintainVisuals();
        stage.maintainVisuals();

        assertEquals(2, creates.get(), "mount retries must reuse existing handles");
        assertEquals(2, stage.mountedPartCount());
        assertTrue(instances.stream().allMatch(instance -> instance.getRootTransformDriver() != null));

        stage.close();
        stage.close();

        assertTrue(handles.stream().allMatch(lib.kasuga.rendering.models.mc.api.McModelHandle::isDestroyed));
        for (ModelInstance instance : instances) {
            assertNull(instance.getRootTransformDriver());
            assertNull(instance.getAnimationRenderPort());
        }
    }

    @Test
    void aPartialMountFailureRollsBackEveryHandleAndRemainsRetryable() {
        AtomicInteger creates = new AtomicInteger();
        AtomicReference<McModelHandle> first = new AtomicReference<>();
        RigidPartVisualStage stage = new RigidPartVisualStage(
                BASE,
                List.of(ModelPart.of(MODEL), ModelPart.of(MODEL)),
                () -> null,
                new Transform(),
                (spec, instanceLoc) -> {
                    int create = creates.incrementAndGet();
                    if (create == 2) {
                        throw new IllegalStateException("second part failed");
                    }
                    McModelHandle handle = McModelHandleTestSupport.custom(
                            MODEL, instanceLoc, ModelInstanceFixture.minimal());
                    if (create == 1) {
                        first.set(handle);
                    }
                    return handle;
                });

        assertThrows(IllegalStateException.class, stage::maintainVisuals);
        assertTrue(first.get().isDestroyed(),
                "a failed assembly must not leave the first part mounted");

        stage.maintainVisuals();
        assertEquals(2, stage.mountedPartCount());
        stage.close();
    }

    @Test
    void suspendHidesAndStopsFrameSamplingUntilResumeRestoresTheSameHandles() {
        AtomicInteger frameReads = new AtomicInteger();
        AtomicReference<McModelHandle> handle = new AtomicReference<>();
        RigidPartVisualStage stage = new RigidPartVisualStage(
                BASE,
                List.of(ModelPart.of(MODEL).moving(ModelPart.frameTransform(() -> {
                    frameReads.incrementAndGet();
                    return new Transform();
                }))),
                () -> null,
                new Transform(),
                (spec, instanceLoc) -> {
                    McModelHandle created = McModelHandleTestSupport.custom(
                            MODEL, instanceLoc, ModelInstanceFixture.minimal());
                    handle.set(created);
                    return created;
                });

        stage.maintainVisuals();
        int initialReads = frameReads.get();
        assertTrue(handle.get().shown());

        stage.suspend();
        assertFalse(handle.get().shown());
        stage.publishFrame(new Transform().translate(10f, 0f, 0f));
        assertEquals(initialReads, frameReads.get(),
                "a suspended visual must not sample host frame sources");

        stage.resume();
        assertTrue(handle.get().shown());
        stage.publishFrame(new Transform().translate(10f, 0f, 0f));
        assertEquals(initialReads + 1, frameReads.get());
        stage.close();
    }

    @Test
    void rebindRetriesMissingResourcesWithoutDestroyingMountedPartsOrChangingPublications() {
        AtomicReference<McModelHandle> mounted = new AtomicReference<>();
        RigidPartVisualStage stage = new RigidPartVisualStage(
                BASE,
                List.of(ModelPart.of(MODEL), ModelPart.of(MODEL)),
                () -> null,
                new Transform(),
                (spec, instanceLoc) -> {
                    if (mounted.get() == null) {
                        McModelHandle handle = McModelHandleTestSupport.custom(
                                MODEL, instanceLoc, ModelInstanceFixture.minimal());
                        mounted.set(handle);
                        return handle;
                    }
                    return McModelHandle.of(MODEL, null, instanceLoc, null);
                });
        ManagedAnimationScope scope = scopeWith(stage);
        stage.bind(scope.renderPort());
        try {
            stage.prepareLogicalStep(new Transform().translate(20f, 0f, 0f));
            scope.advance(new ScopeAdvance(0.05, ScopeAdvanceCause.GAME_TICK));
            stage.maintainVisuals();
            long revision = scope.view().revision();

            assertFalse(stage.rebind());

            assertEquals(revision, scope.view().revision());
            assertEquals(1, stage.mountedPartCount());
            assertFalse(mounted.get().isDestroyed());
            assertTrue(mounted.get().shown());
        } finally {
            scope.close();
            stage.close();
        }
    }

    private static ManagedAnimationScope scopeWith(RigidPartVisualStage stage) {
        ManagedAnimationScope.Builder builder = ManagedAnimationScope.builder(
                UUID.randomUUID(),
                LogicalStepPolicy.fixed(1.0 / 20.0, 5));
        for (TargetBinding<?> target : stage.targetBindings()) {
            builder.target(target);
        }
        return builder.build();
    }

    private static float rootX(ModelInstance instance) {
        return instance.getSkeletonInstance().getTransform().getPosition().x;
    }
}
