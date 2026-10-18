package lib.kasuga.rendering.models.mc.java_and_bedrock.data.je;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.mc.util.JsonHelper;
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
        Objects.requireNonNull(input, "input must not be null");

        if (!input.has("texture")) {
            throw new IllegalArgumentException("face texture must not be null");
        }
        String texture = input.get("texture").getAsString();

        Vector2f uvFrom;
        Vector2f uvTo;
        
        if (input.has("uv")) {
            JsonArray uvArray = input.getAsJsonArray("uv");
            if (uvArray.size() != 4) {
                throw new IllegalArgumentException("Expected 4 uv values, found: " + uvArray.size());
            }
            float u1 = uvArray.get(0).getAsFloat();
            float v1 = uvArray.get(1).getAsFloat();
            float u2 = uvArray.get(2).getAsFloat();
            float v2 = uvArray.get(3).getAsFloat();
            uvFrom = new Vector2f(u1, v1);
            uvTo = new Vector2f(u2, v2);
        } else {
            uvFrom = new Vector2f(0, 0);
            uvTo = new Vector2f(0, 0);
        }
        
        int tintIndex = JsonHelper.jsonToInt(input, "tintindex", -1);
        
        Direction cullface = null;
        if (input.has("cullface")) {
            String cullfaceStr = input.get("cullface").getAsString();
            if (!cullfaceStr.isEmpty()) {
                cullface = Direction.fromString(cullfaceStr);
            }
        }
        
        int rotation = JsonHelper.jsonToInt(input, "rotation", 0);
        if (rotation < 0 || rotation % 90 != 0 || rotation / 90 > 3) {
            throw new IllegalArgumentException("Invalid rotation " + rotation + " found, only 0/90/180/270 allowed");
        }
        
        return new JEFace(texture, uvFrom, uvTo, tintIndex, cullface, rotation);
    }
}
