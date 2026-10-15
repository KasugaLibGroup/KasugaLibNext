package lib.kasuga.rendering.models.mc.source.texture;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.util.List;
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
        List<Resource> resources = manager.getResourceStack(location);
        if (resources.isEmpty()) return Optional.empty();
        try {
            return Optional.of(resources.getFirst().open());
        } catch (Exception e) {
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
