package lib.kasuga.rendering.models.mc.java_and_bedrock.data.je;

import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.mc.util.JsonHelper;
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
        Objects.requireNonNull(input, "input must not be null");
        if (!input.has("from") || !input.has("to")) {
            throw new IllegalArgumentException("Element must have both 'from' and 'to' fields");
        }
        Vector3f from = JsonHelper.jsonToV3f(input.get("from")).mul(1/16f);
        Vector3f to = JsonHelper.jsonToV3f(input.get("to")).mul(1/16f);
        Map<Direction, JEFace> faces = new HashMap<>();

        JERotation rotation = null;
        if (input.has("rotation")) {
            rotation = JERotation.fromJson(input.getAsJsonObject("rotation"));
        }

        if (input.has("faces")) {
            JsonObject facesObj = input.getAsJsonObject("faces");
            for (String key : facesObj.keySet()) {
                Direction dir = Direction.fromString(key);
                JsonObject faceObj = facesObj.getAsJsonObject(key);
                faces.put(dir, JEFace.fromJson(faceObj));
            }
        }

        return new JEElement(from, to, faces, rotation);
    }
}
