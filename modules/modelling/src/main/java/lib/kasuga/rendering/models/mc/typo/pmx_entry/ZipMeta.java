package lib.kasuga.rendering.models.mc.typo.pmx_entry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import lombok.Getter;
import org.joml.Vector3f;

import java.nio.charset.Charset;

public class ZipMeta {

    public static final float DEFAULT_MODEL_SCALE = 1.0f / 12.0f;

    @Getter
    private Charset charset;

    private final Vector3f modelScale;

    public ZipMeta(JsonObject json) {
        try {
            charset = Charset.forName(json.get("encoding").getAsString());
        } catch (Exception e) {
            charset = Charset.defaultCharset();
        }
        modelScale = parseModelScale(json.get("model_scale"));
    }

    /** PMX-to-world scale; returned by value because JOML vectors are mutable. */
    public Vector3f getModelScale() {
        return new Vector3f(modelScale);
    }

    private static Vector3f parseModelScale(JsonElement value) {
        if (value == null) return new Vector3f(DEFAULT_MODEL_SCALE);
        Vector3f result;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            result = new Vector3f(value.getAsFloat());
        } else if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            if (array.size() != 3) {
                throw new JsonParseException("model_scale must be a number or three-number array");
            }
            result = new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(),
                    array.get(2).getAsFloat());
        } else {
            throw new JsonParseException("model_scale must be a number or three-number array");
        }
        if (!result.isFinite() || result.x <= 0f || result.y <= 0f || result.z <= 0f) {
            throw new JsonParseException("model_scale components must be finite and positive");
        }
        return result;
    }
}
