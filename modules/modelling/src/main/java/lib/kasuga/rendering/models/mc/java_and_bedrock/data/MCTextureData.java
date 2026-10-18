package lib.kasuga.rendering.models.mc.java_and_bedrock.data;

import lib.kasuga.rendering.models.mc.source.texture.KasugaTextureManager;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public class MCTextureData implements TextureData, SpriteHolder {

    @NonNull
    @Getter
    private final Object identifier;

    @NonNull
    @Getter
    private final KasugaTextureManager textureManager;

    @Setter
    private boolean dividedByTextureSize;

    public MCTextureData(@NonNull Object identifier, @NonNull KasugaTextureManager textureManager) {
        this(identifier, textureManager, false);
    }

    public MCTextureData(@NonNull Object identifier, @NonNull KasugaTextureManager textureManager, boolean dividedByTextureSize) {
        this.identifier = identifier;
        this.textureManager = textureManager;
        this.dividedByTextureSize = dividedByTextureSize;
    }

    public TextureAtlasSprite getSprite() {
        return textureManager.get(identifier);
    }

    @Override
    public boolean shouldDividedByTextureSize() {
        return dividedByTextureSize;
    }
}
