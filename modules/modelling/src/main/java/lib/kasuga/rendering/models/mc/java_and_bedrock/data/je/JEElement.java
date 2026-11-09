package lib.kasuga.rendering.models.mc.java_and_bedrock.data.je;

import com.google.gson.JsonObject;
import lib.kasuga.utils.json.JsonFieldPolicy.NonNullFields;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.mc.util.ModelJson;
import lombok.Getter;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Getter
public class JEElement {
    
    private final Vector3f from;
    private final Vector3f to;
    private final Map<Direction, JEFace> faces;

    @Nullable
    private final JERotation rotation;
    
    public JEElement(Vector3f from, Vector3f to, Map<Direction, JEFace> faces, JERotation rotation) {
        this.from = from;
        this.to = to;
        this.faces = faces;
        this.rotation = rotation;
    }

    public Vector3f getSize() {
        return new Vector3f(to).sub(from);
    }
    
    public boolean hasRotation() {
        return rotation != null;
    }

    public static JEElement fromJson(JsonObject input) {
        return ModelJson.GSON.fromJson(Objects.requireNonNull(input), Input.class).build();
    }

    @NonNullFields
    static final class Input {
        Vector3f from, to;
        Map<String, JEFace.Input> faces = Map.of();
        JERotation.Input rotation;
        JEElement build() {
            if (from == null || to == null) throw new IllegalArgumentException("Element must have both 'from' and 'to' fields");
            Map<Direction, JEFace> built = new HashMap<>();
            faces.forEach((direction, face) -> built.put(Direction.fromString(direction), face.build()));
            return new JEElement(from.div(16f), to.div(16f), built, rotation == null ? null : rotation.build());
        }
    }
}
