package lib.kasuga.rendering.models.mc.source.model;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.List;

public interface ModelScanner {
    void setConfig(ModelProxyConfig config);

    List<ResourceLocation> scan(ResourceManager resourceManager);
}
