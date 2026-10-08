package lib.kasuga.rendering.models.mc.source.model.assembly;

import com.google.gson.*;
import lib.kasuga.rendering.models.mc.api.McModelAssemblies;
import lib.kasuga.rendering.models.uml.loaders.assembly.ModelAssemblyDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.util.*;

/** Resource-pack JSON adapter; source model format selection stays in the existing resource pipelines. */
public final class AssemblyResourceDefinitions {
    public static final String DIRECTORY = "model_assemblies";
    private AssemblyResourceDefinitions() {}

    public static Map<ResourceLocation, ModelAssemblyDefinition<McModelAssemblies.Reference>> load(ResourceManager manager) {
        Map<ResourceLocation, ModelAssemblyDefinition<McModelAssemblies.Reference>> output = new LinkedHashMap<>();
        var resources = manager.listResources(DIRECTORY, id -> id.getPath().endsWith(".json"));
        for (var entry : new TreeMap<>(resources).entrySet()) {
            var location = entry.getKey();
            String path = location.getPath().substring(DIRECTORY.length() + 1, location.getPath().length() - 5);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(location.getNamespace(), path);
            try (var reader = entry.getValue().openAsReader()) {
                output.put(id, parse(JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (IOException | RuntimeException failure) {
                throw new IllegalArgumentException("Invalid model assembly resource " + location, failure);
            }
        }
        return Map.copyOf(output);
    }

    public static ModelAssemblyDefinition<McModelAssemblies.Reference> parse(JsonObject json) {
        JsonObject body = json.getAsJsonObject("body");
        if (body == null) throw new IllegalArgumentException("assembly body required");
        var builder = ModelAssemblyDefinition.builder(text(body, "id", "body"), reference(body), part -> configure(body, part));
        if (json.has("parts")) {
            for (JsonElement value : json.getAsJsonArray("parts")) {
                JsonObject component = value.getAsJsonObject();
                builder.part(text(component, "id", null), reference(component), part -> configure(component, part));
            }
        }
        if (json.has("bind_tolerance")) builder.bindTolerance(json.get("bind_tolerance").getAsFloat());
        return builder.build();
    }

    private static McModelAssemblies.Reference reference(JsonObject json) {
        String model = text(json, "model", null);
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model resource required");
        return new McModelAssemblies.Reference(ResourceLocation.parse(model), text(json, "model_name", null));
    }
    private static void configure(JsonObject json, ModelAssemblyDefinition.PartBuilder part) {
        if (json.has("bone_mappings")) json.getAsJsonObject("bone_mappings").entrySet()
                .forEach(entry -> part.mapBone(entry.getKey(), entry.getValue().getAsString()));
        if (json.has("regions")) json.getAsJsonObject("regions").entrySet()
                .forEach(entry -> part.region(entry.getKey(), indices(entry.getValue().getAsJsonArray())));
        if (json.has("hide_regions")) {
            for (var name : json.getAsJsonArray("hide_regions")) part.hideRegions(name.getAsString());
        }
        if (json.has("hide_meshes")) part.hideMeshes(indices(json.getAsJsonArray("hide_meshes")));
        if (json.has("match_body_bones")) part.matchBodyBones(flag(json, "match_body_bones"));
        if (json.has("include_dynamics")) part.includeDynamics(flag(json, "include_dynamics"));
    }
    private static boolean flag(JsonObject json, String name) {
        JsonPrimitive value = json.getAsJsonPrimitive(name);
        if (!value.isBoolean()) throw new IllegalArgumentException(name + " must be boolean");
        return value.getAsBoolean();
    }
    private static int[] indices(JsonArray json) {
        int[] indices = new int[json.size()];
        for (int i = 0; i < indices.length; i++) {
            JsonPrimitive value = json.get(i).getAsJsonPrimitive();
            if (!value.isNumber()) throw new IllegalArgumentException("mesh indices must be integers");
            indices[i] = value.getAsBigDecimal().intValueExact();
        }
        return indices;
    }
    private static String text(JsonObject json, String name, String fallback) {
        if (!json.has(name)) return fallback;
        JsonPrimitive value = json.getAsJsonPrimitive(name);
        if (!value.isString()) throw new IllegalArgumentException(name + " must be a string");
        return value.getAsString();
    }
}
