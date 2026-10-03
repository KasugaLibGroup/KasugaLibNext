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

public class ModelPipeLine<SourceOutputType, BackendInputType, StorageIdentifierType, InstanceIdentifierType, TextureIdentifierType> {

    private final SourceManager<SourceOutputType> sourceManager;

    private final Map<SourceType, HashMap<String, SourceManager<?>>> sidedSources;

    private final ModelLoader<SourceOutputType, StorageIdentifierType, TextureIdentifierType> loader;

    private final Map<String, Bridge<BackendInputType>> bridges;

    private final Map<StorageIdentifierType, Model> models;

    private final Map<String, Backend<Bridge<BackendInputType>, BackendInputType, ?, ?>> backends;

    private final Map<Model,
            HashMap<InstanceIdentifierType, ModelInstance>> modelInstances;

    private ModelPipeLine(SourceManager<SourceOutputType> sourceManager,
                          ModelLoader<SourceOutputType, StorageIdentifierType, TextureIdentifierType> loader,
                          Map<String, Bridge<BackendInputType>> bridges,
                          Map<String, Backend<Bridge<BackendInputType>, BackendInputType, ?, ?>> backends,
                          Map<SourceType, HashMap<String, SourceManager<?>>> sidedSources
    ) {
        this.sourceManager = sourceManager;
        this.loader = loader;
        this.bridges = bridges;
        this.backends = backends;
        this.models = new HashMap<>();
        this.modelInstances = new HashMap<>();
        this.sidedSources = sidedSources;
    }

    public Map<StorageIdentifierType, Model> loadModel(Object source, @Nullable String sourceLoaderName) {
        Map<StorageIdentifierType, Model> loaded = prepareModel(source, sourceLoaderName);
        publishModels(loaded);
        return loaded;
    }

    /** Loads and builds models without making them visible to render callers. */
    public Map<StorageIdentifierType, Model> prepareModel(Object source, @Nullable String sourceLoaderName) {
        Source<?, SourceOutputType> manager = null;
        if (sourceLoaderName != null) {
            manager = sourceManager.getSource(sourceLoaderName);
        }
        if (manager == null) {
            for (Source<?, SourceOutputType> m : sourceManager.getSources().values()) {
                if (m.isValidInput(source)) {
                    manager = m;
                    break;
                }
            }
        }
        if (manager == null) {
            throw new IllegalArgumentException("No source manager found for source: " + source);
        }
        Optional<SourceOutputType> sourceOutput = ((Source<Object, SourceOutputType>) manager).getInput(source);
        if (sourceOutput.isEmpty()) {
            throw new IllegalStateException("Source manager failed to load source: " + source);
        }
        if (loader.getSidedSources().size() != sidedSources.size()) {
            loader.getSidedSources().clear();
            loader.getSidedSources().putAll(this.sidedSources);
        }
        loader.setSourceManager(this.sourceManager);
        Map<StorageIdentifierType, Model> map =
                loader.load((StorageIdentifierType) source, sourceOutput.get());
        return map;
    }

    /**
     * Publishes a prepared model set and removes instances backed by replaced
     * models. Call this on the game thread after dependent textures are ready.
     */
    public void publishModels(Map<StorageIdentifierType, Model> prepared) {
        RuntimeException failure = null;
        for (Map.Entry<StorageIdentifierType, Model> entry : prepared.entrySet()) {
            Model previous = models.put(entry.getKey(), entry.getValue());
            if (previous == null || previous == entry.getValue()) continue;
            HashMap<InstanceIdentifierType, ModelInstance> staleInstances = modelInstances.remove(previous);
            if (staleInstances == null) continue;
            for (ModelInstance instance : staleInstances.values()) {
                try { retireInstance(instance); }
                catch (RuntimeException cleanup) {
                    if (failure == null) failure = cleanup;
                    else failure.addSuppressed(cleanup);
                }
            }
            staleInstances.clear();
        }
        if (failure != null) throw failure;
    }

    @Nullable
    public ModelInstance createInstance(StorageIdentifierType modelName, InstanceIdentifierType instanceIdentifier,
                                                @Nullable Transform transform,
                                                @Nullable ModelInstanceData instanceData,
                                                @Nullable SkeletonInstanceData skeletonInstanceData) {
        Model model = models.get(modelName);
        if (model == null) {return null;}
        ModelInstance instance = new ModelInstance(model, transform, instanceData, skeletonInstanceData, new MaterialSetInstance(model.getMaterialSet()), null);
        modelInstances.computeIfAbsent(model, k -> new HashMap<>()).put(instanceIdentifier, instance);
        return instance;
    }

    /**
     * Create an instance and attach a {@link PoseDriver} in one step. Intended for non-FSM drivers that do not
     * depend on the instance at construction time. FSM drivers ({@code FsmPoseDriver}) have a chicken-and-egg
     * with the machine build (the machine needs the instance for its sink, the driver needs the machine), so
     * they use the two-step form: {@code createInstance(...)} then {@code instance.setPoseDriver(driver)}.
     *
     * @param driver the pose driver to attach, or {@code null} to leave the instance static
     */
    @Nullable
    public ModelInstance createInstance(StorageIdentifierType modelName, InstanceIdentifierType instanceIdentifier,
                                                @Nullable Transform transform,
                                                @Nullable ModelInstanceData instanceData,
                                                @Nullable SkeletonInstanceData skeletonInstanceData,
                                                @Nullable PoseDriver driver) {
        ModelInstance instance = createInstance(modelName, instanceIdentifier, transform, instanceData, skeletonInstanceData);
        if (instance != null && driver != null) {
            instance.setPoseDriver(driver);
        }
        return instance;
    }

