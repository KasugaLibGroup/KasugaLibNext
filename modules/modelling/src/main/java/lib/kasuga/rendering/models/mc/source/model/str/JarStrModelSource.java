package lib.kasuga.rendering.models.mc.source.model.str;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.util.List;
import java.util.Optional;

public class JarStrModelSource extends StringModelSource<ResourceLocation> {

    public JarStrModelSource(String name) {
        super(name);
    }

    @Override
    public Optional<String> getInput(ResourceLocation input) {
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        ResourceLocation location = ResourceLocation.tryBuild(
                input.getNamespace(), input.getPath()
        );
        List<Resource> resources = manager.getResourceStack(location);
        if (resources.isEmpty()) return Optional.empty();
        Resource resource = resources.getFirst();
        try (BufferedReader reader = resource.openAsReader()) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return Optional.of(builder.toString());
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
