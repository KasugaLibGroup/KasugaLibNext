package lib.kasuga.rendering.models.uml.framework.render;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;

/**
 * One mounted instance's thread-owned render state. apply lazily creates and
 * caches a resource, not a pose sample; the backend handles frame sampling and
 * uploads. beforeRender supplies host metadata without allocating the resource.
 * Closing releases only the cached resource and is idempotent. A closed context
 * cannot be applied again. The render flag is an independent hard visibility gate.
 */
public interface RenderContext<R, Q, E> extends AutoCloseable {
    ModelInstance getModelInstance();
    boolean isRender();
    void setRender(boolean render);
    boolean isClosed();
    R apply();
    E beforeRender(Q context);
}
