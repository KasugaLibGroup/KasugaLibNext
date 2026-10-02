package lib.kasuga.rendering.models.uml.framework.render;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;

import java.util.Map;

/**
 * Render-thread-owned mount registry and draw submission. add prepares a new
 * context before publishing it; a preparation failure leaves the old mount
 * intact. Replacement/removal releases the old context, never the model instance.
 * The registry view is read-only. close retires all mounts, attempting every
 * release and reporting failures, and prevents new mounts/render submissions.
 */
public interface RenderBackend<A, C extends RenderContext<?, Q, ?>, Q> extends AutoCloseable {
    void add(Object key, A adapter, ModelInstance instance);
    boolean contains(Object key);
    boolean remove(Object key);
    Map<Object, C> getRenderingObjects();
    void render(C renderable, Q context);
    void renderAllObjects(Q context);
    boolean isClosed();
}
