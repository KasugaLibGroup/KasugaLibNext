package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be;

import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.IdentifierHelper;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCMeshData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.TextureLayer;
import lib.kasuga.rendering.models.mc.source.texture.KasugaTextureManager;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.SkeletonBuilder;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.structural.Loader;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.VertexData;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.AnchorData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonData;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.BiFunction;

public class BEModelLoader extends Loader<JsonObject, ResourceLocation, String> {

    private final HashMap<ResourceLocation, Model> loadedModels;

    @Getter
    private final String nameSpace;

    public BEModelLoader(String name, String nameSpace) {
        super(name);
        this.nameSpace = nameSpace;
        this.loadedModels = new HashMap<>();
        registerProcessor("root", new BEModelLayer());
        registerProcessor("texture_layer", new TextureLayer());
        registerProcessor("geometry_layer", new BEGeoLayer());
        registerProcessor("bone_layer", new BEBoneLayer());
        registerProcessor("cube_layer", new BECubeLayer());
        registerProcessor("locator_layer", new BELocatorLayer());
        registerProcessor("box_layer", new BEBoxLayerProcessor());
        registerProcessor("directional_layer", new BEDirectionalLayerProcessor());
    }


    @Override
    public void build(HashMap<ResourceLocation, Model> resultMap) {
        if (resultMap != null) {
            resultMap.putAll(loadedModels);
            loadedModels.clear();
            return;
        }
        BiFunction<
                SkeletonBuilder.AnchorRecord,
                List<Bone>, BoneBinding> anchorBindingFunc = (anchorRec, bones) -> {
            List<Pair<Bone, Float>> parentBones = new ArrayList<>(bones.size());
            for (String parentBoneName : anchorRec.parentBoneNames()) {
                for (Bone bone : bones) {
                    if (bone.getName().equals(parentBoneName)) {
                        parentBones.add(Pair.of(bone, 1f / bones.size()));
                        break;
                    }
                }
            }
            return new BoneBinding(
                    parentBones.toArray(new Pair[0]), BoneBindingFunc.BDEF, null
            );
        };

        BiFunction<
                SkeletonBuilder.VertexRecord,
                List<Bone>,
                BoneBinding> vertexBindingFunc = (vertexRec, bones) -> {
            List<Pair<Bone, Float>> parentBones = new ArrayList<>(bones.size());
            for (String parentBoneName : vertexRec.parentBoneNames()) {
                for (Bone bone : bones) {
                    if (bone.getName().equals(parentBoneName)) {
                        parentBones.add(Pair.of(bone, 1f / bones.size()));
                        break;
                    }
                }
            }
            return new BoneBinding(
                    parentBones.toArray(new Pair[0]), BoneBindingFunc.BDEF, null
            );
        };

        Skeleton skeleton = getBones().build(
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
        this.loadedModels.put(resolveModelIdentifier(), model);
    }

    /**
     * 模型注册 key 必须是模型文件的 ResourceLocation（文件路径），
     * 与 PipelineBindingRegistry 中 RenderFeature.modelKey 的语义一致——
     * createInstance(modelKey) 按文件路径查找 models 存储。
     * 不能使用 JSON 内的 identifier（可能含中文/空格，tryBuild 返回 null，
     * 且与 modelKey 对不上）。
     */
    private ResourceLocation resolveModelIdentifier() {
        Object identifier = getContext().getData("identifier");
        if (!(identifier instanceof ResourceLocation rl)) {
            throw new IllegalStateException("identifier not set in context - model cannot be registered");
        }
        return rl;
    }

    @Override
    public HashMap<ResourceLocation, Model> load(ResourceLocation loc, JsonObject input) {
        getContext().setData("identifier", loc);
        return super.load(loc, input);
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof JsonObject;
    }

    @Override
    public Texture loadTexture(Object textureIdentifier) {
        return null;
    }
}
