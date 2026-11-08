package lib.kasuga.rendering.models.mc.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class JsonHelper {

    public static Vector2f jsonToV2f(JsonElement element) {
        return java.util.Objects.requireNonNull(ModelJson.GSON.fromJson(element, Vector2f.class), "vector");
    }

    public static Vector3f jsonToV3f(JsonElement element) {
        return java.util.Objects.requireNonNull(ModelJson.GSON.fromJson(element, Vector3f.class), "vector");
    }

    public static Vector4f jsonToV4f(JsonElement element) {
        return java.util.Objects.requireNonNull(ModelJson.GSON.fromJson(element, Vector4f.class), "vector");
    }

    public static boolean jsonToBool(JsonObject element, String name, boolean defaultValue) {
        JsonElement ele = element.get(name);
        if (ele != null && ele.isJsonPrimitive() && ele.getAsJsonPrimitive().isBoolean()) {
            return ModelJson.GSON.fromJson(ele, Boolean.class);
        } else {
            return defaultValue;
        }
    }

    public static float jsonToFloat(JsonObject element, String name, float defaultValue) {
        JsonElement ele = element.get(name);
        if (ele != null && ele.isJsonPrimitive() && ele.getAsJsonPrimitive().isNumber()) {
            return ModelJson.GSON.fromJson(ele, Float.class);
        } else {
            return defaultValue;
        }
    }

     public static int jsonToInt(JsonObject element, String name, int defaultValue) {
        JsonElement ele = element.get(name);
        if (ele != null && ele.isJsonPrimitive() && ele.getAsJsonPrimitive().isNumber()) {
            return ModelJson.GSON.fromJson(ele, Integer.class);
        } else {
            return defaultValue;
        }
    }

    public static String jsonToString(JsonObject element, String name, String defaultValue) {
        JsonElement ele = element.get(name);
        if (ele != null && ele.isJsonPrimitive() && ele.getAsJsonPrimitive().isString()) {
            return ModelJson.GSON.fromJson(ele, String.class);
        } else {
            return defaultValue;
        }
    }
}
