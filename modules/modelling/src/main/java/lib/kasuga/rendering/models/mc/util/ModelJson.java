package lib.kasuga.rendering.models.mc.util;

import com.google.gson.*;
import lib.kasuga.utils.json.GsonBindings;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Gson bindings shared by model formats; coordinate and unit conversions belong to each loader. */
public final class ModelJson {
    public static final Gson GSON = GsonBindings.builder()
            .registerTypeAdapter(Vector2f.class, (JsonDeserializer<Vector2f>) (json, type, context) -> {
                float[] values = coordinates(json, 2);
                return new Vector2f(values[0], values[1]);
            })
            .registerTypeAdapter(Vector3f.class, (JsonDeserializer<Vector3f>) (json, type, context) ->
                    new Vector3f(coordinates(json, 3)))
            .registerTypeAdapter(Vector4f.class, (JsonDeserializer<Vector4f>) (json, type, context) -> {
                float[] values = coordinates(json, 4);
                return new Vector4f(values[0], values[1], values[2], values[3]);
            })
            .registerTypeAdapter(Scale.class, (JsonDeserializer<Scale>) (json, type, context) -> {
                Vector3f value;
                if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
                    value = new Vector3f(context.<Float>deserialize(json, Float.class));
                } else if (json.isJsonArray()) {
                    value = new Vector3f(coordinates(json, 3));
                } else throw new JsonParseException("model_scale must be a number or three-number array");
                return new Scale(value);
            })
            .create();

    private ModelJson() {}

    private static float[] coordinates(JsonElement json, int size) {
        float[] values;
        if (json.isJsonArray()) values = GsonBindings.GSON.fromJson(json, float[].class);
        else if (json.isJsonObject()) {
            Coordinates point = GsonBindings.GSON.fromJson(json, Coordinates.class);
            Float[] components = {point.x, point.y, point.z, point.w};
            values = new float[size];
            for (int i = 0; i < size; i++) {
                if (components[i] == null) throw new JsonParseException("Missing vector coordinate " + "xyzw".charAt(i));
                values[i] = components[i];
            }
        } else throw new JsonParseException("Expected vector array or object");
        if (values.length != size) throw new JsonParseException("Expected " + size + " vector components, got " + values.length);
        return values;
    }

    private record Coordinates(Float x, Float y, Float z, Float w) {}

    public record Scale(Vector3f value) {
        public Scale {
            value = new Vector3f(value);
            if (!value.isFinite() || value.x <= 0f || value.y <= 0f || value.z <= 0f)
                throw new JsonParseException("model_scale components must be finite and positive");
        }
        @Override public Vector3f value() { return new Vector3f(value); }
    }
}
