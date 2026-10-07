package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.mc.typo.KsgPmxLoader;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.mc.api.McModelAssemblies;
import lib.kasuga.client.loading.LoadingIndicator;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side rendering façade over the global model pipelines ({@code .mmd.zip} → MMD,
 * {@code .glb/.gltf} → glTF, {@code .obj} → OBJ, {@code .geo.json} → Bedrock,
 * {@code .json} → Java Edition). Instances are
 * attached to the global {@code mc_backend} and cleaned up on resource reload ({@code publishModels}).
 *
 * <p>Server-safe: methods are no-ops when the client pipelines are not initialized; {@code unbind}
 * is null-safe. Publication is lazy — {@link #createAndBind} returns {@code null} when the model
 * resource is not ready yet, and the caller retries next tick.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class KasugaModelPipelines {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String BRIDGE = "mc_bridge";
    private static final String BACKEND = "mc_backend";
    // warn-once keys (createAndBind/isRendering/unbind call route() every tick) so a bad model path logs once, not 20×/s
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();

    private KasugaModelPipelines() {}

    /** Resource lookup shared by normal model handles and assembled models. */
    @Nullable public static McModelAssemblies.PublishedSource publishedSource(McModelAssemblies.Reference reference) {
        if (LoadingIndicator.snapshot().active()) return null;
        var pipeline = route(reference.model());
        if (pipeline == null) return null;
        var key = resolveLoc(reference.model(), reference.modelName(), pipeline);
        return key == null ? null : new McModelAssemblies.PublishedSource(pipeline, key);
    }

    /**
     * Create (or reuse) the model instance and bind it to the render backend.
     *
     * @param modelLoc     model resource location, routed by file extension
     * @param instanceLoc  per-owner instance identifier (must be unique per host)
     * @param modelName    internal model name (MMD only; unused by other pipelines)
     * @param rootTransform root transform placing the model in the world (e.g. block position)
     * @return the bound instance, or {@code null} when the model is not published yet / MMD entry
     *         not resolvable (lazy retry)
     */
    @Nullable
    public static ModelInstance createAndBind(ResourceLocation modelLoc, ResourceLocation instanceLoc,
                                              @Nullable String modelName, Transform rootTransform) {
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline = route(modelLoc);
        if (pipeline == null) {
            return null;
        }
        ResourceLocation loc = resolveLoc(modelLoc, modelName, pipeline);
        if (loc == null) {
            return null;
        }
        ModelInstance existing = pipeline.getInstance(loc, instanceLoc);
        if (existing != null) {
            // Instance survived but was detached (external stopRendering): re-mount, no rebuild needed.
            if (!pipeline.isRendering(loc, instanceLoc, BACKEND)) {
                pipeline.addToRenderer(loc, instanceLoc, BRIDGE, BACKEND);
                LOGGER.info("[KasugaModelPipelines] re-added '{}' instance '{}' to mc_backend", loc, instanceLoc);
            }
            return existing;
        }
        ModelInstance instance = pipeline.createInstance(loc, instanceLoc, rootTransform, null, null);
        if (instance == null) {
            return null; // model not published yet (resource loading); retry lazily
        }
        pipeline.addToRenderer(loc, instanceLoc, BRIDGE, BACKEND);
        LOGGER.info("[KasugaModelPipelines] bound '{}' instance '{}' to mc_backend", loc, instanceLoc);
        return instance;
    }

    /** Whether the instance is currently attached to the backend (false on server / not bound). */
    public static boolean isRendering(ResourceLocation modelLoc, @Nullable String modelName, ResourceLocation instanceLoc) {
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline = route(modelLoc);
        if (pipeline == null) {
            return false;
        }
        ResourceLocation loc = resolveLoc(modelLoc, modelName, pipeline);
        return loc != null && pipeline.isRendering(loc, instanceLoc, BACKEND);
    }

    /** Detach the instance from the backend; null-safe for unbound instances, returns whether anything was removed. */
    public static boolean unbind(ResourceLocation modelLoc, @Nullable String modelName, ResourceLocation instanceLoc) {
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline = route(modelLoc);
        if (pipeline == null) {
            return false;
        }
        ResourceLocation loc = resolveLoc(modelLoc, modelName, pipeline);
        if (loc == null) {
            return false;
        }
        boolean removed = pipeline.stopRendering(loc, instanceLoc, BACKEND);
        if (removed) {
            LOGGER.info("[KasugaModelPipelines] unbound '{}' instance '{}' from mc_backend", loc, instanceLoc);
        }
        return removed;
    }

    //region routing

    @Nullable
    private static ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> route(@Nullable ResourceLocation modelLoc) {
        if (modelLoc == null || !PipelineRegistry.isInitialized()) {
            return null;
        }
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline = PipelineRegistry.resolve(modelLoc);
        if (pipeline == null && warned.add("route:" + modelLoc)) {
            LOGGER.warn("[KasugaModelPipelines] no pipeline for model '{}'", modelLoc);
        }
        return pipeline;
    }

    /** MMD models resolve their internal entry via {@code PipelineRegistry.pmxLoader()}; other pipelines use the location directly. */
    @Nullable
    private static ResourceLocation resolveLoc(ResourceLocation modelLoc, @Nullable String modelName,
                                               ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline) {
        if (pipeline != PipelineRegistry.pmx()) {
            return modelLoc;
        }
        KsgPmxLoader pmxLoader = PipelineRegistry.pmxLoader();
        if (pmxLoader == null) {
            return null;
        }
        if (modelName == null) {
            if (warned.add("modelname:" + modelLoc)) {
                LOGGER.warn("[KasugaModelPipelines] MMD model '{}' requires a model_name", modelLoc);
            }
            return null;
        }
        return pmxLoader.getLocByFileAndName(modelLoc, modelName);
    }

    //endregion
}
