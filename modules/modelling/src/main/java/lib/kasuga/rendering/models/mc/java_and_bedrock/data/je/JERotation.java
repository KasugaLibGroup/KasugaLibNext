package lib.kasuga.rendering.models.mc.java_and_bedrock.data.je;

import com.google.gson.JsonObject;
import com.google.gson.JsonDeserializer;
import com.google.gson.annotations.JsonAdapter;
import lib.kasuga.rendering.models.mc.util.ModelJson;
import lib.kasuga.utils.json.JsonFieldPolicy.DefaultOnMismatch;
import lombok.Getter;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Objects;

@Getter
public class JERotation {
    
    private final Vector3f origin;
    private final String axis;
    private final float angle;
    private final boolean rescale;

    @Nullable
    private final Vector3f multiAxis;

    public JERotation(Vector3f origin, boolean rescale, String axis, float angle) {
        this.origin = origin;
        this.rescale = rescale;
        this.axis = axis;
        this.angle = angle;
        this.multiAxis = null;
    }
    
    public JERotation(Vector3f origin, boolean rescale, @Nullable Vector3f multiAxis) {
        this.origin = origin;
        this.rescale = rescale;
        this.axis = null;
        this.angle = 0;
        this.multiAxis = multiAxis;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JERotation that)) return false;
        return Float.compare(that.angle, angle) == 0 &&
                rescale == that.rescale &&
                Objects.equals(origin, that.origin) &&
                Objects.equals(axis, that.axis);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(origin, axis, angle, rescale);
    }

    public boolean multiAxis() {
        return multiAxis != null;
    }

    public String getAxisName() {
        return axis != null ? axis : "";
    }

    public static JERotation fromJson(JsonObject input) {
        return ModelJson.GSON.fromJson(Objects.requireNonNull(input), Input.class).build();
    }

    static final class Input {
        Vector3f origin;
        @DefaultOnMismatch Boolean rescale = false;
        Float angle;
        @JsonAdapter(value = AxisValueAdapter.class, nullSafe = false) Float x, y, z;
        String axis;
        JERotation build() {
            if (origin == null) throw new IllegalArgumentException("Rotation must have 'origin' field");
            origin.div(16f);
            if (angle != null) return new JERotation(origin, rescale, Objects.requireNonNull(axis, "axis"), angle);
            if (x != null || y != null || z != null)
                return new JERotation(origin, rescale, new Vector3f(x == null ? 0f : x, y == null ? 0f : y, z == null ? 0f : z));
            throw new IllegalArgumentException("One of rotation method should be specified");
        }
    }
    private static final class AxisValueAdapter implements JsonDeserializer<Float> {
        @Override public Float deserialize(com.google.gson.JsonElement json, java.lang.reflect.Type type,
                com.google.gson.JsonDeserializationContext context) {
            return json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()
                    ? context.deserialize(json, Float.class) : 0f;
        }
    }
}
