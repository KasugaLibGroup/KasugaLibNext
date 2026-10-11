package lib.kasuga.rendering.models.mc.source.model;

import lib.kasuga.rendering.models.mc.api.pbr.PbrUserConfig;
import lib.kasuga.client.loading.LoadingIndicator;
import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.source.texture.KasugaTextureManager;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.dynamic.ModelPublicationBatch;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.mc.source.model.assembly.AssemblyResourceDefinitions;
import lib.kasuga.rendering.models.mc.api.McModelAssemblies;
import lib.kasuga.rendering.models.uml.loaders.assembly.ModelAssemblyDefinition;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lombok.NonNull;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class KasugaModelManager implements PreparableReloadListener, AutoCloseable {

    private final Collection<KasugaTextureManager> textureManagers;
    private final Collection<Object> inputIdentifiers;

    private ModelScanner modelScanner;
    private PipeLineRouter pipeLineRouter;
    private final ModelPublicationBatch<Object> publications = new ModelPublicationBatch<>();

    public KasugaModelManager(Collection<KasugaTextureManager> textureManagers) {
        this.textureManagers = textureManagers;
        this.inputIdentifiers = new ArrayList<>();

        this.modelScanner = new KasugaModelScanner();
        this.pipeLineRouter = new KasugaPipeLineRouter();
    }

    public KasugaModelManager(SourceType type,
                              String name,
                              Collection<KasugaTextureManager> textureManagers,
                              ModelScanner modelScanner,
                              PipeLineRouter pipeLineRouter) {
        this.inputIdentifiers = new ArrayList<>();
        this.textureManagers = textureManagers;
        this.modelScanner = modelScanner;
        this.pipeLineRouter = pipeLineRouter;
    }

    public void registerModel(Object inputIdentifier) {
        this.inputIdentifiers.add(inputIdentifier);
    }

    public void registerRouter(PipeLineRouter router) {
        this.pipeLineRouter = router;
    }

    public void registerModelScanner(ModelScanner modelScanner) {
        this.modelScanner = modelScanner;
    }

    @Override
    public void close() throws Exception {}

    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier,
                                          @NonNull ResourceManager resourceManager,
                                          @NonNull ProfilerFiller preparationProfiler,
                                          @NonNull ProfilerFiller reloadProfiler,
                                          @NonNull Executor backgroundExecutor,
                                          @NonNull Executor gameExecutor) {
        Map<ModelPipeLine, Map<Object, lib.kasuga.rendering.models.uml.structure.Model>> preparedModels =
                new IdentityHashMap<>();
        Map<ResourceLocation, ModelAssemblyDefinition<McModelAssemblies.Reference>> preparedAssemblies = new HashMap<>();
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            LoadingIndicator.begin("Scanning model resources", 1);
            PbrUserConfig.reload(Minecraft.getInstance().gameDirectory.toPath().resolve("config"));

            modelScanner.setConfig(ModelProxyConfigLoader.loadConfig(resourceManager));
            List<ResourceLocation> scanned = modelScanner.scan(resourceManager);
            preparedAssemblies.putAll(AssemblyResourceDefinitions.load(resourceManager));

            Map<ModelPipeLine, List<ResourceLocation>> routed = pipeLineRouter.route(scanned);
            int modelCount = routed.values().stream().mapToInt(List::size).sum();
            int totalSteps = modelCount + 2;
            int completed = 1;

            for(Map.Entry<ModelPipeLine, List<ResourceLocation>> entry : routed.entrySet()) {
                for(ResourceLocation location : entry.getValue()) {
                    LoadingIndicator.update("Loading model " + location, completed, totalSteps);
                    Map<Object, lib.kasuga.rendering.models.uml.structure.Model> loaded =
                            entry.getKey().prepareModel(location, null);
                    preparedModels.computeIfAbsent(entry.getKey(), ignored -> new HashMap<>()).putAll(loaded);
                    completed++;
                    LoadingIndicator.update("Loaded model " + location, completed, totalSteps);
                }
            }
            LoadingIndicator.update("Preparing PBR textures and atlases", totalSteps - 1, totalSteps);
        }, backgroundExecutor);
        CompletableFuture<Void> allTexturesFuture = future.thenCompose(ignored -> {
            List<CompletableFuture<Void>> textureFutures = textureManagers.stream()
                    .map(manager -> manager.reload(barrier, resourceManager, preparationProfiler, reloadProfiler, backgroundExecutor, gameExecutor))
                    .toList();
            return CompletableFuture.allOf(textureFutures.toArray(new CompletableFuture[0]));
        });
        return allTexturesFuture.thenRunAsync(() -> {
            LoadingIndicator.label("Publishing models");
            RuntimeException failure = null;
            var assemblies = PipelineRegistry.assemblies();
            if (assemblies != null) {
                try { assemblies.replaceResourceDefinitions(preparedAssemblies); }
                catch (RuntimeException cleanup) { failure = cleanup; }
            }
            try { publications.publish((Map) preparedModels); }
            catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
            if (failure != null) throw failure;
        }, gameExecutor).whenComplete((ignored, throwable) -> LoadingIndicator.complete());
    }

    private void loadAllModels(ModelPipeLine pipe) {
        for (Object inputIdentifier : this.inputIdentifiers) {
            pipe.loadModel(inputIdentifier, null);
        }
    }
}
