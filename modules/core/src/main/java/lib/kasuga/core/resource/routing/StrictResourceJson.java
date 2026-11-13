package lib.kasuga.core.resource.routing;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Set;

/** Shared by opt-in resource consumers. Rejects duplicate keys instead of silently taking the last. */
public final class StrictResourceJson {
    private StrictResourceJson() {}
    public static JsonObject parse(String text) {
        return parse(text, 64);
    }
    public static JsonObject parse(String text, int maximumDepth) {
        if (maximumDepth < 1 || maximumDepth > 256) throw new IllegalArgumentException("Invalid JSON depth limit");
        if (text.length() > 8 * 1024 * 1024) throw new IllegalArgumentException("LIMIT_EXCEEDED: JSON size");
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement value = read(reader, 0, maximumDepth, new int[]{0});
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("Expected exactly one JSON object");
            return value.getAsJsonObject();
        } catch (IOException | IllegalStateException invalid) { throw new IllegalArgumentException("Invalid resource JSON", invalid); }
    }
    private static JsonElement read(JsonReader reader, int depth, int maximumDepth, int[] nodes) throws IOException {
        if (depth > maximumDepth || ++nodes[0] > 100_000) throw new IllegalArgumentException("LIMIT_EXCEEDED: JSON depth/nodes");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw new IllegalArgumentException("Duplicate JSON key at " + reader.getPath());
                    object.add(name, read(reader, depth + 1, maximumDepth, nodes));
                }
                reader.endObject(); return object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); JsonArray array = new JsonArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1, maximumDepth, nodes));
                reader.endArray(); return array;
            }
            case STRING -> { return new JsonPrimitive(reader.nextString()); }
            case NUMBER -> { return new JsonPrimitive(new BigDecimal(reader.nextString())); }
            case BOOLEAN -> { return new JsonPrimitive(reader.nextBoolean()); }
            case NULL -> { reader.nextNull(); return JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Invalid JSON token at " + reader.getPath());
        }
    }
    public static void fields(JsonObject object, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (String name : object.keySet()) if (!names.contains(name)) throw new IllegalArgumentException("Unknown field: " + name);
    }
    public static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) throw new IllegalArgumentException("Expected string: " + key);
        return primitive.getAsString();
    }
    public static String optional(JsonObject object, String key, String fallback) { return object.has(key) ? string(object, key) : fallback; }
    public static void version(JsonObject object) {
        if (!object.has("format_version") || !(object.get("format_version") instanceof JsonPrimitive p) || !p.isNumber())
            throw new IllegalArgumentException("format_version must be 1");
        try { if (p.getAsBigDecimal().intValueExact() != 1) throw new IllegalArgumentException("Unsupported resource format version"); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Invalid resource format version", e); }
    }
    public static JsonArray array(JsonObject object, String key) {
        if (!object.has(key)) return new JsonArray();
        if (!object.get(key).isJsonArray()) throw new IllegalArgumentException("Expected array: " + key);
        return object.getAsJsonArray(key);
    }
    public static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Expected JSON object");
        return value.getAsJsonObject();
    }
}
