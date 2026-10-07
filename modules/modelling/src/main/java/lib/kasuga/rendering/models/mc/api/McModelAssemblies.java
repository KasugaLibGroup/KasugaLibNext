package lib.kasuga.rendering.models.mc.api;

import lib.kasuga.rendering.models.mc.dynamic.fsm.KasugaModelPipelines;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.loaders.assembly.*;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;

/** Minecraft resource/pipeline adapter. Geometry, recipes, cache and rig definitions remain in render-core. */
public final class McModelAssemblies implements AutoCloseable {
    public record Reference(ResourceLocation model, @Nullable String modelName) {
        public Reference { Objects.requireNonNull(model); }
        public Reference(ResourceLocation model) { this(model, null); }
    }
    public record PublishedSource(ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline,
                                  ResourceLocation key) {
        public PublishedSource { Objects.requireNonNull(pipeline); Objects.requireNonNull(key); }
    }

    private final ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline;
    private final ModelAssemblyRegistry<Reference, ResourceLocation> registry;
    private final Map<ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?>, AutoCloseable> subscriptions = new IdentityHashMap<>();
    private final Map<ResourceLocation, ModelAssemblyDefinition<Reference>> manual = new LinkedHashMap<>();
    private Map<ResourceLocation, ModelAssemblyDefinition<Reference>> resources = Map.of();
    private boolean closed;

    public McModelAssemblies(ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline,
                             Function<Reference, PublishedSource> sources, ModelAssemblyCache cache) {
        this.pipeline = Objects.requireNonNull(pipeline);
        Objects.requireNonNull(sources);
        registry = new ModelAssemblyRegistry<>(reference -> {
            PublishedSource source = sources.apply(reference);
            if (source == null) return null;
            subscriptions.computeIfAbsent(source.pipeline(), observed -> observed.onModelChanged(this::sourceChanged));
            var model = source.pipeline().getModel(source.key());
            return model == null ? null : new ModelAssembly.Source(model, source.pipeline().modelRevision(source.key()));
        }, cache);
    }

    private void sourceChanged(ModelPipeLine.ModelChange<ResourceLocation> change) {
        if (change.previous() == null) return;
        RuntimeException failure = null;
        for (var id : registry.invalidate(change.previous())) {
            try { pipeline.removeModel(id); }
            catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }

    public ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline() { return pipeline; }
    public void register(ResourceLocation id, ModelAssemblyDefinition<Reference> definition) {
        requireOpen(); manual.put(Objects.requireNonNull(id), Objects.requireNonNull(definition)); registry.register(id, definition);
    }
    public boolean unregister(ResourceLocation id) {
        requireOpen(); boolean removed = manual.remove(id) != null;
        registry.remove(id); pipeline.removeModel(id);
        if (resources.containsKey(id)) registry.register(id, resources.get(id));
        return removed;
    }
    /** Apply a successfully parsed complete resource snapshot. Programmatic definitions take precedence. */
    public void replaceResourceDefinitions(Map<ResourceLocation, ModelAssemblyDefinition<Reference>> next) {
        requireOpen(); resources = Map.copyOf(next);
        Set<ResourceLocation> previous = Set.copyOf(registry.definitions().keySet());
        previous.forEach(registry::remove);
        resources.forEach(registry::register); manual.forEach(registry::register);
        registry.clearResolved(); pipeline.replaceModels(Map.of());
    }
    public Map<ResourceLocation, ModelAssemblyDefinition<Reference>> definitions() { return registry.definitions(); }
    @Nullable public ModelAssembly resolve(ResourceLocation id) {
        requireOpen();
        ModelAssembly result = registry.resolve(id);
        if (result != null && pipeline.getModel(id) != result.model()) pipeline.publishModels(Map.of(id, result.model()));
        return result;
    }
    @Nullable public ModelInstance createAndBind(ResourceLocation id, ResourceLocation instanceId, @Nullable Transform root) {
        if (resolve(id) == null) return null;
        ModelInstance instance = pipeline.createInstance(id, instanceId, root, null, null);
        try {
            if (!pipeline.isRendering(id, instanceId, "mc_backend")) pipeline.addToRenderer(id, instanceId, "mc_bridge", "mc_backend");
        } catch (RuntimeException | Error failure) {
            try { pipeline.removeInstance(id, instanceId); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return instance;
    }
    public McModelHandle handle(ResourceLocation id, ResourceLocation instanceId) {
        requireOpen();
        return McModelHandle.custom(id, null, instanceId, pose -> createAndBind(id, instanceId, pose), ignored -> pipeline);
    }
    public ModelAssemblyCache.Stats cacheStats() { return registry.cacheStats(); }
    private void requireOpen() { if (closed) throw new IllegalStateException("assembly service is closed"); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try { pipeline.replaceModels(Map.of()); } catch (RuntimeException cleanup) { failure = cleanup; }
        registry.clearResolved(); manual.clear(); resources = Map.of();
        for (AutoCloseable subscription : subscriptions.values()) {
            try { subscription.close(); }
            catch (Exception cleanup) {
                RuntimeException wrapped = new IllegalStateException("assembly subscription cleanup failed", cleanup);
                if (failure == null) failure = wrapped; else failure.addSuppressed(wrapped);
            }
        }
        subscriptions.clear();
        if (failure != null) throw failure;
    }
}
