package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be;

import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.util.ModelJson;
import lib.kasuga.utils.json.JsonFieldPolicy.DefaultOnMismatch;
import com.google.gson.annotations.SerializedName;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import org.joml.Vector3f;

import java.util.HashMap;

public class BEModelLayer extends Layer<JsonObject> {

    @Override
    public void process(JsonObject input, Context context) {
        context.setData("pivot_map", new HashMap<String, Vector3f>());
        Input definition = ModelJson.GSON.fromJson(input, Input.class);
        addChildProcess(definition.textures, "texture_layer");
        java.util.List<JsonObject> geometries = definition.geometries;
        boolean legacy = geometries == null;
        if (legacy) {
            if (definition.legacyGeometry == null)
                throw new IllegalArgumentException("Expected 'minecraft:geometry' or 'geometry.model' property in model JSON");
            geometries = java.util.List.of(definition.legacyGeometry);
        }
        context.setData("format_version", definition.formatVersion);
        context.setData("legacy", legacy);
        for (JsonObject geometry : geometries) addChildProcess(geometry, "geometry_layer");
    }

    private static final class Input {
        @DefaultOnMismatch String formatVersion = "";
        JsonObject textures;
        @SerializedName("minecraft:geometry") java.util.List<JsonObject> geometries;
        @SerializedName("geometry.model") JsonObject legacyGeometry;
    }
}
