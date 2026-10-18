package lib.kasuga.rendering.models.mc.backend.schedule;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.framework.schedule.ModelRenderScheduling;
import lib.kasuga.rendering.models.uml.framework.schedule.RenderScheduler;

import java.util.Objects;

/** Minecraft compatibility facade; all policy and frame state lives in UML framework. */
public final class ModelRenderScheduler {
    private ModelRenderScheduler() {}

    public static RenderScheduler<ModelInstance> scheduler() { return ModelRenderScheduling.scheduler(); }
    public static void setMode(ModelInstance instance, RenderScheduleMode mode) {
        scheduler().setMode(instance, switch (Objects.requireNonNull(mode, "mode")) {
            case ALWAYS -> lib.kasuga.rendering.models.uml.framework.schedule.RenderScheduleMode.ALWAYS;
            case MANUAL -> lib.kasuga.rendering.models.uml.framework.schedule.RenderScheduleMode.MANUAL;
            case VANILLA_RENDERER -> lib.kasuga.rendering.models.uml.framework.schedule.RenderScheduleMode.HOST_RENDERER;
        });
    }
    public static RenderScheduleMode mode(ModelInstance instance) {
        return switch (scheduler().mode(instance)) {
            case ALWAYS -> RenderScheduleMode.ALWAYS;
            case MANUAL -> RenderScheduleMode.MANUAL;
            case HOST_RENDERER -> RenderScheduleMode.VANILLA_RENDERER;
        };
    }
    public static void setVisible(ModelInstance instance, boolean visible) { scheduler().setVisible(instance, visible); }
    public static void markRenderedThisFrame(ModelInstance instance) { scheduler().markRenderedThisFrame(instance); }
    public static void setMaxRenderDistance(ModelInstance instance, float blocks) { scheduler().setMaxRenderDistance(instance, blocks); }
    public static float maxRenderDistance(ModelInstance instance) { return scheduler().maxRenderDistance(instance); }
    public static void flipFrame() { scheduler().flipFrame(); }
    public static boolean wasMarkedThisFrame(ModelInstance instance) { return scheduler().wasMarkedThisFrame(instance); }
    public static boolean shouldRender(ModelInstance instance) { return scheduler().shouldRender(instance); }
    public static boolean withinRenderDistance(ModelInstance instance, float distanceSquared) {
        return scheduler().withinRenderDistance(instance, distanceSquared);
    }
    public static void detach(ModelInstance instance) { scheduler().detach(instance); }
    public static void resetAll() { scheduler().resetAll(); }
}
