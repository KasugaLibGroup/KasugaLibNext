package lib.kasuga.rendering.models.uml.structure.material;

import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lombok.Getter;
import lombok.Setter;

@Getter
public class Texture {

    private final String id;

    private final float width, height;

    @Setter
    private TextureData data;

    public Texture(String id, float textureWidth, float textureHeight, TextureData data) {
        this.data = data;
        this.id = id;
        this.width = textureWidth;
        this.height = textureHeight;
    }
}
