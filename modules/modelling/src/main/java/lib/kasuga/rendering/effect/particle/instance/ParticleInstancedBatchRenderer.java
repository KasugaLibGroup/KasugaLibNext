package lib.kasuga.rendering.effect.particle.instance;

import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.particle.ParticleBatchRenderer;
import lib.kasuga.rendering.effect.particle.ParticleInstanceBuffer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Connects a particle pipeline to a replaceable graphics-API instancing backend. */
public final class ParticleInstancedBatchRenderer implements ParticleBatchRenderer {
    private final ParticleInstanceMesh mesh;
    private final ParticleInstanceRenderBackend backend;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ParticleInstancedBatchRenderer(
            ParticleInstanceMesh mesh,
            ParticleInstanceRenderBackend backend
    ) {
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public void render(ParticleInstanceBuffer instances, WorldRenderPipelineContext context) {
        if (!closed.get()) backend.draw(mesh, instances, context);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) backend.close();
    }
}
