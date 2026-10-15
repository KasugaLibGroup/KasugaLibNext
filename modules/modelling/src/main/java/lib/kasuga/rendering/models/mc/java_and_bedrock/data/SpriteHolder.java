package lib.kasuga.rendering.models.mc.java_and_bedrock.data;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public interface SpriteHolder {

    public TextureAtlasSprite getSprite();

    boolean shouldDividedByTextureSize();
}
