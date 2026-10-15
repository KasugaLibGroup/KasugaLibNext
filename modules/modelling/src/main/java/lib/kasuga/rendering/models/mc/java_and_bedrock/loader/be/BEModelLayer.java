package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.util.JsonHelper;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Stack;

public class BEModelLayer extends Layer<JsonObject> {

    @Override
    public void process(JsonObject input, Context context) {
        context.setData("pivot_map", new HashMap<String, Vector3f>());
        String formatVersion = JsonHelper.jsonToString(input, "format_version", "");
        JsonObject textures = input.get("textures").getAsJsonObject();
        addChildProcess(textures, "texture_layer");
        JsonArray geometries;
        boolean legacy = false;
        if (input.has("minecraft:geometry")) {
            geometries = input.get("minecraft:geometry").getAsJsonArray();
        } else if (input.has("geometry.model")) {
            geometries = new JsonArray();
            geometries.add(input.get("geometry.model"));
            legacy = true;
        } else {
            throw new IllegalArgumentException("Expected 'minecraft:geometry' or 'geometry.model' property in model JSON");
        }
        context.setData("format_version", formatVersion);
        context.setData("legacy", legacy);
        for (JsonElement element : geometries) {
            JsonObject geometry = element.getAsJsonObject();
            addChildProcess(geometry, "geometry_layer");
        }
    }
}
