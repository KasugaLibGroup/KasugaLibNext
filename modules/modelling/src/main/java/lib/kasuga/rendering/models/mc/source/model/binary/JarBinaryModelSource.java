package lib.kasuga.rendering.models.mc.source.model.binary;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public final class JarBinaryModelSource extends BinaryModelSource<ResourceLocation> {
    public JarBinaryModelSource(String name) { super(name); }
    @Override public Optional<byte[]> getInput(ResourceLocation input) {
        return Minecraft.getInstance().getResourceManager().getResource(input).flatMap(resource -> {
            try (var stream = resource.open()) { return Optional.of(stream.readAllBytes()); }
            catch (Exception ignored) { return Optional.empty(); }
        });
    }
    @Override public Class<ResourceLocation> getInputType() { return ResourceLocation.class; }
    @Override public boolean isValidInput(Object input) { return input instanceof ResourceLocation; }
}
