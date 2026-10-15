package lib.kasuga.rendering.models.mc.java_and_bedrock.data.je;

import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.util.JsonHelper;
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
        Objects.requireNonNull(input, "input must not be null");

        if (!input.has("origin")) {
            throw new IllegalArgumentException("Rotation must have 'origin' field");
        }
        Vector3f origin = JsonHelper.jsonToV3f(input.get("origin")).mul(1/16f);
        boolean rescale = JsonHelper.jsonToBool(input, "rescale", false);

        if (input.has("angle")) {
            float angle = input.get("angle").getAsFloat();
            String axis = input.get("axis").getAsString();
            return new JERotation(origin, rescale, axis, angle);
        } else if (input.has("x") || input.has("y") || input.has("z")) {
            float x = JsonHelper.jsonToFloat(input, "x", 0f);
            float y = JsonHelper.jsonToFloat(input, "y", 0f);
            float z = JsonHelper.jsonToFloat(input, "z", 0f);
            Vector3f multiAxis = new Vector3f(x, y, z);
            return new JERotation(origin, rescale, multiAxis);
        } else {
            throw new IllegalArgumentException("One of rotation method should be specified");
        }
    }
}