    @Nullable
    public ModelInstance getInstance(StorageIdentifierType modelName, InstanceIdentifierType instanceIdentifier) {
        HashMap<InstanceIdentifierType, ModelInstance> instances = modelInstances.get(models.get(modelName));
        if (instances == null) {return null;}
        return instances.get(instanceIdentifier);
    }

    public boolean hasModel(StorageIdentifierType modelName) {
        return models.containsKey(modelName);
    }

    public boolean hasInstance(StorageIdentifierType modelName, InstanceIdentifierType instanceIdentifier) {
        HashMap<InstanceIdentifierType, ModelInstance> instances = modelInstances.get(models.get(modelName));
        if (instances == null) {
            return false;
        }
        return instances.containsKey(instanceIdentifier);
    }

    /**
     * Removes an instance from every backend and from this pipeline's instance
     * registry, then closes its runtime state (animation and physics).
     */
    public boolean removeInstance(StorageIdentifierType modelName,
                                  InstanceIdentifierType instanceIdentifier) {
        Model model = models.get(modelName);
        if (model == null) return false;
        HashMap<InstanceIdentifierType, ModelInstance> instances = modelInstances.get(model);
        if (instances == null) return false;
        ModelInstance instance = instances.remove(instanceIdentifier);
        if (instance == null) return false;
        if (instances.isEmpty()) modelInstances.remove(model);
        retireInstance(instance);
        return true;
    }

    /** Release failures must not strand the instance in another backend or its runtime. */
    private void retireInstance(ModelInstance instance) {
        RuntimeException failure = null;
        for (var backend : backends.values()) {
            try { backend.remove(instance); }
            catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        lib.kasuga.rendering.models.uml.framework.schedule.ModelRenderScheduling.scheduler().detach(instance);
        try { instance.close(); }
        catch (RuntimeException cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        if (failure != null) throw failure;
    }

    public void addToRenderer(
            StorageIdentifierType modelName,
            InstanceIdentifierType instanceIdentifier,
            String bridgeName,
            String backendName) {
        Model model = models.get(modelName);
        if (model == null) {
            throw new IllegalArgumentException("No model found with name: " + modelName);
        }
        ModelInstance instance = getInstance(modelName, instanceIdentifier);
        if (instance == null) {
            throw new IllegalArgumentException("No instance found with identifier: " + instanceIdentifier);
        }
        Bridge<BackendInputType> bridge = bridges.get(bridgeName);
        if (bridge == null) {
            throw new IllegalArgumentException("No bridge found with name: " + bridgeName);
        }
        Backend<Bridge<BackendInputType>, BackendInputType, ?, ?> backend = backends.get(backendName);
        if (backend == null) {
            throw new IllegalArgumentException("No backend found with name: " + backendName);
        }
        backend.add(instance, bridge, instance);
    }

    public boolean isRendering(
            StorageIdentifierType modelName,
            InstanceIdentifierType instanceIdentifier,
            @Nullable String backendName) {
        Model model = models.get(modelName);
        if (model == null) {
            return false;
        }
        ModelInstance instance = getInstance(modelName, instanceIdentifier);
        if (instance == null) {
            return false;
        }
        if (backendName != null) {
            Backend<Bridge<BackendInputType>, BackendInputType, ?, ?> backend = backends.get(backendName);
            if (backend != null) {
                return backend.contains(instance);
            }
        }
        for (Backend<Bridge<BackendInputType>, BackendInputType, ?, ?> backend : backends.values()) {
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
            StorageIdentifierType modelName,
            InstanceIdentifierType instanceIdentifier,
            boolean visible) {
        ModelInstance instance = getInstance(modelName, instanceIdentifier);
        if (instance != null) {
            lib.kasuga.rendering.models.uml.framework.schedule.ModelRenderScheduling.scheduler().setVisible(instance, visible);
        }
    }

    public boolean stopRendering(
            StorageIdentifierType modelName,
            InstanceIdentifierType instanceIdentifier,
            @Nullable String backendName) {
        Model model = models.get(modelName);
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
            Backend<Bridge<BackendInputType>, BackendInputType, ?, ?> backend = backends.get(backendName);
            if (backend != null) {
                return backend.remove(instance);
            }
        }
        for (Backend<Bridge<BackendInputType>, BackendInputType, ?, ?> backend : backends.values()) {
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

    public static class Builder<SourceOutputType,
        BackendInputType, StorageIdentifierType, InstanceIdentifierType, TextureIdentifierType> {

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

        public ModelPipeLine<SourceOutputType,
                BackendInputType, StorageIdentifierType,
                InstanceIdentifierType, TextureIdentifierType> build() {
            Objects.requireNonNull(sourceManager);
            Objects.requireNonNull(loader);
            if (bridges.isEmpty()) {
                throw new IllegalStateException("At least one bridge must be provided");
            }
            if (backends.isEmpty()) {
                throw new IllegalStateException("At least one backend must be provided");
            }
            bridges.forEach((name, bridge) -> bridge.setBackends((Map) backends));
            return new ModelPipeLine<>(sourceManager, loader, bridges, backends, sidedSources);
        }
    }
}
