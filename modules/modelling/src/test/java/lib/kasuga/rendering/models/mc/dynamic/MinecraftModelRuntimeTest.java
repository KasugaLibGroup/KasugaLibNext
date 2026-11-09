package lib.kasuga.rendering.models.mc.dynamic;

import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollConfig;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollRuntime;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.ModelPoseBinding;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.physics.ModelPhysicsScene;
import lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonRagdoll;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule;
import lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsBuilder;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics.RigidBody;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftModelRuntimeTest {
    @Test
    void ordinaryModelsUseTheCanonicalPipelineOnceWithoutBeingRendered() {
        try (ModelInstance instance = ModelInstanceFixture.minimal()) {
            AtomicInteger samples = samples(instance);
            AtomicInteger stages = stages(instance);
            MinecraftModelRuntime.register(instance);
            MinecraftModelRuntime.register(instance);
            try {
                MinecraftModelRuntime.evaluateFrame(0.25f, 1f / 60f);
                assertEquals(1, samples.get());
                assertEquals(1, stages.get());
                Object frame = new Object();
                instance.sample(0.25f, frame);
                instance.sample(0.25f, frame);
                instance.prepareRenderFrame(frame);
                assertEquals(1, samples.get(), "backend sampling must consume the prepared pose");
                assertEquals(1, stages.get(), "upload must not run the animated pipeline a second time");
            } finally {
                MinecraftModelRuntime.unregister(instance);
            }
        }
    }

    @Test
    void compatibilityModesAndCountsUseTheGeneralRegistry() {
        int baseline = MinecraftRagdollRuntime.registeredCount();
        try (ModelInstance instance = ModelInstanceFixture.minimal()) {
            AtomicInteger stages = stages(instance);
            MinecraftRagdollRuntime.register(instance, MinecraftRagdollConfig.UpdateMode.RENDER_FRAME);
            try {
                assertEquals(baseline + 1, MinecraftModelRuntime.registeredCount());
                MinecraftModelRuntime.evaluateFrame(0.5f, 1f / 60f);
                assertEquals(1, stages.get());
                MinecraftRagdollRuntime.register(instance, MinecraftRagdollConfig.UpdateMode.MANUAL);
                assertEquals(baseline, MinecraftRagdollRuntime.registeredCount());
                MinecraftModelRuntime.evaluateFrame(0.5f, 1f / 60f);
                assertEquals(1, stages.get());
                MinecraftRagdollRuntime.step(instance, 1f / 60f);
                assertEquals(1, stages.get(), "the legacy physics-only step remains a no-op without physics");
                MinecraftModelRuntime.step(instance, 1f / 60f);
                assertEquals(2, stages.get(), "the general manual step also supports ordinary skeletons");
            } finally {
                MinecraftRagdollRuntime.unregister(instance);
            }
        }
    }

    @Test
    void inactiveClientAndInvalidDeltasDoNotSampleOrEvaluate() {
        try (ModelInstance instance = ModelInstanceFixture.minimal()) {
            AtomicInteger samples = samples(instance);
            AtomicInteger stages = stages(instance);
            MinecraftModelRuntime.register(instance);
            try {
                MinecraftModelRuntime.evaluateFrame(0.5f, 0.1f, false, false);
                MinecraftModelRuntime.evaluateFrame(0.5f, 0.1f, true, true);
                for (float dt : new float[]{0f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
                    MinecraftModelRuntime.evaluateFrame(0.5f, dt);
                }
                assertEquals(0, samples.get());
                assertEquals(0, stages.get());
                assertThrows(IllegalArgumentException.class,
                        () -> MinecraftModelRuntime.evaluateFrame(Float.NaN, 0.1f));
                assertEquals(0, samples.get());
                MinecraftModelRuntime.evaluateFrame(0.5f, 0.1f, true, false);
                assertEquals(1, samples.get());
                assertEquals(1, stages.get());
            } finally {
                MinecraftModelRuntime.unregister(instance);
            }
        }
    }

    @Test
    void closedModelsAreSweptAndCannotBeRegisteredAgain() {
        int baseline = MinecraftModelRuntime.registeredCount();
        ModelInstance instance = ModelInstanceFixture.minimal();
        MinecraftModelRuntime.register(instance);
        instance.close();
        assertEquals(baseline, MinecraftRagdollRuntime.registeredCount());
        MinecraftModelRuntime.register(instance);
        MinecraftModelRuntime.evaluateFrame(0.5f, 0.1f);
        assertFalse(MinecraftModelRuntime.isRegistered(instance));
        assertEquals(baseline, MinecraftModelRuntime.registeredCount());
    }

    @Test
    void unregisteringDuringAFrameDoesNotInvalidateTheSnapshot() {
        try (ModelInstance instance = ModelInstanceFixture.minimal()) {
            AtomicInteger samples = new AtomicInteger();
            ModelPoseBinding.install(instance, new PoseDriver() {
                @Override public void sample(float partialTick) {
                    samples.incrementAndGet();
                    MinecraftModelRuntime.unregister(instance);
                }
            });
            MinecraftModelRuntime.register(instance);
            try {
                MinecraftModelRuntime.evaluateFrame(0.5f, 0.1f);
                MinecraftModelRuntime.evaluateFrame(0.5f, 0.1f);
                assertEquals(1, samples.get());
                assertFalse(MinecraftModelRuntime.isRegistered(instance));
            } finally {
                MinecraftModelRuntime.unregister(instance);
            }
        }
    }

    @Test
    void onlyTheGeneralRuntimeSubscribesToEvents() {
        assertNotNull(MinecraftModelRuntime.class.getAnnotation(EventBusSubscriber.class));
        assertNull(MinecraftRagdollRuntime.class.getAnnotation(EventBusSubscriber.class));
        assertTrue(Arrays.stream(MinecraftRagdollRuntime.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(SubscribeEvent.class)));
    }

    @Tag("box3d")
    @Test
    void explicitSceneAndInstanceRegistrationsShareOneEvaluation() {
        try (ModelInstance first = physicalModel(); ModelInstance second = physicalModel();
             ModelPhysicsScene scene = new ModelPhysicsScene(new Vector3d(), 1)) {
            scene.attach(first);
            scene.attach(second);
            AtomicInteger samples = samples(first);
            AtomicInteger firstStages = stages(first);
            AtomicInteger secondStages = stages(second);
            MinecraftModelRuntime.register(first);
            MinecraftModelRuntime.register(scene);
            try {
                assertTrue(MinecraftModelRuntime.isRegistered(second), "scene members are auto scheduled");
                MinecraftModelRuntime.evaluateFrame(0.5f, 1f / 60f);
                first.sample(0.5f, new Object());
                assertEquals(1, samples.get(), "the standalone registration must not resample after the scene");
                assertEquals(1, firstStages.get());
                assertEquals(1, secondStages.get());
            } finally {
                MinecraftModelRuntime.unregister(first);
                MinecraftModelRuntime.unregister(scene);
            }
        }
    }

    @Tag("box3d")
    @Test
    void standaloneRegistrationFollowsMigrationAndInfersUnregisteredScenes() {
        int sceneBaseline = MinecraftModelRuntime.registeredSceneCount();
        try (ModelInstance first = physicalModel(); ModelInstance second = physicalModel();
             ModelPhysicsScene oldScene = new ModelPhysicsScene(new Vector3d(), 1);
             ModelPhysicsScene newScene = new ModelPhysicsScene(new Vector3d(50, 0, 0), 1)) {
            SkeletonRagdoll firstPhysics = first.enablePhysics();
            SkeletonRagdoll secondPhysics = second.enablePhysics();
            AtomicInteger firstStages = stages(first);
            AtomicInteger secondStages = stages(second);
            AtomicInteger samples = samples(first);
            MinecraftModelRuntime.register(first);
            MinecraftModelRuntime.register(second);
            try {
                MinecraftModelRuntime.evaluateFrame(0.5f, 1f / 60f);
                firstPhysics.moveTo(oldScene);
                secondPhysics.moveTo(oldScene);
                MinecraftModelRuntime.evaluateFrame(0.5f, 1f / 60f);
                assertEquals(2, firstStages.get());
                assertEquals(2, secondStages.get());
                firstPhysics.moveTo(newScene);
                secondPhysics.moveTo(newScene);
                MinecraftModelRuntime.register(newScene);
                MinecraftModelRuntime.evaluateFrame(0.5f, 1f / 60f);
                firstPhysics.moveTo(null);
                MinecraftModelRuntime.evaluateFrame(0.5f, 1f / 60f);
                assertEquals(4, firstStages.get());
                assertEquals(4, secondStages.get());
                assertEquals(4, samples.get());
                assertEquals(sceneBaseline + 1, MinecraftModelRuntime.registeredSceneCount(),
                        "inferred scenes must not change explicit registration counts");
            } finally {
                MinecraftModelRuntime.unregister(first);
                MinecraftModelRuntime.unregister(second);
                MinecraftModelRuntime.unregister(newScene);
            }
        }
    }

    @Tag("box3d")
    @Test
    void inferredSceneAlsoCoversMembersWithoutStandaloneRegistration() {
        try (ModelInstance first = physicalModel(); ModelInstance second = physicalModel();
             ModelPhysicsScene scene = new ModelPhysicsScene(new Vector3d(), 1)) {
            scene.attach(first);
            scene.attach(second);
            AtomicInteger stages = stages(second);
            MinecraftModelRuntime.register(first);
            try {
                assertTrue(MinecraftModelRuntime.isRegistered(second));
                MinecraftModelRuntime.evaluateFrame(0.5f, 1f / 60f);
                assertEquals(1, stages.get());
                MinecraftModelRuntime.unregister(first);
                assertFalse(MinecraftModelRuntime.isRegistered(second));
            } finally {
                MinecraftModelRuntime.unregister(first);
            }
        }
    }

    @Tag("box3d")
    @Test
    void disabledSceneMembersStillOwnOneAnimationEvaluation() {
        try (ModelInstance instance = physicalModel();
             ModelPhysicsScene scene = new ModelPhysicsScene(new Vector3d(), 1)) {
            SkeletonRagdoll relaxation = scene.attach(instance);
            instance.disablePhysics();
            assertFalse(relaxation.enabled());
            AtomicInteger samples = samples(instance);
            AtomicInteger stages = stages(instance);
            MinecraftModelRuntime.register(instance);
            MinecraftModelRuntime.register(scene);
            try {
                MinecraftModelRuntime.evaluateFrame(0.5f, 1f / 60f);
                assertEquals(1, samples.get());
                assertEquals(1, stages.get());
                MinecraftModelRuntime.unregister(scene);
                assertTrue(MinecraftModelRuntime.isRegistered(scene),
                        "disabled adapters retain scene ownership through the standalone registration");
                MinecraftModelRuntime.evaluateFrame(0.5f, 1f / 60f);
                instance.sample(0.5f, new Object());
                assertEquals(2, samples.get());
                assertEquals(2, stages.get());
            } finally {
                MinecraftModelRuntime.unregister(instance);
                MinecraftModelRuntime.unregister(scene);
            }
        }
    }

    @Tag("box3d")
    @Test
    void sceneClosureSweepsRegistrationAndLeavesAnAnimatedModel() {
        int baseline = MinecraftModelRuntime.registeredSceneCount();
        try (ModelInstance instance = physicalModel();
             ModelPhysicsScene scene = new ModelPhysicsScene(new Vector3d(), 1)) {
            scene.attach(instance);
            AtomicInteger stages = stages(instance);
            MinecraftModelRuntime.register(instance);
            MinecraftModelRuntime.register(scene);
            try {
                scene.close();
                assertEquals(baseline, MinecraftModelRuntime.registeredSceneCount());
                MinecraftModelRuntime.evaluateFrame(0.5f, 1f / 60f);
                assertEquals(1, stages.get());
                MinecraftModelRuntime.register(scene);
                assertEquals(baseline, MinecraftModelRuntime.registeredSceneCount());
            } finally {
                MinecraftModelRuntime.unregister(instance);
                MinecraftModelRuntime.unregister(scene);
            }
        }
    }

    private static ModelInstance physicalModel() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        var skeleton = instance.getModel().getSkeleton();
        var body = new RigidBody("body", "body", skeleton.getBones()[0], 0, 0,
                RigidBody.SPHERE, new Vector3f(0.1f), new Vector3f(), new Vector3f(),
                1f, 0f, 0f, 0f, 0f, RigidBody.DYNAMIC);
        new SkeletonDynamicsBuilder(skeleton).physics(List.of(body), List.of()).attach();
        return instance;
    }

    private static AtomicInteger samples(ModelInstance instance) {
        AtomicInteger count = new AtomicInteger();
        ModelPoseBinding.install(instance, new PoseDriver() {
            @Override public void sample(float partialTick) { count.incrementAndGet(); }
        });
        return count;
    }

    private static AtomicInteger stages(ModelInstance instance) {
        AtomicInteger count = new AtomicInteger();
        instance.getTickLoop().addPostPhysics("runtime-probe", new ModelTickLoopModule() {
            @Override public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float dt) {
                count.incrementAndGet();
            }
            @Override public void destroy(Model model) {}
        });
        return count;
    }
}
