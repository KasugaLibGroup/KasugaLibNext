package lib.kasuga.rendering.models.mc.dynamic.collision;

import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.typo.bbmodel.KsgBbModelLoader;
import lib.kasuga.rendering.models.mc.typo.gltf_entry.GltfModelManifest;
import lib.kasuga.rendering.models.uml.dynamic.physics.BoneCollisionBounds;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfAsset;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfLoader;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** Data-pack model access for dedicated or integrated servers. No client resources are consulted. */
public final class ServerCollisionModels {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceManager, Map<ResourceLocation, Optional<Asset>>> GENERATIONS = new WeakHashMap<>();

    private ServerCollisionModels() {}

    public record Asset(Model model, BoneCollisionBounds bounds) {
        public Asset(Model model) {
            this(model, BoneCollisionBounds.from(model));
        }
    }

    /** The location is the full path inside {@code data/<namespace>/}, including the extension. */
    public static synchronized Optional<Asset> find(ServerLevel level, ResourceLocation location) {
        ResourceManager resources = level.getServer().getResourceManager();
        return GENERATIONS.computeIfAbsent(resources, ignored -> new HashMap<>())
                .computeIfAbsent(location, key -> load(resources, key));
    }

    private static Optional<Asset> load(ResourceManager resources, ResourceLocation location) {
        Resource resource = resources.getResource(location).orElse(null);
        if (resource == null) {
            LOGGER.warn("Missing server collision model {} in data packs", location);
            return Optional.empty();
        }
        try (InputStream input = resource.open()) {
            String path = location.getPath();
            Model model;
            if (path.endsWith(".bbmodel")) {
                model = KsgBbModelLoader.loadGeometry(location, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } else if (path.endsWith(".glb") || path.endsWith(".gltf")) {
                GltfAsset asset = GltfLoader.loadAllAnimations(input);
                GltfModelManifest manifest = GltfModelManifest.load(resources, location);
                model = GltfModelConverter.convert(asset, manifest.modelScale(),
                        (index, texture) -> new Texture(texture.name(), 1, 1, null));
            } else {
                throw new IllegalArgumentException("Expected a .bbmodel, .glb or .gltf resource");
            }
            return Optional.of(new Asset(model));
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("Failed to load server collision model {}", location, failure);
            return Optional.empty();
        }
    }
}
