package lib.kasuga.rendering.models.mc.source.texture;

import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

public class FileTextureSource extends TextureSource<Path> {

    public FileTextureSource(String name) {
        super(name);
    }

    @Override
    public ResourceLocation toRL(Path sourceIdentifier) {
        return ResourceLocation.tryBuild("kasuga_lib",
                "file_texture/file_" + sourceIdentifier.hashCode() + ".png");
    }

    @Override
    public Optional<InputStream> getInput(Path path) {
        File file = path.toFile();
        if (!file.exists() || !file.isFile()) return Optional.empty();
        try {
            return Optional.of(file.toURI().toURL().openStream());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Class<Path> getInputType() {
        return Path.class;
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof Path path && path.toFile().exists() && path.toFile().isFile();
    }
}
