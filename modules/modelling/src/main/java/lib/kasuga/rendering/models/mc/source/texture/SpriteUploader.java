package lib.kasuga.rendering.models.mc.source.texture;

import com.mojang.blaze3d.platform.NativeImage;
import lombok.Getter;
import lombok.NonNull;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.neoforged.neoforge.client.textures.SpriteContentsConstructor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

@Getter
public class SpriteUploader<T> implements SpriteResourceLoader {

    @NonNull
    private final T resourceIdentifier;

    @NonNull
    private final TextureSource<T> source;

    @NonNull
    private final KasugaTextureManager manager;

    public SpriteUploader(@NonNull KasugaTextureManager manager, @NonNull TextureSource<T> source, @NonNull T resourceIdentifier) {
        this.source = source;
        this.resourceIdentifier = resourceIdentifier;
        this.manager = manager;
    }

    public Optional<InputStream> read() {
        return source.getInput(resourceIdentifier);
    }

    @Override
    public @Nullable SpriteContents loadSprite(ResourceLocation resourceLocation, Resource resource, SpriteContentsConstructor spriteContentsConstructor) {
        Optional<InputStream> optional = read();
        if (optional.isEmpty()) return null;
        try (InputStream stream = optional.get()) {
            NativeImage image = NativeImage.read(stream);
            return spriteContentsConstructor.create(
                    source.toRL(resourceIdentifier),
                    new FrameSize(image.getWidth(), image.getHeight()),
                    image,
                    ResourceMetadata.EMPTY
            );
        } catch (IOException e) {
            LOGGER.warn("Failed to load sprite from resource: {}", resourceIdentifier, e);
            return null;
        }
    }
}
