package lib.kasuga.rendering.models.mc.api;

import lib.kasuga.rendering.models.mc.dynamic.fsm.KasugaModelPipelines;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.dynamic.ModelPublicationBatch;
import lib.kasuga.rendering.models.uml.dynamic.ModelPublicationCoordinator;
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
    public record PublishedSource(ModelPipeLine<ResourceLocation, ResourceLocation> pipeline,
                                  ResourceLocation key) {
        public PublishedSource { Objects.requireNonNull(pipeline); Objects.requireNonNull(key); }
    }

    private final ModelPipeLine<ResourceLocation, ResourceLocation> pipeline;
    private final ModelAssemblyRegistry<Reference, ResourceLocation> registry;
    private final ModelPublicationBatch<ResourceLocation> publication;
    private final AutoCloseable preparationSubscription;
    private final Map<ResourceLocation, ModelAssemblyDefinition<Reference>> manual = new LinkedHashMap<>();
    private Map<ResourceLocation, ModelAssemblyDefinition<Reference>> resources = Map.of();
    private boolean closed;

    public McModelAssemblies(ModelPipeLine<ResourceLocation, ResourceLocation> pipeline,
                             Function<Reference, PublishedSource> sources, ModelAssemblyCache cache) {
        this.pipeline = Objects.requireNonNull(pipeline);
        this.publication = new ModelPublicationBatch<>(pipeline.publicationCoordinator());
        Objects.requireNonNull(sources);
        registry = new ModelAssemblyRegistry<>(reference -> {
            PublishedSource source = sources.apply(reference);
            if (source == null) return null;
            if (source.pipeline().publicationCoordinator() != pipeline.publicationCoordinator()) {
                throw new IllegalArgumentException("Assembly sources must share a publication domain");
            }
            var model = source.pipeline().getModel(source.key());
            return model == null ? null : new ModelAssembly.Source(model, source.pipeline().modelRevision(source.key()));
        }, cache);
        preparationSubscription = pipeline.publicationCoordinator().onPrepare(preparation -> {
            var changes = preparation.changes();
            preparation.beforeNotifications(registry, () -> {
                for (var change : changes) if (change.previous() != null) registry.invalidate(change.previous());
            });
            for (var change : changes) {
                if (change.previous() == null) continue;
                for (var id : registry.dependentIds(change.previous()))
                    preparation.removeIfOwned(publication.publisher(), pipeline, id);
            }
        });
    }

    public ModelPipeLine<ResourceLocation, ResourceLocation> pipeline() { return pipeline; }
    public void register(ResourceLocation id, ModelAssemblyDefinition<Reference> definition) {
        requireOpen(); manual.put(Objects.requireNonNull(id), Objects.requireNonNull(definition)); registry.register(id, definition);
    }
    public boolean unregister(ResourceLocation id) {
        requireOpen();
        RuntimeException failure = null;
        try { publication.remove(pipeline, id); }
        catch (ModelPublicationCoordinator.PublicationCommittedException committed) { failure = committed; }
        boolean removed = manual.remove(id) != null;
        registry.remove(id);
        if (resources.containsKey(id)) registry.register(id, resources.get(id));
        if (failure != null) throw failure;
        return removed;
    }
    /** Apply a successfully parsed complete resource snapshot. Programmatic definitions take precedence. */
    public void replaceResourceDefinitions(Map<ResourceLocation, ModelAssemblyDefinition<Reference>> next) {
        requireOpen();
        Map<ResourceLocation, ModelAssemblyDefinition<Reference>> prepared = Map.copyOf(next);
        publication.publisher().replace(Map.of(), ignored -> {}, () -> applyResourceDefinitions(prepared));
    }

    /** Commits source models and removes derived models in the same generation. */
    public <K> void publishResourceGeneration(
            Map<ResourceLocation, ModelAssemblyDefinition<Reference>> next,
            ModelPublicationBatch<K> sources,
            Map<? extends ModelPipeLine<K, ?>, ? extends Map<K, lib.kasuga.rendering.models.uml.structure.Model>> models) {
        requireOpen();
        Map<ResourceLocation, ModelAssemblyDefinition<Reference>> prepared = Map.copyOf(next);
        sources.publisher().replace(models,
                preparation -> preparation.removeAll(publication.publisher(), pipeline),
                () -> applyResourceDefinitions(prepared));
    }

    private void applyResourceDefinitions(Map<ResourceLocation, ModelAssemblyDefinition<Reference>> prepared) {
        resources = prepared;
        Set<ResourceLocation> previous = Set.copyOf(registry.definitions().keySet());
        previous.forEach(registry::remove);
        resources.forEach(registry::register); manual.forEach(registry::register);
        registry.clearResolved();
    }
    public Map<ResourceLocation, ModelAssemblyDefinition<Reference>> definitions() { return registry.definitions(); }
    @Nullable public ModelAssembly resolve(ResourceLocation id) {
        requireOpen();
        ModelAssembly result = registry.resolve(id);
        if (result != null) publication.put(pipeline, Map.of(id, result.model()));
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
        RuntimeException failure = null;
        try { publication.close(); }
        catch (ModelPublicationCoordinator.PublicationCommittedException committed) { failure = committed; }
        closed = true;
        registry.clearResolved(); manual.clear(); resources = Map.of();
        try { preparationSubscription.close(); }
        catch (Exception cleanup) {
            RuntimeException wrapped = new IllegalStateException("assembly preparation subscription cleanup failed", cleanup);
            if (failure == null) failure = wrapped; else failure.addSuppressed(wrapped);
        }
        if (failure != null) throw failure;
    }
}
