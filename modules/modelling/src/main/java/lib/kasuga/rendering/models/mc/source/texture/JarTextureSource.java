package lib.kasuga.rendering.models.mc.source.texture;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

public class JarTextureSource extends TextureSource<ResourceLocation> {


    public JarTextureSource(String name) {
        super(name);
    }

    @Override
    public ResourceLocation toRL(ResourceLocation sourceIdentifier) {
        return sourceIdentifier;
    }

    @Override
    public Optional<InputStream> getInput(ResourceLocation input) {
        ResourceLocation location = ResourceLocation.tryBuild(input.getNamespace(), "textures/" + input.getPath() + ".png");
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        Optional<Resource> resource = manager.getResource(location);
        if (resource.isEmpty()) return Optional.empty();
        try {
            return Optional.of(resource.get().open());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public Class<ResourceLocation> getInputType() {
        return ResourceLocation.class;
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof ResourceLocation;
    }
}
