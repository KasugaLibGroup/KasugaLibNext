package lib.kasuga.rendering.models.mc.source.model;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import lib.kasuga.utils.json.GsonBindings;
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
        // Namespace metadata is allowed alongside pattern arrays.
        JsonObject patterns = new JsonObject();
        json.entrySet().stream().filter(entry -> entry.getValue().isJsonArray())
                .forEach(entry -> patterns.add(entry.getKey(), entry.getValue()));
        return new ModelProxyConfig(GsonBindings.GSON.fromJson(patterns,
                new TypeToken<Map<String, List<String>>>() {}.getType()));
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
