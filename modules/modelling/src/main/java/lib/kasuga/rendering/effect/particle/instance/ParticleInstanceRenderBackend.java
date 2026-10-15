package lib.kasuga.rendering.effect.particle.instance;

import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.particle.ParticleInstanceBuffer;

/**
 * Graphics-API adapter for one instanced particle draw.
 *
 * <p>Particle simulation and mesh descriptions depend only on this contract. OpenGL/Vulkan
 * resource handles belong in implementations.</p>
 */
public interface ParticleInstanceRenderBackend extends AutoCloseable {
    void draw(
            ParticleInstanceMesh mesh,
            ParticleInstanceBuffer instances,
            WorldRenderPipelineContext context
    );

    @Override
    void close();
}
