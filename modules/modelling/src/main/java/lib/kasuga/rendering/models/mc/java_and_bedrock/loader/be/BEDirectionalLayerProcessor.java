package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCMeshData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.CubeVerticesMapper;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.mc.util.JsonHelper;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Processor;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.util.HashMap;

import static lib.kasuga.rendering.models.mc.util.Direction.DOWN;
import static lib.kasuga.rendering.models.mc.util.Direction.UP;

public class BEDirectionalLayerProcessor extends Processor<JsonObject> {
    @Override
    public void process(JsonObject input, Context context) {
        HashMap<JsonElement, Direction> directionMap = (HashMap<JsonElement, Direction>) context.getData("direction");
        Direction direction = directionMap.get(input);
        Material material = (Material) context.getData("material");
        CubeVerticesMapper mapper = (CubeVerticesMapper) context.getData("mapper");
        float texWidth = material.getTexture("root").getWidth();
        float texHeight = material.getTexture("root").getHeight();
        int uvRoataion = JsonHelper.jsonToInt(input, "uv_rotation", 0) % 360;
        Vector2f uvOrg = JsonHelper.jsonToV2f(input.getAsJsonArray("uv"))
                .mul(1 / texWidth, 1 / texHeight);
        Vector2f uvSize = JsonHelper.jsonToV2f(input.getAsJsonArray("uv_size"))
                .mul(1 / texWidth, 1 / texHeight);
        MCMeshData data = new MCMeshData(
                (Boolean) context.getDataOrDefault("visible", true),
                (Boolean) context.getDataOrDefault("emissive", false),
                true, false, direction, new Vector4f(1.0f, 1.0f, 1.0f, 1.0f)
        );
        boolean isUpAndDown = direction == UP || direction == DOWN;
        float defaultRotation = isUpAndDown ? 270 : 90;
        mapper.map(uvOrg, uvSize, material, direction, (uvRoataion + defaultRotation) % 360,
                null, data, true, false);
    }
}
