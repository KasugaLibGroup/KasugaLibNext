package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.je;

import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.je.JEModelData;
import lib.kasuga.rendering.models.mc.util.JsonHelper;
import lib.kasuga.rendering.models.uml.loaders.SkeletonBuilder;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.structural.Loader;
import lib.kasuga.rendering.models.uml.loaders.structural.Processor;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;

public class JEModelLoader extends Loader<JsonObject, ResourceLocation, String> {

    private final Set<ResourceLocation> loadingStack = new HashSet<>();
    private final Set<ResourceLocation> loaded = new HashSet<>();
    private final Map<ResourceLocation, JEModelData> mergedDataCache = new HashMap<>();
    private final Map<ResourceLocation, HashMap<ResourceLocation, Model>> modelCache = new HashMap<>();
    private SourceManager<JsonObject> modelSourceManager;
    private static final Logger LOGGER = LoggerFactory.getLogger("JEPipeline");

    public void setModelSourceManager(SourceManager<JsonObject> manager) {
        this.modelSourceManager = manager;
    }

    @Override
    public void setSourceManager(SourceManager<?> sourceManager) {
        @SuppressWarnings("unchecked")
        SourceManager<JsonObject> casted = (SourceManager<JsonObject>) sourceManager;
        this.modelSourceManager = casted;
    }

    public JEModelLoader(String name) {
        super(name);
        registerProcessor("root", new JEModelLayer());
        registerProcessor("texture_layer", new JETextureLayer());
        registerProcessor("element_layer", new JEElementLayer());
        registerProcessor("directional_layer", new JEDirectionalLayerProcessor());
    }

    @Override
    public void build(HashMap<ResourceLocation, Model> resultMap) {
        SkeletonBuilder skeletonBuilder = getBones();
        
        skeletonBuilder.addBone("root", new Transform(), null, null);

        BiFunction<
                SkeletonBuilder.AnchorRecord,
                List<Bone>,
                BoneBinding
        > anchorBindingFunc = (anchorRec, bones) -> {
            List<Pair<Bone, Float>> parentBones = new ArrayList<>();
            return new BoneBinding(
                    parentBones.toArray(new Pair[0]),
                    null,
                    null
            );
        };

        BiFunction<
                SkeletonBuilder.VertexRecord,
                List<Bone>,
                BoneBinding
        > vertexBindingFunc = (vertexRec, bones) -> {
            List<Pair<Bone, Float>> parentBones = new ArrayList<>();
            return new BoneBinding(
                    parentBones.toArray(new Pair[0]),
                    null,
                    null
            );
        };

        Skeleton skeleton = skeletonBuilder.build(
                null,
                null,
                anchorBindingFunc,
                vertexBindingFunc
        );

        Model model = new Model(
                getVertices().toArray(new Vertex[0]),
                getMeshes().toArray(new Mesh[0]),
                skeleton.getBones(),
                skeleton,
                materialSetBuilder().endMaterialSet(),
                MeshMode.QUADS,
                getData(),
                null
        );

        ResourceLocation identifier = (ResourceLocation) getContext().getData("identifier");
        if (identifier == null) {
            throw new IllegalStateException("identifier not set in context - model cannot be registered");
        }
        resultMap.put(identifier, model);
    }

    @Override
    public HashMap<ResourceLocation, Model> load(ResourceLocation source, JsonObject input) {

        if (loaded.contains(source)) {
            HashMap<ResourceLocation, Model> cached = modelCache.get(source);
            return cached != null ? new HashMap<>(cached) : new HashMap<>();
        }

        if (loadingStack.contains(source)) {
            LOGGER.error("[JELoader] CIRCULAR parent dependency detected: {}", source);
            throw new IllegalStateException("Circular parent dependency detected: " + source);
        }
        loadingStack.add(source);
        JEModelData modelData = JEModelData.fromJson(input);
        modelData.setIdentifier(source);

        HashMap<ResourceLocation, Model> resultMap = new HashMap<>();

        String parentPath = JsonHelper.jsonToString(input, "parent", null);
        if (parentPath != null) {
            ResourceLocation parentLoc = ResourceLocation.parse(parentPath);

            if (modelSourceManager == null) {
                throw new IllegalStateException("modelSourceManager not set - parent loading is unavailable");
            }
            Optional<JsonObject> parentJsonOpt;
            try {
                parentJsonOpt = this.modelSourceManager.load(parentLoc);
            } catch (Exception e) {
                LOGGER.error("[JELoader] failed to load parent resource: {}", parentLoc, e);
                throw new IllegalStateException("Failed to load parent resource: " + parentLoc, e);
            }
            if (parentJsonOpt.isEmpty()) {
                LOGGER.error("[JELoader] parent resource NOT FOUND: {}", parentLoc);
                throw new IllegalStateException("Parent resource not found: " + parentLoc);
            }
            JsonObject parentJson = parentJsonOpt.get();

            HashMap<ResourceLocation, Model> parentResult = this.load(parentLoc, parentJson);
            resultMap.putAll(parentResult);

            JEModelData parentData = mergedDataCache.get(parentLoc);
            if (parentData != null) {
                modelData.setParent(parentData);
                modelData.mergeFromParent();
            } else {
                LOGGER.warn("[JELoader] parentData NOT FOUND in mergedDataCache for {}", parentLoc);
            }
        }

        mergedDataCache.put(source, modelData);

        getContext().setData("modelData", modelData);
        getContext().setData("identifier", source);

        Processor<JsonObject> processor = (Processor<JsonObject>) this.getProcessors().get("root");
        if (processor == null) {
            throw new IllegalStateException("No root processor registered");
        }
        processor.walk(input, this.getContext());
        
        build(resultMap);
        modelCache.put(source, new HashMap<>(resultMap));
        clear();

        loaded.add(source);
        loadingStack.remove(source);
        return resultMap;
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof JsonObject;
    }

    @Override
    public Texture loadTexture(Object textureIdentifier) {
        return null;
    }

    @Override
    public void clear() {
        super.clear();
    }
}
