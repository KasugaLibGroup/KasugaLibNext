package lib.kasuga.rendering.models.mc.source.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

public class ModelProxyConfig {
    
    private final Map<String, List<String>> namespaces;
    
    public ModelProxyConfig() {
        this.namespaces = new HashMap<>();
    }
    
    public ModelProxyConfig(Map<String, List<String>> namespaces) {
        this.namespaces = namespaces;
    }
    
    public static ModelProxyConfig fromJson(JsonObject json) {
        Map<String, List<String>> namespaces = new HashMap<>();
        
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String namespace = entry.getKey();
            JsonElement value = entry.getValue();
            
            if (value.isJsonArray()) {
                List<String> patterns = new ArrayList<>();
                for (JsonElement element : value.getAsJsonArray()) {
                    patterns.add(element.getAsString());
                }
                namespaces.put(namespace, patterns);
            }
        }
        
        return new ModelProxyConfig(namespaces);
    }
    
    public boolean matches(ResourceLocation resource) {
        String namespace = resource.getNamespace();
        String path = resource.getPath();
        
        List<String> patterns = namespaces.get(namespace);
        if (patterns == null) {
            return false;
        }
        
        boolean included = false;
        for (String pattern : patterns) {
            if (pattern.startsWith("!")) {
                // '!' stand for exclude this element.
                if (matchesPattern(path, pattern.substring(1))) {
                    return false;
                }
            } else {
                if (matchesPattern(path, pattern)) {
                    included = true;
                }
            }
        }
        
        return included;
    }
    
    private boolean matchesPattern(String path, String pattern) {
        if (pattern.startsWith("/")) {
            pattern = pattern.substring(1);
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        
        String regex = pattern
            .replace(".", "\\.")
            .replace("**", "§§")
            .replace("*", "[^/]*")
            .replace("§§", ".*")
            .replace("?", ".");
        
        return path.matches(regex);
    }
    
    public Set<String> getNamespaces() {
        return namespaces.keySet();
    }
    
    public List<String> getPatterns(String namespace) {
        return namespaces.getOrDefault(namespace, new ArrayList<>());
    }
    
    public void addNamespace(String namespace, List<String> patterns) {
        this.namespaces.put(namespace, patterns);
    }

}
