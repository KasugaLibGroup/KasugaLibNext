package lib.kasuga.rendering.models.mc.typo.pmx_entry;

import lib.kasuga.rendering.models.mc.util.ModelJson;
import com.google.gson.JsonElement;
import com.google.gson.JsonDeserializer;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.JsonObject;
import lombok.Getter;
import org.joml.Vector3f;

import java.nio.charset.Charset;

public class ZipMeta {

    public static final float DEFAULT_MODEL_SCALE = 1.0f / 12.0f;

    @Getter
    private Charset charset;

    private final Vector3f modelScale;

    public ZipMeta(JsonObject json) {
        Input input = ModelJson.GSON.fromJson(json, Input.class);
        charset = input.encoding == null ? Charset.defaultCharset() : input.encoding;
        modelScale = input.modelScale.value();
    }

    /** PMX-to-world scale; returned by value because JOML vectors are mutable. */
    public Vector3f getModelScale() {
        return new Vector3f(modelScale);
    }

    private static final class Input {
        @JsonAdapter(EncodingAdapter.class) Charset encoding = Charset.defaultCharset();
        ModelJson.Scale modelScale = new ModelJson.Scale(new Vector3f(DEFAULT_MODEL_SCALE));
    }
    private static final class EncodingAdapter implements JsonDeserializer<Charset> {
        @Override public Charset deserialize(JsonElement json, java.lang.reflect.Type type,
                com.google.gson.JsonDeserializationContext context) {
            try {
                String name;
                if (json.isJsonArray()) {
                    String[] names = context.deserialize(json, String[].class);
                    if (names.length != 1) return Charset.defaultCharset();
                    name = names[0];
                } else name = context.deserialize(json, String.class);
                return Charset.forName(name);
            } catch (RuntimeException invalidEncoding) {
                return Charset.defaultCharset();
            }
        }
    }
}
