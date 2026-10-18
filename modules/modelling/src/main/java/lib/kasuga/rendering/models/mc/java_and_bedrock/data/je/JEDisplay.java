package lib.kasuga.rendering.models.mc.java_and_bedrock.data.je;

import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.util.JsonHelper;
import lombok.Getter;
import org.joml.Vector3f;

import java.util.Objects;

@Getter
public class JEDisplay {
    
    private final Vector3f translation;
    private final Vector3f rotation;
    private final Vector3f scale;
    
    public JEDisplay(Vector3f translation, Vector3f rotation, Vector3f scale) {
        this.translation = translation;
        this.rotation = rotation;
        this.scale = scale;
    }
    
    public JEDisplay() {
        this(new Vector3f(0, 0, 0), new Vector3f(0, 0, 0), new Vector3f(1, 1, 1));
    }

    public boolean isDefault() {
        return translation.equals(0, 0, 0) &&
                rotation.equals(0, 0, 0) &&
                scale.equals(1, 1, 1);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JEDisplay that)) return false;
        return Objects.equals(translation, that.translation) &&
                Objects.equals(rotation, that.rotation) &&
                Objects.equals(scale, that.scale);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(translation, rotation, scale);
    }

    public static JEDisplay fromJson(JsonObject input) {
        Objects.requireNonNull(input, "input must not be null");

        Vector3f translation = new Vector3f(0, 0, 0);
        Vector3f rotation = new Vector3f(0, 0, 0);
        Vector3f scale = new Vector3f(1, 1, 1);

        if (input.has("translation")) {
            translation = JsonHelper.jsonToV3f(input.get("translation")).div(16f);
        }
        if (input.has("rotation")) {
            rotation = JsonHelper.jsonToV3f(input.get("rotation"));
        }
        if (input.has("scale")) {
            scale = JsonHelper.jsonToV3f(input.get("scale"));
        }

        return new JEDisplay(translation, rotation, scale);
    }
}
