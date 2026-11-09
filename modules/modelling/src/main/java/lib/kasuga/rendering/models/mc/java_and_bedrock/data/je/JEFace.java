package lib.kasuga.rendering.models.mc.java_and_bedrock.data.je;

import com.google.gson.JsonObject;
import lib.kasuga.utils.json.JsonFieldPolicy.NonNullFields;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.mc.util.ModelJson;
import lib.kasuga.utils.json.JsonFieldPolicy.DefaultOnMismatch;
import lombok.Getter;
import org.joml.Vector2f;

import javax.annotation.Nullable;
import java.util.Objects;

@Getter
public class JEFace {
    
    private final String texture;
    private final Vector2f uvFrom;
    private final Vector2f uvTo;
    private final int tintIndex;
    @Nullable
    private final Direction cullface;
    private final int rotation;
    
    public JEFace(String texture, Vector2f uvFrom, Vector2f uvTo, int tintIndex, @Nullable Direction cullface, int rotation) {
        this.texture = texture;
        this.uvFrom = uvFrom;
        this.uvTo = uvTo;
        this.tintIndex = tintIndex;
        this.cullface = cullface;
        this.rotation = rotation;
    }
    
    public JEFace(String texture, Vector2f uvFrom, Vector2f uvTo, int tintIndex, @Nullable Direction cullface) {
        this(texture, uvFrom, uvTo, tintIndex, cullface, 0);
    }
    
    public JEFace(String texture, Vector2f uvFrom, Vector2f uvTo) {
        this(texture, uvFrom, uvTo, -1, null, 0);
    }

    public float getU(int index) {
        return index == 0 || index == 1 ? uvFrom.x : uvTo.x;
    }
    
    public float getV(int index) {
        return index == 0 || index == 3 ? uvFrom.y : uvTo.y;
    }
    
    public boolean hasCullface() {
        return cullface != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JEFace jeFace)) return false;
        return tintIndex == jeFace.tintIndex &&
                rotation == jeFace.rotation &&
                Objects.equals(texture, jeFace.texture) &&
                Objects.equals(uvFrom, jeFace.uvFrom) &&
                Objects.equals(uvTo, jeFace.uvTo) &&
                Objects.equals(cullface, jeFace.cullface);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(texture, uvFrom, uvTo, tintIndex, cullface, rotation);
    }

    public static JEFace fromJson(JsonObject input) {
        return ModelJson.GSON.fromJson(Objects.requireNonNull(input), Input.class).build();
    }

    @NonNullFields
    static final class Input {
        String texture, cullface;
        float[] uv = new float[4];
        @DefaultOnMismatch Integer tintindex = -1, rotation = 0;
        JEFace build() {
            if (texture == null) throw new IllegalArgumentException("face texture must not be null");
            if (uv == null || uv.length != 4) throw new IllegalArgumentException("Expected 4 uv values");
            if (rotation < 0 || rotation % 90 != 0 || rotation > 270)
                throw new IllegalArgumentException("Invalid rotation " + rotation + ", only 0/90/180/270 allowed");
            Direction direction = cullface == null || cullface.isEmpty() ? null : Direction.fromString(cullface);
            return new JEFace(texture, new Vector2f(uv[0], uv[1]), new Vector2f(uv[2], uv[3]), tintindex, direction, rotation);
        }
    }
}
