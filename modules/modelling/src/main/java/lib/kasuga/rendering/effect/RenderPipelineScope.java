package lib.kasuga.rendering.effect;

import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.effect.particle.ParticleBatchRenderer;
import lib.kasuga.rendering.effect.particle.ParticleRenderPipeline;
import lib.kasuga.rendering.effect.post.PostProcessPass;
import lib.kasuga.rendering.effect.post.PostProcessPipelineRegistry;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraph;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistration;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistry;
import lib.kasuga.rendering.effect.shader.RenderShaderDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderRegistry;
import lib.kasuga.rendering.effect.shader.ShaderLoadListener;
import lib.kasuga.rendering.effect.shader.ShaderRegistration;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Owner-scoped registrar that releases all registrations in reverse order. */
public final class RenderPipelineScope implements RenderPipelineRegistrar, AutoCloseable {
    private final ResourceLocation owner;
    private final Deque<AutoCloseable> registrations = new ArrayDeque<>();
    private boolean closed;

    public static RenderPipelineScope create(ResourceLocation owner) {
        return new RenderPipelineScope(owner);
    }

    private RenderPipelineScope(ResourceLocation owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override
    public ResourceLocation owner() {
        return owner;
    }

    /** Creates a separately identified child registrar owned by this scope. */
    public RenderPipelineScope child(ResourceLocation childOwner) {
        return own(create(childOwner));
    }

    public synchronized <T extends AutoCloseable> T own(T registration) {
        Objects.requireNonNull(registration, "registration");
        if (closed) {
            IllegalStateException failure = new IllegalStateException("Render pipeline scope is closed: " + owner);
            try {
                registration.close();
            } catch (Exception exception) {
                failure.addSuppressed(exception);
            }
            throw failure;
        }
        if (registrations.stream().noneMatch(existing -> existing == registration)) {
            registrations.addFirst(registration);
        }
        return registration;
    }

    @Override
    public ShaderRegistration shader(
            RenderShaderDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            ShaderLoadListener listener
    ) {
        return own(RenderShaderRegistry.register(owner, descriptor, duplicatePolicy, listener));
    }

    @Override
    public PipelineRegistration world(
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            WorldRenderPipeline pipeline
    ) {
        return own(WorldRenderPipelineRegistry.register(owner, descriptor, duplicatePolicy, pipeline));
    }

    @Override
    public PipelineRegistration postProcess(
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            PostProcessPass pass
    ) {
        return own(PostProcessPipelineRegistry.register(owner, descriptor, duplicatePolicy, pass));
    }

    @Override
    public PostProcessGraphRegistration graph(
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            PostProcessGraph graph
    ) {
        return own(PostProcessGraphRegistry.register(owner, descriptor, duplicatePolicy, graph));
    }

    @Override
    public <T extends RenderEffect> EffectRenderPipeline<T> effects(
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            boolean sortBackToFront,
            EffectRenderer<T> renderer
    ) {
        return own(EffectRenderPipeline.register(
                owner, descriptor, duplicatePolicy, sortBackToFront, renderer
        ));
    }

    @Override
    public ParticleRenderPipeline particles(
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            ParticleBatchRenderer renderer
    ) {
        return own(ParticleRenderPipeline.register(owner, descriptor, duplicatePolicy, renderer));
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized int registrationCount() {
        return registrations.size();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        while (true) {
            AutoCloseable registration;
            synchronized (this) {
                if (!closed) closed = true;
                registration = registrations.pollFirst();
            }
            if (registration == null) break;
            try {
                registration.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new IllegalStateException("Failed to close render pipeline scope " + owner, exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) throw failure;
    }
}
