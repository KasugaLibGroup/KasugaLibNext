package lib.kasuga.rendering.models.uml.framework.render;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;

/**
 * Creates a non-null render resource for one model instance on the owning render
 * thread. The receiving context owns the result and closes it if AutoCloseable;
 * it does not own the model instance or the backend services used by the factory.
 */
@FunctionalInterface
public interface RenderableFactory<R> {
    R createRenderable(ModelInstance instance);
}
