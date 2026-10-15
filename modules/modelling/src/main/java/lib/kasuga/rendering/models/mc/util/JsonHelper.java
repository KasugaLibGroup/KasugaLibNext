package lib.kasuga.rendering.models.mc.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class JsonHelper {

    public static Vector2f jsonToV2f(JsonElement element) {
        if (element.isJsonArray()) {
            var arr = element.getAsJsonArray();
            if (arr.size() != 2) {
                throw new IllegalArgumentException("Expected array of size 2 for Vector2f, got " + arr.size());
            }
            return new Vector2f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat());
        } else if (element.isJsonObject()) {
            var obj = element.getAsJsonObject();
            if (!obj.has("x") || !obj.has("y")) {
                throw new IllegalArgumentException("Expected object with 'x' and 'y' properties for Vector2f");
            }
            return new Vector2f(obj.get("x").getAsFloat(), obj.get("y").getAsFloat());
        } else {
            throw new IllegalArgumentException("Expected JSON array or object for Vector2f, got " + element);
        }
    }

    public static Vector3f jsonToV3f(JsonElement element) {
        if (element.isJsonArray()) {
            var arr = element.getAsJsonArray();
            if (arr.size() != 3) {
                throw new IllegalArgumentException("Expected array of size 3 for Vector3f, got " + arr.size());
            }
            return new Vector3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
        } else if (element.isJsonObject()) {
            var obj = element.getAsJsonObject();
            if (!obj.has("x") || !obj.has("y") || !obj.has("z")) {
                throw new IllegalArgumentException("Expected object with 'x', 'y', and 'z' properties for Vector3f");
            }
            return new Vector3f(obj.get("x").getAsFloat(), obj.get("y").getAsFloat(), obj.get("z").getAsFloat());
        } else {
            throw new IllegalArgumentException("Expected JSON array or object for Vector3f, got " + element);
        }
    }

    public static Vector4f jsonToV4f(JsonElement element) {
        if (element.isJsonArray()) {
            var arr = element.getAsJsonArray();
            if (arr.size() != 4) {
                throw new IllegalArgumentException("Expected array of size 4 for Vector4f, got " + arr.size());
            }
            return new Vector4f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat(), arr.get(3).getAsFloat());
        } else if (element.isJsonObject()) {
            var obj = element.getAsJsonObject();
            if (!obj.has("x") || !obj.has("y") || !obj.has("z") || !obj.has("w")) {
                throw new IllegalArgumentException("Expected object with 'x', 'y', 'z', and 'w' properties for Vector4f");
            }
            return new Vector4f(obj.get("x").getAsFloat(), obj.get("y").getAsFloat(), obj.get("z").getAsFloat(), obj.get("w").getAsFloat());
        } else {
            throw new IllegalArgumentException("Expected JSON array or object for Vector4f, got " + element);
        }
    }

    public static boolean jsonToBool(JsonObject element, String name, boolean defaultValue) {
        JsonElement ele = element.get(name);
        if (ele != null && ele.isJsonPrimitive() && ele.getAsJsonPrimitive().isBoolean()) {
            return ele.getAsBoolean();
        } else {
            return defaultValue;
        }
    }

    public static float jsonToFloat(JsonObject element, String name, float defaultValue) {
        JsonElement ele = element.get(name);
        if (ele != null && ele.isJsonPrimitive() && ele.getAsJsonPrimitive().isNumber()) {
            return ele.getAsFloat();
        } else {
            return defaultValue;
        }
    }

     public static int jsonToInt(JsonObject element, String name, int defaultValue) {
        JsonElement ele = element.get(name);
        if (ele != null && ele.isJsonPrimitive() && ele.getAsJsonPrimitive().isNumber()) {
            return ele.getAsInt();
        } else {
            return defaultValue;
        }
    }

    public static String jsonToString(JsonObject element, String name, String defaultValue) {
        JsonElement ele = element.get(name);
        if (ele != null && ele.isJsonPrimitive() && ele.getAsJsonPrimitive().isString()) {
            return ele.getAsString();
        } else {
            return defaultValue;
        }
    }
}
