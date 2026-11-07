package lib.kasuga.utils.json;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.math.BigDecimal;

/** Shared field naming for resource/configuration DTOs. Format-specific conversions stay with the format. */
public final class GsonBindings {
    public static final Gson GSON = builder().create();
    public static final Gson STRICT = builder()
            .registerTypeAdapter(String.class, new StringAdapter())
            .registerTypeAdapter(boolean.class, new BooleanAdapter())
            .registerTypeAdapter(Boolean.class, new BooleanAdapter())
            .registerTypeAdapter(int.class, new IntegerAdapter())
            .registerTypeAdapter(Integer.class, new IntegerAdapter())
            .create();

    private GsonBindings() {}

    public static GsonBuilder builder() {
        return new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .registerTypeAdapterFactory(new JsonFieldPolicy());
    }

    private static void require(JsonReader reader, JsonToken token) throws IOException {
        if (reader.peek() != token) throw new JsonParseException("Expected " + token + " at " + reader.getPath());
    }

    public static final class StringAdapter extends TypeAdapter<String> {
        @Override public String read(JsonReader reader) throws IOException {
            require(reader, JsonToken.STRING);
            return reader.nextString();
        }
        @Override public void write(JsonWriter writer, String value) throws IOException { writer.value(value); }
    }

    public static final class BooleanAdapter extends TypeAdapter<Boolean> {
        @Override public Boolean read(JsonReader reader) throws IOException {
            require(reader, JsonToken.BOOLEAN);
            return reader.nextBoolean();
        }
        @Override public void write(JsonWriter writer, Boolean value) throws IOException { writer.value(value); }
    }

    public static final class IntegerAdapter extends TypeAdapter<Integer> {
        @Override public Integer read(JsonReader reader) throws IOException {
            require(reader, JsonToken.NUMBER);
            try { return new BigDecimal(reader.nextString()).intValueExact(); }
            catch (NumberFormatException | ArithmeticException failure) {
                throw new JsonParseException("Expected an exact integer at " + reader.getPath(), failure);
            }
        }
        @Override public void write(JsonWriter writer, Integer value) throws IOException { writer.value(value); }
    }
}
