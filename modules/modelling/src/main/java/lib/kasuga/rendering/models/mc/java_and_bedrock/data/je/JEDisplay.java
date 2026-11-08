package lib.kasuga.rendering.models.mc.java_and_bedrock.data.je;

import com.google.gson.JsonObject;
import lib.kasuga.utils.json.JsonFieldPolicy.NonNullFields;
import lib.kasuga.rendering.models.mc.util.ModelJson;
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
        Input value = ModelJson.GSON.fromJson(Objects.requireNonNull(input), Input.class);
        return value.build();
    }

    @NonNullFields
    static final class Input {
        Vector3f translation = new Vector3f(), rotation = new Vector3f(), scale = new Vector3f(1f);
        JEDisplay build() {
            return new JEDisplay(translation.div(16f), rotation, scale);
        }
    }
}
