package lib.kasuga.rendering.models.mc.backend.schedule;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.dynamic.ModelPublicationBatch;
import lib.kasuga.rendering.models.mc.backend.BackendInstance;
import lib.kasuga.rendering.models.mc.backend.MCBackend;
import lib.kasuga.rendering.models.mc.backend.MCBridge;
import lib.kasuga.rendering.models.uml.framework.schedule.ModelRenderScheduling;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRenderSchedulerTest {

    @Test
    void compatibilityFacadeAndFrameworkSharePoliciesAndFrameMarks() {
        ModelInstance instance = fakeInstance();
        var scheduler = ModelRenderScheduling.scheduler();
        try {
            scheduler.setVisible(instance, false);
            assertFalse(ModelRenderScheduler.shouldRender(instance));
            ModelRenderScheduler.setMode(instance, RenderScheduleMode.VANILLA_RENDERER);
            assertEquals(lib.kasuga.rendering.models.uml.framework.schedule.RenderScheduleMode.HOST_RENDERER,
                    scheduler.mode(instance));
            ModelRenderScheduler.markRenderedThisFrame(instance);
            scheduler.flipFrame();
            assertTrue(ModelRenderScheduler.shouldRender(instance));
            scheduler.flipFrame();
            assertFalse(ModelRenderScheduler.shouldRender(instance));
        } finally {
            scheduler.detach(instance);
        }
    }

    @Test
    void defaultModeAlwaysRenders() {
        ModelInstance instance = fakeInstance();
        assertTrue(ModelRenderScheduler.shouldRender(instance));
        assertEquals(RenderScheduleMode.ALWAYS, ModelRenderScheduler.mode(instance));
        ModelRenderScheduler.detach(instance);
    }

    @Test
    void manualModeFollowsTheVisibilitySwitch() {
        ModelInstance instance = fakeInstance();
        ModelRenderScheduler.setVisible(instance, false);
        assertEquals(RenderScheduleMode.MANUAL, ModelRenderScheduler.mode(instance));
        assertFalse(ModelRenderScheduler.shouldRender(instance));
        ModelRenderScheduler.setVisible(instance, true);
        assertTrue(ModelRenderScheduler.shouldRender(instance));
        ModelRenderScheduler.detach(instance);
    }

    @Test
    void vanillaRendererMarksSurviveExactlyOnePipelineFlip() {
        ModelInstance instance = fakeInstance();
        ModelRenderScheduler.setMode(instance, RenderScheduleMode.VANILLA_RENDERER);

        // No marks yet — vanilla pass hasn't run.
        assertFalse(ModelRenderScheduler.shouldRender(instance));

        // Vanilla renderer invoked this frame.
        ModelRenderScheduler.markRenderedThisFrame(instance);
        assertTrue(ModelRenderScheduler.shouldRender(instance),
                "marks must be visible to the same frame's evaluation");

        // Global pipeline flips: consumed marks still gate this frame.
        ModelRenderScheduler.flipFrame();
        assertTrue(ModelRenderScheduler.shouldRender(instance));

        // Next frame without a fresh mark: culled by the vanilla dispatcher.
        ModelRenderScheduler.flipFrame();
        assertFalse(ModelRenderScheduler.shouldRender(instance));
        ModelRenderScheduler.detach(instance);
    }

    @Test
    void flipKeepsDifferentInstancesIndependent() {
        ModelInstance rendered = fakeInstance();
        ModelInstance culled = fakeInstance();
        ModelRenderScheduler.setMode(rendered, RenderScheduleMode.VANILLA_RENDERER);
        ModelRenderScheduler.setMode(culled, RenderScheduleMode.VANILLA_RENDERER);

        ModelRenderScheduler.markRenderedThisFrame(rendered);
        ModelRenderScheduler.flipFrame();

        assertTrue(ModelRenderScheduler.shouldRender(rendered));
        assertFalse(ModelRenderScheduler.shouldRender(culled));
        ModelRenderScheduler.detach(rendered);
        ModelRenderScheduler.detach(culled);
    }

    @Test
    void distanceGateUsesSquaredComparison() {
        ModelInstance instance = fakeInstance();
        ModelRenderScheduler.setMaxRenderDistance(instance, 32f);
        assertTrue(ModelRenderScheduler.withinRenderDistance(instance, 32f * 32f));
        assertFalse(ModelRenderScheduler.withinRenderDistance(instance, 32.5f * 32.5f));
        // Zero disables distance culling entirely.
        ModelRenderScheduler.setMaxRenderDistance(instance, 0f);
        assertTrue(ModelRenderScheduler.withinRenderDistance(instance, Float.MAX_VALUE));
        ModelRenderScheduler.detach(instance);
    }

    @Test
    void detachClearsAllState() {
        ModelInstance instance = fakeInstance();
        ModelRenderScheduler.setVisible(instance, false);
        ModelRenderScheduler.markRenderedThisFrame(instance);
        ModelRenderScheduler.flipFrame();

        ModelRenderScheduler.detach(instance);

        assertTrue(ModelRenderScheduler.shouldRender(instance),
                "a detached instance falls back to ALWAYS");
        assertEquals(0f, ModelRenderScheduler.maxRenderDistance(instance));
    }

    @Test
    void unloadedHostCanReturnWithAFreshInstanceAndDrawOnItsFirstVisibleFrame() throws Exception {
        AtomicReference<ModelInstance> host = new AtomicReference<>();
        UmlBlockEntityRenderer<BlockEntity> renderer =
                new UmlBlockEntityRenderer<>(ignored -> host.get(), 64);
        try (MCBackend backend = new MCBackend(); ModelPublicationBatch<String> publications = new ModelPublicationBatch<>()) {
            ModelPipeLine<String, String> pipeline = new ModelPipeLine.PublishedBuilder<String, String, BackendInstance>()
                    .withBridge("mc_bridge", new MCBridge()).withBackend("mc_backend", backend).build();
            publications.put(pipeline, Map.of("asset", fakeModel()));
            ModelInstance old = pipeline.createInstance("asset", "host", null, null, null);
            host.set(old);
            ModelRenderScheduler.setMode(old, RenderScheduleMode.VANILLA_RENDERER);
            renderer.render(null, 0, null, null, 0, 0);
            ModelRenderScheduler.flipFrame();
            assertTrue(ModelRenderScheduler.shouldRender(old));
            assertTrue(pipeline.removeInstance("asset", "host"));
            assertTrue(old.isClosed());
            renderer.render(null, 0, null, null, 0, 0);
            assertFalse(ModelRenderScheduler.wasMarkedThisFrame(old));
            host.set(null);
            renderer.render(null, 0, null, null, 0, 0);

            ModelInstance fresh = pipeline.createInstance("asset", "host", null, null, null);
            host.set(fresh);
            ModelRenderScheduler.setMode(fresh, RenderScheduleMode.VANILLA_RENDERER);
            ModelRenderScheduler.setMaxRenderDistance(fresh, 64f);
            assertFalse(ModelRenderScheduler.withinRenderDistance(fresh, 65 * 65));
            ModelRenderScheduler.flipFrame();
            assertFalse(ModelRenderScheduler.shouldRender(fresh));
            renderer.render(null, 0, null, null, 0, 0);
            ModelRenderScheduler.flipFrame();
            assertTrue(ModelRenderScheduler.withinRenderDistance(fresh, 63 * 63));
            assertTrue(ModelRenderScheduler.shouldRender(fresh));
        }
    }

    /** Minimal real instance: the scheduler only ever uses object identity. */
    private static ModelInstance fakeInstance() {
        return ModelInstance.forSimulation(fakeModel(), null);
    }

    private static Model fakeModel() {
        Bone root = new Bone("root", new Transform(), null);
        root.setChildren(new Bone[0]);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root,
                new Anchor[0], null, new Transform());
        return new Model(new Vertex[0], new Mesh[0], new Bone[]{root},
                skeleton, new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
    }
}
