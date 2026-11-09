package lib.kasuga.rendering.models.mc.typo.gltf_entry;

import lib.kasuga.rendering.models.mc.util.ModelJson;
import com.google.gson.JsonParseException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.Objects;

/** Optional {@code <model>.gltf.json} sidecar for glTF resource metadata. */
public record GltfModelManifest(Vector3f modelScale, ResourceLocation ragdollConfig) {
    public static final Vector3f DEFAULT_MODEL_SCALE = new Vector3f(1f);

    public GltfModelManifest {
        modelScale = new Vector3f(Objects.requireNonNull(modelScale, "modelScale"));
        if (!modelScale.isFinite() || modelScale.x <= 0f || modelScale.y <= 0f || modelScale.z <= 0f) {
            throw new IllegalArgumentException("modelScale components must be finite and positive");
        }
    }

    @Override public Vector3f modelScale() { return new Vector3f(modelScale); }

    public static GltfModelManifest load(ResourceManager manager,
                                         ResourceLocation modelLocation) throws IOException {
        Objects.requireNonNull(manager, "manager");
        ResourceLocation manifestLocation = locationFor(modelLocation);
        Resource resource = manager.getResource(manifestLocation).orElse(null);
        if (resource == null) return new GltfModelManifest(DEFAULT_MODEL_SCALE, null);
        try (var reader = resource.openAsReader()) {
            Input input = Objects.requireNonNull(ModelJson.GSON.fromJson(reader, Input.class), "manifest");
            ResourceLocation ragdoll = input.ragdoll == null ? null : ResourceLocation.tryParse(input.ragdoll);
            if (input.ragdoll != null && ragdoll == null) throw new JsonParseException("ragdoll must be a resource location");
            return new GltfModelManifest(input.modelScale.value(), ragdoll);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid glTF model manifest: " + manifestLocation, exception);
        }
    }

    public static ResourceLocation locationFor(ResourceLocation modelLocation) {
        Objects.requireNonNull(modelLocation, "modelLocation");
        String path = modelLocation.getPath();
        int extension = path.lastIndexOf('.');
        String base = extension < 0 ? path : path.substring(0, extension);
        return ResourceLocation.fromNamespaceAndPath(modelLocation.getNamespace(), base + ".gltf.json");
    }

    private static final class Input {
        ModelJson.Scale modelScale = new ModelJson.Scale(DEFAULT_MODEL_SCALE);
        String ragdoll;
    }
}
