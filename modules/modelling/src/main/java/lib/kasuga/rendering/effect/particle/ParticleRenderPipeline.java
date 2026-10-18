package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.PipelineRegistration;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.WorldRenderPipelineRegistry;
import lib.kasuga.rendering.effect.pipeline.CompiledRenderPipeline;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Registered world pipeline whose render callback consumes a whole transform-instance group. */
public final class ParticleRenderPipeline implements PipelineRegistration {
    private final ResourceLocation owner;
    private final RenderPipelineDescriptor descriptor;
    private final ParticleBatchRenderer renderer;
    private final ParticleGroup group = new ParticleGroup();
    private final ParticleInstanceBuffer instances = new ParticleInstanceBuffer(256);
    private final ParticleInstanceBuffer depthSortedInstances = new ParticleInstanceBuffer(256);
    private final ParticleDepthSorter depthSorter = new ParticleDepthSorter();
    private final CopyOnWriteArrayList<ParticleSource> sources = new CopyOnWriteArrayList<>();
    private final PipelineRegistration registration;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int lastVisibleCount;
    private volatile long lastRenderNanos;
    private volatile boolean sortBackToFront;

    private ParticleRenderPipeline(ResourceLocation owner, RenderPipelineDescriptor descriptor,
                                   DuplicatePolicy duplicatePolicy, ParticleBatchRenderer renderer) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        registration = WorldRenderPipelineRegistry.register(
                owner, descriptor, duplicatePolicy, this::render
        );
        try {
            ClientParticleRuntime.add(this, duplicatePolicy);
        } catch (RuntimeException exception) {
            registration.close();
            renderer.close();
            throw exception;
        }
    }

    public static ParticleRenderPipeline register(
            ResourceLocation owner,
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            ParticleBatchRenderer renderer
    ) {
        return new ParticleRenderPipeline(owner, descriptor, duplicatePolicy, renderer);
    }

    public ParticleHandle add(ParticleInstance instance) {
        requireOpen();
        return group.add(instance);
    }

    public ParticleHandle create(Transform transform) {
        return add(ParticleInstance.builder(transform).build());
    }

    /** Creates a placeable source that is automatically updated by this pipeline. */
    public ParticleSource source(ParticleSource.Settings settings) {
        requireOpen();
        ParticleSource source = new ParticleSource(group, settings);
        sources.add(source);
        return source;
    }

    public List<ParticleSource> sources() {
        return List.copyOf(sources);
    }

    public boolean removeSource(ParticleSource source) {
        Objects.requireNonNull(source, "source");
        if (!sources.remove(source)) return false;
        source.close();
        return true;
    }

    public void clearSources() {
        sources.forEach(ParticleSource::close);
        sources.clear();
    }

    public ParticleGroup group() {
        return group;
    }

    public void controller(ParticleGroupBehavior controller) {
        requireOpen();
        group.controller(controller);
    }

    public void bufferController(ParticleBufferGroupBehavior controller) {
        requireOpen();
        group.bufferController(controller);
    }

    /**
     * Enables CPU instance sorting from farthest to nearest before the batch renderer receives
     * the packed buffer. Use this for conventional translucent alpha blending, including
     * hardware-instanced renderers that cannot use Minecraft's quad upload sorter.
     */
    public void sortBackToFront(boolean value) {
        requireOpen();
        sortBackToFront = value;
    }

    public boolean sortsBackToFront() {
        return sortBackToFront;
    }

    public int activeCount() {
        return group.size();
    }

    public int lastVisibleCount() {
        return lastVisibleCount;
    }

    public long lastRenderNanos() {
        return lastRenderNanos;
    }

    public void clear() {
        group.clear();
    }

    void clearWorldState() {
        clearSources();
        group.clear();
    }

    void update(ClientLevel level) {
        if (closed.get()) return;
        sources.removeIf(ParticleSource::isClosed);
        sources.forEach(source -> source.update(level));
        group.update(level);
    }

    private void render(WorldRenderPipelineContext context) {
        if (closed.get()) return;
        instances.beginWrite();
        group.writeVisible(instances);
        lastVisibleCount = instances.size();
        if (instances.isEmpty()) {
            lastRenderNanos = 0;
            return;
        }
        long started = System.nanoTime();
        try {
            ParticleInstanceBuffer renderInstances = instances;
            if (sortBackToFront && instances.size() > 1) {
                var camera = context.camera().getPosition();
                depthSorter.sortBackToFront(
                        instances, (float) camera.x, (float) camera.y, (float) camera.z
                );
                depthSortedInstances.copyFrom(instances, depthSorter);
                renderInstances = depthSortedInstances;
            }
            renderer.render(renderInstances, context);
        } finally {
            lastRenderNanos = System.nanoTime() - started;
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Particle pipeline is closed: " + id());
        }
    }

    @Override
    public ResourceLocation id() {
        return descriptor.id();
    }

    @Override
    public ResourceLocation owner() {
        return owner;
    }

    @Override
    public boolean isActive() {
        return !closed.get() && registration.isActive();
    }

    @Override
    public RenderPipelineDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public CompiledRenderPipeline compiledPipeline() {
        return registration.compiledPipeline();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ClientParticleRuntime.remove(this);
        registration.close();
        clearSources();
        group.clear();
        renderer.close();
    }
}
