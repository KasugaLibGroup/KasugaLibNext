package lib.kasuga.rendering.models.uml.structure.material;

import lib.kasuga.rendering.models.uml.structure.material.data.SpriteData;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.util.Objects;

public class Sprite {

    @Getter
    private final Texture texture;

    @Getter
    private final Vector2f uv0, uv1, uv2, uv3; // Left Top, Right Top, Right Bottom, Left Bottom

    @Getter
    private final SpriteData data;

    public Vector4f color, ambient, specular;
    public boolean shade;
    public boolean flipU, flipV;
    public boolean ambientOcclusion;
    public boolean culled;
    public boolean emissive;


    public Sprite(Texture texture, Vector2f uv0, Vector2f uv1, Vector2f uv2, Vector2f uv3, @Nullable Vector4f color, @Nullable Vector4f ambient, @Nullable Vector4f specular, @Nullable SpriteData data) {
        this.texture = texture;
        this.uv0 = uv0;
        this.uv1 = uv1;
        this.uv2 = uv2;
        this.uv3 = uv3;
        this.color = color != null ? color : new Vector4f(1, 1, 1, 1);
        this.ambient = ambient != null ? ambient : new Vector4f(1, 1, 1, 1);
        this.specular = specular != null ? specular : new Vector4f(1, 1, 1, 1);
        this.shade = true;
        this.flipU = false;
        this.flipV = false;
        this.ambientOcclusion = true;
        this.culled = false;
        this.emissive = false;
        this.data = data;
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj ||
                (obj instanceof Sprite other &&
                Objects.equals(this.texture, other.texture) &&
                Objects.equals(this.uv0, other.uv0) &&
                Objects.equals(this.uv1, other.uv1) &&
                Objects.equals(this.uv2, other.uv2) &&
                Objects.equals(this.uv3, other.uv3) &&
                Objects.equals(this.data, other.data) &&
                Objects.equals(this.color, other.color));
    }

    @Override
    public int hashCode() {
        return Objects.hash(texture, uv0, uv1, uv2, uv3, data, color);
    }
}
