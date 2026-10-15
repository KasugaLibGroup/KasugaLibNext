package lib.kasuga.rendering.models.mc.typo.gltf_entry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
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
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            Vector3f scale = parseScale(json.get("model_scale"));
            ResourceLocation ragdoll = null;
            if (json.has("ragdoll") && !json.get("ragdoll").isJsonNull()) {
                ragdoll = ResourceLocation.tryParse(json.get("ragdoll").getAsString());
                if (ragdoll == null) throw new JsonParseException("ragdoll must be a resource location");
            }
            return new GltfModelManifest(scale, ragdoll);
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

    private static Vector3f parseScale(JsonElement value) {
        if (value == null) return new Vector3f(DEFAULT_MODEL_SCALE);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return validated(new Vector3f(value.getAsFloat()));
        }
        if (value.isJsonArray()) {
            JsonArray values = value.getAsJsonArray();
            if (values.size() != 3) throw new JsonParseException("model_scale array must contain three numbers");
            return validated(new Vector3f(values.get(0).getAsFloat(), values.get(1).getAsFloat(),
                    values.get(2).getAsFloat()));
        }
        throw new JsonParseException("model_scale must be a number or a three-number array");
    }

    private static Vector3f validated(Vector3f scale) {
        if (!scale.isFinite() || scale.x <= 0f || scale.y <= 0f || scale.z <= 0f) {
            throw new JsonParseException("model_scale components must be finite and positive");
        }
        return scale;
    }
}
