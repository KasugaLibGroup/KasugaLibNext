package lib.kasuga.rendering.models.mc.source.model.assembly;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import lib.kasuga.rendering.models.mc.api.McModelAssemblies;
import lib.kasuga.rendering.models.uml.loaders.assembly.ModelAssemblyDefinition;
import lib.kasuga.utils.json.GsonBindings;
import lib.kasuga.utils.json.JsonFieldPolicy.NonNullFields;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.util.*;

/** Resource-pack JSON adapter; source model format selection stays in the existing resource pipelines. */
public final class AssemblyResourceDefinitions {
    private static final Gson GSON = GsonBindings.builder()
            .registerTypeAdapter(Boolean.class, new GsonBindings.BooleanAdapter())
            .registerTypeAdapter(int.class, new GsonBindings.IntegerAdapter())
            .create();
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
                output.put(id, build(GSON.fromJson(reader, Definition.class)));
            } catch (IOException | RuntimeException failure) {
                throw new IllegalArgumentException("Invalid model assembly resource " + location, failure);
            }
        }
        return Map.copyOf(output);
    }

    public static ModelAssemblyDefinition<McModelAssemblies.Reference> parse(JsonObject json) {
        return build(GSON.fromJson(Objects.requireNonNull(json), Definition.class));
    }

    private static ModelAssemblyDefinition<McModelAssemblies.Reference> build(Definition definition) {
        if (definition == null || definition.body == null) throw new IllegalArgumentException("assembly body required");
        Part body = definition.body;
        var builder = ModelAssemblyDefinition.builder(body.id == null ? "body" : body.id,
                reference(body), part -> configure(body, part));
        for (Part component : Objects.requireNonNull(definition.parts, "parts")) {
            builder.part(component.id, reference(component), part -> configure(component, part));
        }
        if (definition.bindTolerance != null) builder.bindTolerance(definition.bindTolerance);
        return builder.build();
    }

    private static McModelAssemblies.Reference reference(Part part) {
        if (part.model == null || part.model.isBlank()) throw new IllegalArgumentException("model resource required");
        return new McModelAssemblies.Reference(ResourceLocation.parse(part.model), part.modelName);
    }
    private static void configure(Part input, ModelAssemblyDefinition.PartBuilder part) {
        input.boneMappings.forEach(part::mapBone);
        input.regions.forEach(part::region);
        part.hideRegions(input.hideRegions);
        part.hideMeshes(input.hideMeshes);
        if (input.matchBodyBones != null) part.matchBodyBones(input.matchBodyBones);
        if (input.includeDynamics != null) part.includeDynamics(input.includeDynamics);
    }

    @NonNullFields
    private static final class Definition {
        Part body;
        List<Part> parts = List.of();
        Float bindTolerance;
    }

    @NonNullFields
    private static final class Part {
        @JsonAdapter(GsonBindings.StringAdapter.class) String id, model, modelName;
        Map<String, String> boneMappings = Map.of();
        Map<String, int[]> regions = Map.of();
        String[] hideRegions = new String[0];
        int[] hideMeshes = new int[0];
        Boolean matchBodyBones, includeDynamics;
    }
}
