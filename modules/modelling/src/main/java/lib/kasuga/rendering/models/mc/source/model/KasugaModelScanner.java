package lib.kasuga.rendering.models.mc.source.model;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class KasugaModelScanner implements ModelScanner {
    
    private ModelProxyConfig proxyConfig;
    
    public KasugaModelScanner() {
        this.proxyConfig = null;
    }
    
    public KasugaModelScanner(ModelProxyConfig proxyConfig) {
        this.proxyConfig = proxyConfig;
    }

    @Override
    public void setConfig(ModelProxyConfig config) {
        this.proxyConfig = config;
    }

    @Override
    public List<ResourceLocation> scan(ResourceManager resourceManager) {
        List<ResourceLocation> resources = new ArrayList<>();

        Map<ResourceLocation, Resource> allResources =
                resourceManager.listResources(
                        "models",
                        id -> id.getNamespace().equals("kasuga_generated") && id.getPath().startsWith("models/v1/")
                                || proxyConfig != null && proxyConfig.matches(id)
                );
        resources.addAll(allResources.keySet());
        
        return resources;
    }
}
