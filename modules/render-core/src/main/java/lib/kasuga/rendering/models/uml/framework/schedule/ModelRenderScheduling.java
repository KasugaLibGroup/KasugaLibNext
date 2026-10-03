package lib.kasuga.rendering.models.uml.framework.schedule;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;

/** Shared scheduling domain for the model pipeline and host-renderer adapters. */
public final class ModelRenderScheduling {
    private static final RenderScheduler<ModelInstance> SCHEDULER = new DefaultRenderScheduler<>();

    private ModelRenderScheduling() {}

    public static RenderScheduler<ModelInstance> scheduler() {
        return SCHEDULER;
    }
}
