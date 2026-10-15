package lib.kasuga.rendering.models.mc.typo.pmx_entry;

import lib.kasuga.rendering.models.mc.source.texture.TextureSource;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;

public class ZipTextureSource extends TextureSource<ZipResource> {


    public ZipTextureSource(String name) {
        super(name);
    }

    @Override
    public ResourceLocation toRL(ZipResource sourceIdentifier) {
        return ResourceLocation.tryBuild("kasuga_lib", "textures/zip/" + sourceIdentifier.hashCode());
    }

    @Override
    public Optional<InputStream> getInput(ZipResource input) {
        return Optional.of(new ByteArrayInputStream(input.buffer().array()));
    }

    @Override
    public Class<ZipResource> getInputType() {
        return ZipResource.class;
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof ZipResource;
    }
}
