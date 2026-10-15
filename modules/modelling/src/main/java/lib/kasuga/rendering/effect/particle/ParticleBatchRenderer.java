package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.effect.WorldRenderPipelineContext;

/**
 * Renders one complete instance batch. Backends may expand it into a shared Minecraft buffer today
 * or upload instance data and issue a hardware-instanced draw without changing the particle model.
 */
@FunctionalInterface
public interface ParticleBatchRenderer extends AutoCloseable {
    void render(ParticleInstanceBuffer instances, WorldRenderPipelineContext context);

    @Override
    default void close() {
    }
}
