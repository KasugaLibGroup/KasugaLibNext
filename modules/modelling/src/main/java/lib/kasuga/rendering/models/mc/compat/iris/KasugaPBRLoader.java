package lib.kasuga.rendering.models.mc.compat.iris;

import lib.kasuga.mixins.client.AccessorTextureAtlas;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.source.texture.CombinedTextureManager;
import lib.kasuga.structure.Pair;
import net.irisshaders.iris.pbr.loader.AtlasPBRLoader;
import net.irisshaders.iris.pbr.loader.PBRTextureLoader;
import net.irisshaders.iris.pbr.loader.PBRTextureLoaderRegistry;
import net.irisshaders.iris.pbr.texture.*;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Map;
import java.util.Objects;

public class KasugaPBRLoader implements PBRTextureLoader<KasugaTextureAtlas> {

    @Override
    public void load(KasugaTextureAtlas kasugaCheatTexture, ResourceManager resourceManager, PBRTextureConsumer pbrTextureConsumer) {
        CombinedTextureManager ksgInput = Constants.TEXTURE_BASIC;
        TextureAtlas albedo = ksgInput.getTextureAtlas();
        TextureAtlas normal = ksgInput.getNormalMap();
        TextureAtlas specular = ksgInput.getSpecularMap();
        PBRAtlasTexture normalTexture = new PBRAtlasTexture(albedo, PBRType.NORMAL);
        PBRAtlasTexture specularTexture = new PBRAtlasTexture(albedo, PBRType.SPECULAR);
        PBRAtlasHolder holder = ((TextureAtlasExtension) albedo).getOrCreatePBRHolder();
        holder.setNormalAtlas(normalTexture);
        holder.setSpecularAtlas(specularTexture);
        Pair<TextureAtlas, PBRAtlasTexture>[] layers = new Pair[]{
                Pair.of(normal, normalTexture),
                Pair.of(specular, specularTexture)
        };
        Map<ResourceLocation, TextureAtlasSprite> sprites;
        for (Pair<TextureAtlas, PBRAtlasTexture> layer : layers) {
            TextureAtlas atlas = layer.getFirst();
            PBRAtlasTexture pbrTexture = layer.getSecond();
            sprites = atlas.getTextures();
            AccessorTextureAtlas accessor = (AccessorTextureAtlas) atlas;
            int width = accessor.callGetWidth();
            int height = accessor.callGetHeight();

            for (Map.Entry<ResourceLocation, TextureAtlasSprite> entry : sprites.entrySet()) {
                ResourceLocation spriteId = entry.getKey();
                TextureAtlasSprite sprite = entry.getValue();
                TextureAtlasSprite albedoSprite = albedo.getSprite(spriteId);
                Objects.requireNonNull(albedoSprite);
                Objects.requireNonNull(albedoSprite.contents());
                SpriteContentsExtension extension = ((SpriteContentsExtension) albedoSprite.contents());
                PBRSpriteHolder spriteHolder = extension.getOrCreatePBRHolder();

                try {
                    Objects.requireNonNull(sprite.contents());
                    Object content = IrisCompat.createPBRSpriteContent(
                            spriteId,
                            new FrameSize(
                                    sprite.contents().width(),
                                    sprite.contents().height()
                            ),
                            sprite.contents().getOriginalImage(),
                            sprite.contents().metadata(),
                            pbrTexture.getType()
                    );
                    Object pbrSprite = IrisCompat.createPBRTextureAtlasSprite(
                            spriteId, content, width, height,
                            sprite.getX(), sprite.getY(), sprite
                    );
                    AtlasPBRLoader.PBRTextureAtlasSprite result = (AtlasPBRLoader.PBRTextureAtlasSprite) pbrSprite;
                    if (pbrTexture.getType() == PBRType.NORMAL) {
                        spriteHolder.setNormalSprite(result);
                    } else if (pbrTexture.getType() == PBRType.SPECULAR) {
                        spriteHolder.setSpecularSprite(result);
                    }
                    pbrTexture.addSprite((AtlasPBRLoader.PBRTextureAtlasSprite) pbrSprite);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            pbrTexture.tryUpload(width, height, 0);
        }
        pbrTextureConsumer.acceptNormalTexture(normalTexture);
        pbrTextureConsumer.acceptSpecularTexture(specularTexture);
    }

}
