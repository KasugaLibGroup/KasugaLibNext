package lib.kasuga.compat.iris;

import com.mojang.blaze3d.platform.NativeImage;
import lib.kasuga.core.compat.CompatCapability;
import lib.kasuga.mixins.client.AccessorTextureAtlas;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.compat.iris.KasugaTextureAtlas;
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
import net.minecraft.server.packs.resources.ResourceMetadata;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Objects;

final class KasugaPBRLoader implements PBRTextureLoader<KasugaTextureAtlas> {
    private final Constructor<?> spriteContents;
    private final Constructor<?> atlasSprite;

    private KasugaPBRLoader(Constructor<?> spriteContents, Constructor<?> atlasSprite) {
        this.spriteContents = spriteContents;
        this.atlasSprite = atlasSprite;
    }

    static KasugaPBRLoader register() throws ReflectiveOperationException {
        Class<?> contentsClass = CompatCapability.requireClass(AtlasPBRLoader.class.getName() + "$PBRSpriteContents",
                AtlasPBRLoader.class.getClassLoader());
        Constructor<?> contents = contentsClass.getDeclaredConstructor(
                ResourceLocation.class, FrameSize.class, NativeImage.class, ResourceMetadata.class, PBRType.class);
        Constructor<?> sprite = AtlasPBRLoader.PBRTextureAtlasSprite.class.getDeclaredConstructor(
                ResourceLocation.class, contents.getDeclaringClass(), int.class, int.class, int.class, int.class,
                TextureAtlasSprite.class);
        contents.setAccessible(true);
        sprite.setAccessible(true);

        PBRAtlasTexture.class.getConstructor(TextureAtlas.class, PBRType.class);
        CompatCapability.requireMethod(TextureAtlasExtension.class, "getOrCreatePBRHolder", PBRAtlasHolder.class);
        CompatCapability.requireMethod(SpriteContentsExtension.class, "getOrCreatePBRHolder", PBRSpriteHolder.class);
        CompatCapability.requireMethod(PBRAtlasHolder.class, "setNormalAtlas", void.class, PBRAtlasTexture.class);
        CompatCapability.requireMethod(PBRAtlasHolder.class, "setSpecularAtlas", void.class, PBRAtlasTexture.class);
        CompatCapability.requireMethod(PBRSpriteHolder.class, "setNormalSprite", void.class, TextureAtlasSprite.class);
        CompatCapability.requireMethod(PBRSpriteHolder.class, "setSpecularSprite", void.class, TextureAtlasSprite.class);
        CompatCapability.requireMethod(PBRAtlasTexture.class, "getType", PBRType.class);
        CompatCapability.requireMethod(PBRAtlasTexture.class, "addSprite", void.class, sprite.getDeclaringClass());
        CompatCapability.requireMethod(PBRAtlasTexture.class, "tryUpload", boolean.class, int.class, int.class, int.class);

        KasugaPBRLoader loader = new KasugaPBRLoader(contents, sprite);
        PBRTextureLoaderRegistry.INSTANCE.register(KasugaTextureAtlas.class, loader);
        return loader;
    }

    Object createSpriteContent(ResourceLocation name, FrameSize size, NativeImage image, ResourceMetadata metadata,
                               Object type) throws ReflectiveOperationException {
        return spriteContents.newInstance(name, size, image, metadata, type);
    }

    Object createAtlasSprite(ResourceLocation name, Object contents, int width, int height, int x, int y,
                             TextureAtlasSprite sprite) throws ReflectiveOperationException {
        return atlasSprite.newInstance(name, contents, width, height, x, y, sprite);
    }

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
                    Object content = createSpriteContent(
                            spriteId,
                            new FrameSize(
                                    sprite.contents().width(),
                                    sprite.contents().height()
                            ),
                            sprite.contents().getOriginalImage(),
                            sprite.contents().metadata(),
                            pbrTexture.getType()
                    );
                    Object pbrSprite = createAtlasSprite(
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
