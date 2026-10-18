package lib.kasuga.rendering.models.mc.source.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.util.*;

public class ModelProxyConfigLoader {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROXY_FILENAME = "models/model_proxy.json";
    
    public static ModelProxyConfig loadConfig(ResourceManager resourceManager) {
        List<ModelProxyConfig> configs = new ArrayList<>();
        
        for (String namespace : resourceManager.getNamespaces()) {
            ResourceLocation proxyLoc = ResourceLocation.tryBuild(namespace, PROXY_FILENAME);
            
            Optional<Resource> resource = resourceManager.getResource(proxyLoc);
            if (resource.isPresent()) {
                try {
                    Resource res = resource.get();
                    try (BufferedReader reader = res.openAsReader()) {
                        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                        ModelProxyConfig config = ModelProxyConfig.fromJson(json);
                        configs.add(config);
                        
                        LOGGER.info(
                            "Loaded proxy config from {}: {} namespaces configured", 
                            proxyLoc,
                            config.getNamespaces().size()
                        );
                    }
                } catch (Exception e) {
                    LOGGER.error(
                        "Failed to load proxy config from {}", 
                        proxyLoc, 
                        e
                    );
                }
            }
        }
        
        if (configs.isEmpty()) {
            LOGGER.info("No {} found in any namespace", PROXY_FILENAME);
            return null;
        }
        
        if (configs.size() == 1) {
            ModelProxyConfig config = configs.get(0);
            LOGGER.info(
                "Loaded 1 proxy config with {} namespaces", 
                config.getNamespaces().size()
            );
            return config;
        }
        
        ModelProxyConfig merged = mergeConfigs(configs);
        LOGGER.info(
            "Merged {} proxy configs with {} total namespaces", 
            configs.size(), 
            merged.getNamespaces().size()
        );
        
        return merged;
    }
    
    private static ModelProxyConfig mergeConfigs(List<ModelProxyConfig> configs) {
        Map<String, List<String>> mergedNamespaces = new HashMap<>();
        
        for (ModelProxyConfig config : configs) {
            for (String ns : config.getNamespaces()) {
                List<String> patterns = config.getPatterns(ns);
                mergedNamespaces.computeIfAbsent(ns, k -> new ArrayList<>())
                    .addAll(patterns);
            }
        }
        
        return new ModelProxyConfig(mergedNamespaces);
    }
}
