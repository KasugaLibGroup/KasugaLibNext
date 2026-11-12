package lib.kasuga.resource.category;

import lib.kasuga.resource.category.impl.SimpleResourceCategory;
import lib.kasuga.resource.transformer.impl.JsonMergeTransformer;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class AllResourceCategory {

    private static final Map<String, ResourceCategory> CATEGORIES;

    static {
        Map<String, ResourceCategory> map = new LinkedHashMap<>();
        JsonMergeTransformer jsonMerge = new JsonMergeTransformer();

        // assets-namespaced categories
        register(map, new SimpleResourceCategory("blockstates",
                (ns, p) -> Path.of("assets", ns, "blockstates").resolve(p)));
        register(map, new SimpleResourceCategory("lang",
                (ns, p) -> Path.of("assets", ns, "lang").resolve(p), jsonMerge));
        register(map, new SimpleResourceCategory("models",
                (ns, p) -> Path.of("assets", ns, "models").resolve(p)));
        register(map, new SimpleResourceCategory("sounds",
                (ns, p) -> Path.of("assets", ns, "sounds").resolve(p)));
        register(map, new SimpleResourceCategory("textures",
                (ns, p) -> Path.of("assets", ns, "textures").resolve(p)));

        // data-namespaced categories
        register(map, new SimpleResourceCategory("recipes",
                (ns, p) -> Path.of("data", ns, "recipes").resolve(p)));
        register(map, new SimpleResourceCategory("tags",
                (ns, p) -> Path.of("data", ns, "tags").resolve(p), jsonMerge));
        register(map, new SimpleResourceCategory("loots",
                (ns, p) -> Path.of("data", ns, "loot_tables").resolve(p)));

        // generic passthrough categories
        register(map, new SimpleResourceCategory("data",
                (ns, p) -> Path.of("data").resolve(p), jsonMerge));
        register(map, new SimpleResourceCategory("assets",
                (ns, p) -> Path.of("assets").resolve(p), jsonMerge));
        register(map, new SimpleResourceCategory("scripts",
                (ns, p) -> Path.of("scripts").resolve(p)));

        CATEGORIES = Collections.unmodifiableMap(map);
    }

    private static void register(Map<String, ResourceCategory> map, ResourceCategory category) {
        map.put(category.getFolderName(), category);
    }

    public static ResourceCategory get(String folderName) {
        return CATEGORIES.get(folderName);
    }

    public static Map<String, ResourceCategory> getAll() {
        return CATEGORIES;
    }
}
