package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.be.BEModelData;
import lib.kasuga.rendering.models.mc.util.JsonHelper;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.SpriteSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.util.TransformStack;
import lib.kasuga.structure.Pair;
import net.minecraft.client.resources.model.Material;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Stack;

public class BEGeoLayer extends Layer<JsonObject> {

    @Override
    public void process(JsonObject input, Context context) {
        JsonObject data = input.getAsJsonObject("description");
        String identifier = JsonHelper.jsonToString(data, "identifier", "");
        float textureWidth = JsonHelper.jsonToFloat(data, "texture_width", 16);
        float textureHeight = JsonHelper.jsonToFloat(data, "texture_height", 16);
        float visibleBoundsWidth = JsonHelper.jsonToFloat(data, "visible_bounds_width", 3);
        float visibleBoundsHeight = JsonHelper.jsonToFloat(data, "visible_bounds_height", 3);
        Vector3f visibleBoundsOffset = JsonHelper.jsonToV3f(data.get("visible_bounds_offset"));
        String formatVersion = (String) context.getData("format_version");
        boolean legacy = (Boolean) context.getData("legacy");

        HashMap<String, Pair<Material, MCTextureData>> texturesMap =
                (HashMap<String, Pair<Material, MCTextureData>>) context.getData("textures");
        Pair<Material, MCTextureData> texture = texturesMap.get("root");
        MCTexture rootTexture = new MCTexture(
                "root", texture::getFirst,
                textureWidth, textureHeight,
                texture.getSecond());
        MaterialSetBuilder builder = context.getLoader().getMaterialSetBuilder();
        builder.registerTexture("root", rootTexture);
        builder.beginMaterial()
                .useTexture("root")
                .addSpriteBuildingFunc((mtlb, sprb, mtl) -> {
                    SpriteSetBuilder sprBuilder = (SpriteSetBuilder) sprb;
                    sprBuilder.textureId("root")
                            .rectangularUVs(new Vector2f(), new Vector2f(1f, 1f))
                            .endSprite();
                })
                .endMaterial();
        lib.kasuga.rendering.models.uml.structure.material.Material mat =
                (lib.kasuga.rendering.models.uml.structure.material.Material) builder.getMaterials().getFirst();
        context.setData("material", mat);

        BEModelData modelData = new BEModelData(
                identifier, formatVersion,
                textureWidth, textureHeight,
                visibleBoundsWidth, visibleBoundsHeight,
                visibleBoundsOffset, legacy);

        context.setData("transform_map", new HashMap<String, Pair<Transform, Transform>>());

        JsonArray bonesJson = input.getAsJsonArray("bones");
        for (JsonElement bone : bonesJson) {
            addChildProcess(bone.getAsJsonObject(), "bone_layer");
        }
        context.getLoader().setData(modelData);
    }

    @Override
    public void postProcess(JsonObject input, Context context) {
        context.getLoader().build(null);
        HashMap<String, Vector3f> map = (HashMap<String, Vector3f>) context.getData("pivot_map");
        map.clear();
        HashMap<String, Pair<Transform, Transform>> transformMap = (HashMap<String, Pair<Transform, Transform>>) context.getData("transform_map");
        transformMap.clear();
    }
}
