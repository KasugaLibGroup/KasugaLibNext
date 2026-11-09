package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.backend.Backend;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.loaders.ModelLoader;
import lib.kasuga.rendering.models.uml.loaders.sources.Source;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.MeshData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.VertexData;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.data.ModelInstanceData;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSetInstance;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.AnchorData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonInstanceData;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

public class ModelPipeLine<K, I> {

    @FunctionalInterface
    private interface ModelPreparation<K> {
        Map<K, Model> prepare(K source, @Nullable String sourceLoaderName);
    }

    private interface Renderer {
        void add(ModelInstance instance, String bridgeName);
        boolean contains(ModelInstance instance);
        boolean remove(ModelInstance instance);
    }

    private final ModelPublicationCoordinator coordinator;
    public ModelPublicationCoordinator publicationCoordinator() { return coordinator; }
    private final ModelPreparation<K> preparation;
    private final Set<String> bridgeNames;
    private final Map<String, Renderer> backends;

    private final Map<K,
            HashMap<I, ModelInstance>> modelInstances;
    private final List<Consumer<ModelChange<K>>> modelListeners = new ArrayList<>();

    public record ModelChange<K>(K key, @Nullable Model previous, @Nullable Model current, long revision) {}

    /** Game-thread notification, before retired instances are closed. Close the subscription to unregister. */
    public AutoCloseable onModelChanged(Consumer<ModelChange<K>> listener) {
        coordinator.checkThread();
        modelListeners.add(Objects.requireNonNull(listener));
        return () -> {
            coordinator.checkThread();
            modelListeners.remove(listener);
        };
    }

    @Nullable public Model getModel(K key) { return coordinator.getModel(this, key); }
    public long modelRevision(K key) { return coordinator.modelRevision(this, key); }
    /** Finds a registered instance once; callers can retain the key for subsequent constant-time checks. */
    @Nullable public K modelKeyOf(ModelInstance instance) {
        coordinator.checkThread();
        for (var entry : modelInstances.entrySet()) {
            for (ModelInstance candidate : entry.getValue().values()) if (candidate == instance) return entry.getKey();
        }
        return null;
    }

    private <S, B, T> ModelPipeLine(SourceManager<S> sourceManager,
                          ModelLoader<S, K, T> loader,
                          Map<String, Bridge<B>> bridges,
                          Map<String, Backend<Bridge<B>, B, ?, ?>> backends,
                          Map<SourceType, HashMap<String, SourceManager<?>>> sidedSources,
                          ModelPublicationCoordinator coordinator) {
        this.coordinator = coordinator;
        this.preparation = sourceManager == null || loader == null ? null
                : new LoadingBinding<>(sourceManager, loader, sidedSources)::prepare;
        this.bridgeNames = Set.copyOf(bridges.keySet());
        Map<String, Bridge<B>> boundBridges = Map.copyOf(bridges);
        var renderers = new HashMap<String, Renderer>();
        backends.forEach((name, backend) -> renderers.put(name, new Renderer() {
            @Override public void add(ModelInstance instance, String bridgeName) {
                backend.add(instance, boundBridges.get(bridgeName), instance);
            }
            @Override public boolean contains(ModelInstance instance) { return backend.contains(instance); }
            @Override public boolean remove(ModelInstance instance) { return backend.remove(instance); }
        }));
        this.backends = Map.copyOf(renderers);
        this.modelInstances = new HashMap<>();
    }

    /** Loads without publishing. Source and texture types are captured at construction. */
    public Map<K, Model> prepareModel(K source, @Nullable String sourceLoaderName) {
        if (preparation == null) throw new IllegalStateException("This pipeline accepts published models only");
        return preparation.prepare(source, sourceLoaderName);
    }

