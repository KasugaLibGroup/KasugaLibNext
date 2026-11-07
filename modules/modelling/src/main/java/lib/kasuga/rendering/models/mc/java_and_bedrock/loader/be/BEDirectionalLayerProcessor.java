package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCMeshData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.CubeVerticesMapper;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.mc.util.ModelJson;
import lib.kasuga.utils.json.JsonFieldPolicy.DefaultOnMismatch;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Processor;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.util.HashMap;

import static lib.kasuga.rendering.models.mc.util.Direction.DOWN;
import static lib.kasuga.rendering.models.mc.util.Direction.UP;

public class BEDirectionalLayerProcessor extends Processor<JsonObject> {
    @Override
    public void process(JsonObject input, Context context) {
        Input definition = ModelJson.GSON.fromJson(input, Input.class);
        HashMap<JsonElement, Direction> directionMap = (HashMap<JsonElement, Direction>) context.getData("direction");
        Direction direction = directionMap.get(input);
        Material material = (Material) context.getData("material");
        CubeVerticesMapper mapper = (CubeVerticesMapper) context.getData("mapper");
        float texWidth = material.getTexture("root").getWidth();
        float texHeight = material.getTexture("root").getHeight();
        int uvRoataion = definition.uvRotation % 360;
        Vector2f uvOrg = new Vector2f(definition.uv)
                .mul(1 / texWidth, 1 / texHeight);
        Vector2f uvSize = new Vector2f(definition.uvSize)
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
    private static final class Input {
        @DefaultOnMismatch Integer uvRotation = 0;
        org.joml.Vector2f uv, uvSize;
    }
}
