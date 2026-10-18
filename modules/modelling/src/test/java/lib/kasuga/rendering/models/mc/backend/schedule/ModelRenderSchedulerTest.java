package lib.kasuga.rendering.models.mc.backend.schedule;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
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
import org.junit.jupiter.api.Test;

import java.util.List;

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

    /** Minimal real instance: the scheduler only ever uses object identity. */
    private static ModelInstance fakeInstance() {
        Bone root = new Bone("root", new Transform(), null);
        root.setChildren(new Bone[0]);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root,
                new Anchor[0], null, new Transform());
        Model model = new Model(new Vertex[0], new Mesh[0], new Bone[]{root},
                skeleton, new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        return new ModelInstance(model, null, null, null, null, null);
    }
}