    private record LoadingBinding<S, K, T>(SourceManager<S> sources, ModelLoader<S, K, T> loader,
                          Map<SourceType, HashMap<String, SourceManager<?>>> sidedSources) {
        @SuppressWarnings("unchecked")
        Map<K, Model> prepare(K source, @Nullable String sourceLoaderName) {
            Source<?, S> manager = sourceLoaderName == null ? null : sources.getSource(sourceLoaderName);
            if (manager == null) {
                for (Source<?, S> candidate : sources.getSources().values()) {
                    if (candidate.isValidInput(source)) { manager = candidate; break; }
                }
            }
            if (manager == null) throw new IllegalArgumentException("No source manager found for source: " + source);
            Optional<S> output = ((Source<Object, S>) manager).getInput(source);
            if (output.isEmpty()) throw new IllegalStateException("Source manager failed to load source: " + source);
            if (loader.getSidedSources().size() != sidedSources.size()) {
                loader.getSidedSources().clear();
                loader.getSidedSources().putAll(sidedSources);
            }
            loader.setSourceManager(sources);
            return loader.load(source, output.get());
        }
    }

    static final class RetiredChange {
        private final Runnable detach;
        private final List<Runnable> notifications;
        private final List<Runnable> retirements;
        RetiredChange(Runnable detach, List<Runnable> notifications, List<Runnable> retirements) {
            this.detach = detach;
            this.notifications = notifications;
            this.retirements = retirements;
        }
        Throwable detach(Throwable failure) { return runAll(List.of(detach), failure); }
        Throwable notifyListeners(Throwable failure) { return runAll(notifications, failure); }
        Throwable retire(Throwable failure) { return runAll(retirements, failure); }
        private static Throwable runAll(List<Runnable> actions, Throwable failure) {
            for (Runnable action : actions) {
                try { action.run(); }
                catch (RuntimeException | Error problem) { failure = ModelPublicationCoordinator.combine(failure, problem); }
            }
            return failure;
        }
    }

    @SuppressWarnings("unchecked")
    RetiredChange prepareForPublication(Object rawKey, Model previous, Model next, long revision) {
        K key = (K) rawKey;
        var stale = modelInstances.get(key);
        var change = new ModelChange<>(key, previous, next, revision);
        List<Runnable> notifications = new ArrayList<>();
        for (var listener : List.copyOf(modelListeners)) notifications.add(() -> listener.accept(change));
        List<Runnable> retirements = new ArrayList<>();
        if (stale != null) for (var instance : stale.values()) retirements.add(() -> retireInstance(instance));
        return new RetiredChange(() -> modelInstances.remove(key), notifications, retirements);
    }

    @Nullable
    public ModelInstance createInstance(K modelName, I instanceIdentifier,
                                                @Nullable Transform transform,
                                                @Nullable ModelInstanceData instanceData,
                                                @Nullable SkeletonInstanceData skeletonInstanceData) {
        coordinator.checkThread();
        Model model = getModel(modelName);
        if (model == null) {return null;}
        ModelInstance current = getInstance(modelName, instanceIdentifier);
        if (current != null) return current;
        ModelInstance instance = new ModelInstance(model, transform, instanceData, skeletonInstanceData, new MaterialSetInstance(model.getMaterialSet()), null);
        modelInstances.computeIfAbsent(modelName, k -> new HashMap<>()).put(instanceIdentifier, instance);
        return instance;
    }

    /**
     * Create an instance and attach a {@link PoseDriver} in one step. Intended for non-FSM drivers that do not
     * depend on the instance at construction time. FSM drivers ({@code FsmPoseDriver}) have a chicken-and-egg
     * with the machine build (the machine needs the instance for its sink, the driver needs the machine), so
     * they use the two-step form: {@code createInstance(...)} then {@code ModelPoseBinding.install(instance, driver)}.
     *
     * @param driver the pose driver to attach, or {@code null} to leave the instance static
     */
    @Nullable
    public ModelInstance createInstance(K modelName, I instanceIdentifier,
                                                @Nullable Transform transform,
                                                @Nullable ModelInstanceData instanceData,
                                                @Nullable SkeletonInstanceData skeletonInstanceData,
                                                @Nullable PoseDriver driver) {
        ModelInstance instance = createInstance(modelName, instanceIdentifier, transform, instanceData, skeletonInstanceData);
        if (instance != null && driver != null) {
            ModelPoseBinding.install(instance, driver);
        }
        return instance;
    }

    @Nullable
    public ModelInstance getInstance(K modelName, I instanceIdentifier) {
        coordinator.checkThread();
        HashMap<I, ModelInstance> instances = modelInstances.get(modelName);
        if (instances == null) {return null;}
        return instances.get(instanceIdentifier);
    }

    public boolean hasModel(K modelName) {
        return getModel(modelName) != null;
    }

