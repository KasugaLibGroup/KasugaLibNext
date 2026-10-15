package lib.kasuga.rendering.models.uml.structure.material;

import lib.kasuga.rendering.models.uml.structure.material.data.SpriteData;
import lib.kasuga.rendering.models.uml.structure.material.data.SpriteSetData;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

public class SpriteSet implements AutoCloseable {

    private final Sprite[] sprites;

    @Nullable
    @Getter
    private final SpriteSetData data;

    @SafeVarargs
    public SpriteSet(SpriteSetData data, Sprite... sprites) {
        this.sprites = sprites;
        if (sprites.length == 0) {
            throw new IllegalArgumentException("SpriteSet must have at least one sprite");
        }
        this.data = data;
    }

    public Sprite[] getSprites() {
        return sprites;
    }

    public int spriteSize() {
        return sprites.length;
    }

    public Sprite getSprite(int index) {
        if (index < 0 || index >= sprites.length) {
            return sprites[0];
        }
        return sprites[index];
    }

    @Override
    public void close() throws Exception {}
}
