package lib.kasuga.utils.json;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.annotation.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Opt-in input policies layered over Gson's field bindings, without duplicating format schemas. */
public final class JsonFieldPolicy implements TypeAdapterFactory {
    /** Missing fields retain DTO defaults; an explicit null for a known field is invalid. */
    @Inherited @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
    public @interface NonNullFields {}

    /** Wrong primitive kinds and explicit null retain this field's constructor default. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
    public @interface DefaultOnMismatch {}

    private record Binding(String name, Class<?> type, boolean fallback) {}

    @Override public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        Class<?> raw = type.getRawType();
        boolean nonNull = raw.isAnnotationPresent(NonNullFields.class);
        List<Binding> bindings = new ArrayList<>();
        for (Class<?> owner = raw; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            // Only DTOs opt in. Never inspect JDK/Minecraft implementation details.
            if (!nonNull && java.util.Arrays.stream(owner.getDeclaredFields())
                    .noneMatch(field -> field.isAnnotationPresent(DefaultOnMismatch.class))) continue;
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers()) || field.isSynthetic()) continue;
                boolean fallback = field.isAnnotationPresent(DefaultOnMismatch.class);
                if (!nonNull && !fallback) continue;
                SerializedName name = field.getAnnotation(SerializedName.class);
                bindings.add(new Binding(name == null ? FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES.translateName(field)
                        : name.value(), field.getType(), fallback));
                if (name != null) for (String alias : name.alternate()) bindings.add(new Binding(alias, field.getType(), fallback));
            }
        }
        if (!nonNull && bindings.isEmpty()) return null;
        TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
        TypeAdapter<JsonElement> trees = gson.getAdapter(JsonElement.class);
        return new TypeAdapter<>() {
            @Override public T read(JsonReader reader) throws IOException {
                JsonElement tree = trees.read(reader);
                if (nonNull && tree.isJsonNull()) throw new JsonParseException("Expected " + raw.getSimpleName());
                if (tree.isJsonObject()) {
                    JsonObject object = tree.getAsJsonObject();
                    boolean copied = false;
                    for (Binding field : bindings) {
                        JsonElement value = object.get(field.name);
                        if (value == null) continue;
                        if (field.fallback && !matches(value, field.type)) {
                            if (!copied) {
                                JsonObject copy = new JsonObject();
                                object.entrySet().forEach(entry -> copy.add(entry.getKey(), entry.getValue()));
                                object = copy;
                                copied = true;
                            }
                            object.remove(field.name);
                        } else if (nonNull && (value.isJsonNull() || field.type.isArray() && hasNullElement(value)))
                            throw new JsonParseException(field.name + " must not be null");
                    }
                    tree = object;
                }
                return delegate.fromJsonTree(tree);
            }
            @Override public void write(JsonWriter writer, T value) throws IOException { delegate.write(writer, value); }
        };
    }

    private static boolean hasNullElement(JsonElement value) {
        if (!value.isJsonArray()) return false;
        for (JsonElement element : value.getAsJsonArray()) if (element.isJsonNull() || hasNullElement(element)) return true;
        return false;
    }

    private static boolean matches(JsonElement value, Class<?> type) {
        if (!value.isJsonPrimitive()) return false;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (type == String.class) return primitive.isString();
        if (type == Boolean.class || type == boolean.class) return primitive.isBoolean();
        return primitive.isNumber();
    }
}