    public boolean hasInstance(K modelName, I instanceIdentifier) {
        coordinator.checkThread();
        HashMap<I, ModelInstance> instances = modelInstances.get(modelName);
        if (instances == null) {
            return false;
        }
        return instances.containsKey(instanceIdentifier);
    }

    /**
     * Removes an instance from every backend and from this pipeline's instance
     * registry, then closes its runtime state (animation and physics).
     */
    public boolean removeInstance(K modelName,
                                  I instanceIdentifier) {
        coordinator.checkThread();
        Model model = getModel(modelName);
        if (model == null) return false;
        HashMap<I, ModelInstance> instances = modelInstances.get(modelName);
        if (instances == null) return false;
        ModelInstance instance = instances.remove(instanceIdentifier);
        if (instance == null) return false;
        if (instances.isEmpty()) modelInstances.remove(modelName);
        retireInstance(instance);
        return true;
    }

    /** Release failures must not strand the instance in another backend or its runtime. */
    private void retireInstance(ModelInstance instance) {
        Throwable failure = null;
        for (var backend : backends.values()) {
            try { backend.remove(instance); }
            catch (RuntimeException | Error cleanup) { failure = ModelPublicationCoordinator.combine(failure, cleanup); }
        }
        try { lib.kasuga.rendering.models.uml.framework.schedule.ModelRenderScheduling.scheduler().detach(instance); }
        catch (RuntimeException | Error cleanup) { failure = ModelPublicationCoordinator.combine(failure, cleanup); }
        try { instance.close(); }
        catch (RuntimeException | Error cleanup) { failure = ModelPublicationCoordinator.combine(failure, cleanup); }
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    public void addToRenderer(
            K modelName,
            I instanceIdentifier,
            String bridgeName,
            String backendName) {
        coordinator.checkThread();
        Model model = getModel(modelName);
        if (model == null) {
            throw new IllegalArgumentException("No model found with name: " + modelName);
        }
        ModelInstance instance = getInstance(modelName, instanceIdentifier);
        if (instance == null) {
            throw new IllegalArgumentException("No instance found with identifier: " + instanceIdentifier);
        }
        if (!bridgeNames.contains(bridgeName)) {
            throw new IllegalArgumentException("No bridge found with name: " + bridgeName);
        }
        Renderer backend = backends.get(backendName);
        if (backend == null) {
            throw new IllegalArgumentException("No backend found with name: " + backendName);
        }
        backend.add(instance, bridgeName);
    }

    public boolean isRendering(
            K modelName,
            I instanceIdentifier,
            @Nullable String backendName) {
        coordinator.checkThread();
        Model model = getModel(modelName);
        if (model == null) {
            return false;
        }
        ModelInstance instance = getInstance(modelName, instanceIdentifier);
        if (instance == null) {
            return false;
        }
        if (backendName != null) {
            Renderer backend = backends.get(backendName);
            if (backend != null) {
                return backend.contains(instance);
            }
        }
        for (Renderer backend : backends.values()) {
            if (backend.contains(instance)) {
                return true;
            }
        }
        return false;
    }

    /**
     * MANUAL visibility switch: keeps GPU buffers and animation state mounted
     * while skipping per-frame sampling/drawing. Pairs with
     * {@code RenderScheduleMode.MANUAL}; instances in the default ALWAYS mode
     * are unaffected by this call.
     */
    public void setRendering(
            K modelName,
            I instanceIdentifier,
            boolean visible) {
        ModelInstance instance = getInstance(modelName, instanceIdentifier);
        if (instance != null) {
            lib.kasuga.rendering.models.uml.framework.schedule.ModelRenderScheduling.scheduler().setVisible(instance, visible);
        }
    }

    public boolean stopRendering(
            K modelName,
            I instanceIdentifier,
            @Nullable String backendName) {
        coordinator.checkThread();
        Model model = getModel(modelName);
        if (model == null) {
            return false;
        }
        ModelInstance instance = getInstance(modelName, instanceIdentifier);
        if (instance == null) {
            return false;
        }
        boolean removed = false;
        RuntimeException failure = null;
        if (backendName != null) {
            Renderer backend = backends.get(backendName);
            if (backend != null) {
                return backend.remove(instance);
            }
        }
        for (Renderer backend : backends.values()) {
            // Each backend must get its release attempt even if an earlier one fails.
            try { removed |= backend.remove(instance); }
            catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
        return removed;
    }

    /** Loader-free construction needs only keys and the backend renderable type. */
    public static final class PublishedBuilder<K, I, B> {
        private final Builder<Void, B, K, I, Void> builder = new Builder<>();
        public PublishedBuilder<K, I, B> withPublicationCoordinator(ModelPublicationCoordinator coordinator) {
            builder.withPublicationCoordinator(coordinator); return this;
        }
        public PublishedBuilder<K, I, B> withBridge(String name, Bridge<B> bridge) {
            builder.withBridge(name, bridge);
            return this;
        }
        public PublishedBuilder<K, I, B> withBackend(String name, Backend<? extends Bridge<B>, B, ?, ?> backend) {
            builder.withBackend(name, backend);
            return this;
        }
        public ModelPipeLine<K, I> build() { return builder.buildForPublishedModels(); }
    }

    public static class Builder<SourceOutputType,
        BackendInputType, StorageIdentifierType, InstanceIdentifierType, TextureIdentifierType> {

        private ModelPublicationCoordinator coordinator = ModelPublicationCoordinator.global();
        public Builder<SourceOutputType, BackendInputType, StorageIdentifierType, InstanceIdentifierType, TextureIdentifierType>
        withPublicationCoordinator(ModelPublicationCoordinator coordinator) {
            this.coordinator = Objects.requireNonNull(coordinator); return this;
        }

        private SourceManager<SourceOutputType> sourceManager;
        private final Map<SourceType, HashMap<String, SourceManager<?>>> sidedSources = new HashMap<>();
        private ModelLoader<SourceOutputType, StorageIdentifierType, TextureIdentifierType> loader;
        private final Map<String, Bridge<BackendInputType>> bridges = new HashMap<>();
        private final Map<String, Backend<Bridge<BackendInputType>, BackendInputType, ?, ?>> backends = new HashMap<>();

        public Builder<SourceOutputType, BackendInputType, StorageIdentifierType,
                InstanceIdentifierType, TextureIdentifierType> withModelSource(
                        SourceManager<SourceOutputType> modelSource) {
            this.sourceManager = modelSource;
            return this;
        }

        public Builder<SourceOutputType,
                BackendInputType, StorageIdentifierType,
                InstanceIdentifierType, TextureIdentifierType>
        withLoader(ModelLoader<SourceOutputType, StorageIdentifierType, TextureIdentifierType> loader) {
            this.loader = loader;
            return this;
        }

        public Builder<SourceOutputType,
                BackendInputType, StorageIdentifierType,
                InstanceIdentifierType, TextureIdentifierType> withSidedSource(SourceType side, String name, SourceManager<?> manager) {
            this.sidedSources.computeIfAbsent(side, k -> new HashMap<>()).put(name, manager);
            return this;
        }

        public Builder<SourceOutputType,
                BackendInputType, StorageIdentifierType,
                InstanceIdentifierType, TextureIdentifierType> withBridge(String name, Bridge bridge) {
            this.bridges.put(name, bridge);
            return this;
        }

        public Builder<SourceOutputType,
                BackendInputType, StorageIdentifierType,
                InstanceIdentifierType, TextureIdentifierType> withBackend(String name, Backend backend) {
            this.backends.put(name, (Backend<Bridge<BackendInputType>, BackendInputType, ?, ?>) backend);
            return this;
        }

        public ModelPipeLine<StorageIdentifierType, InstanceIdentifierType> build() {
            Objects.requireNonNull(sourceManager);
            Objects.requireNonNull(loader);
            return buildForPublishedModels();
        }

        /** Builds a render/instance pipeline without a format loader (e.g. derived assembled models). */
        public ModelPipeLine<StorageIdentifierType, InstanceIdentifierType> buildForPublishedModels() {
            if (bridges.isEmpty()) {
                throw new IllegalStateException("At least one bridge must be provided");
            }
            if (backends.isEmpty()) {
                throw new IllegalStateException("At least one backend must be provided");
            }
            bridges.forEach((name, bridge) -> bridge.setBackends((Map) backends));
            return new ModelPipeLine<>(sourceManager, loader, bridges, backends, sidedSources, coordinator);
        }
    }
}
