package lib.kasuga.rendering.models.mc.source.texture;

import lib.kasuga.rendering.models.uml.loaders.sources.Source;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;

public abstract class TextureSource<T> implements Source<T, InputStream> {

    private final String name;

    public TextureSource(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public Class<InputStream> getOutputType() {
        return InputStream.class;
    }

    public abstract ResourceLocation toRL(T sourceIdentifier);
}
