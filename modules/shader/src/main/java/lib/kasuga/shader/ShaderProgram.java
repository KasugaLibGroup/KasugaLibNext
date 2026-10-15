package lib.kasuga.shader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** One resource-identified fullscreen or vertex/fragment graphics shader program. */
public record ShaderProgram(
        String id,
        Kind kind,
        ShaderModule vertexModule,
        ShaderModule fragmentModule,
        ShaderParameterSchema parameterSchema
) {
    public ShaderProgram {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fragmentModule, "fragmentModule");
        Objects.requireNonNull(parameterSchema, "parameterSchema");
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Shader ID must be namespace:path: " + id);
        }
        if (kind == Kind.FULLSCREEN && vertexModule != null) {
            throw new IllegalArgumentException("Fullscreen programs use the built-in fullscreen vertex stage");
        }
        if (kind == Kind.GRAPHICS && vertexModule == null) {
            throw new IllegalArgumentException("Graphics programs require a vertex module");
        }
        if (kind == Kind.GRAPHICS) validateStageInterface(vertexModule, fragmentModule);
        validateLinkedResources(vertexModule, fragmentModule);
        validateExposedParameters(vertexModule, fragmentModule, parameterSchema);
    }

    public ShaderProgram(String id, Kind kind, ShaderModule vertexModule, ShaderModule fragmentModule) {
        this(id, kind, vertexModule, fragmentModule, ShaderParameterSchema.empty());
    }

    /** Source-compatible constructor for the original fullscreen-only program representation. */
    public ShaderProgram(String id, Kind kind, ShaderModule fragmentModule) {
        this(id, kind, null, fragmentModule, ShaderParameterSchema.empty());
    }

    public static ShaderProgram fullscreen(String id, Consumer<FragmentShaderBuilder> definition) {
        Objects.requireNonNull(definition, "definition");
        FragmentShaderBuilder builder = new FragmentShaderBuilder(true);
        definition.accept(builder);
        ShaderModule fragment = builder.build();
        return new ShaderProgram(
                id, Kind.FULLSCREEN, null, fragment,
                ShaderParameterSchema.of(builder.exposedParameters())
        );
    }

    public static ShaderProgram graphics(
            String id,
            Consumer<VertexShaderBuilder> vertexDefinition,
            Consumer<FragmentShaderBuilder> fragmentDefinition
    ) {
        Objects.requireNonNull(vertexDefinition, "vertexDefinition");
        Objects.requireNonNull(fragmentDefinition, "fragmentDefinition");
        VertexShaderBuilder vertex = new VertexShaderBuilder();
        FragmentShaderBuilder fragment = new FragmentShaderBuilder(false);
        vertexDefinition.accept(vertex);
        fragmentDefinition.accept(fragment);
        ShaderModule vertexModule = vertex.build();
        ShaderModule fragmentModule = fragment.build();
        List<ShaderParameter> exposed = new java.util.ArrayList<>(vertex.exposedParameters());
        exposed.addAll(fragment.exposedParameters());
        return new ShaderProgram(
                id, Kind.GRAPHICS, vertexModule, fragmentModule,
                ShaderParameterSchema.of(exposed)
        );
    }

    private static void validateStageInterface(ShaderModule vertex, ShaderModule fragment) {
        Map<String, ShaderGlobal> outputs = new LinkedHashMap<>();
        for (ShaderGlobal global : vertex.globals()) {
            if (global.storage() == ShaderStorage.OUTPUT) outputs.put(global.name(), global);
        }
        for (ShaderGlobal input : fragment.globals()) {
            if (input.storage() != ShaderStorage.INPUT) continue;
            ShaderGlobal output = outputs.get(input.name());
            if (output == null) {
                throw new IllegalArgumentException("Fragment input '" + input.name()
                        + "' has no matching vertex output");
            }
            if (!output.type().equals(input.type())) {
                throw new IllegalArgumentException("Stage interface type mismatch for '" + input.name()
                        + "': vertex=" + output.type() + ", fragment=" + input.type());
            }
        }
    }

    private static void validateLinkedResources(ShaderModule vertex, ShaderModule fragment) {
        Map<String, ShaderGlobal> resources = new LinkedHashMap<>();
        if (vertex != null) collectLinkedResources(resources, vertex);
        collectLinkedResources(resources, fragment);
    }

    private static void collectLinkedResources(Map<String, ShaderGlobal> resources, ShaderModule module) {
        for (ShaderGlobal global : module.globals()) {
            if (global.storage() != ShaderStorage.UNIFORM && global.storage() != ShaderStorage.SAMPLER) continue;
            ShaderGlobal existing = resources.putIfAbsent(global.name(), global);
            if (existing != null && !existing.equals(global)) {
                throw new IllegalArgumentException("Shader resource '" + global.name()
                        + "' has incompatible declarations across stages");
            }
        }
    }

    private static void validateExposedParameters(
            ShaderModule vertex,
            ShaderModule fragment,
            ShaderParameterSchema schema
    ) {
        Map<String, ShaderGlobal> resources = new LinkedHashMap<>();
        if (vertex != null) collectLinkedResources(resources, vertex);
        collectLinkedResources(resources, fragment);
        for (ShaderParameter parameter : schema.parameters()) {
            ShaderGlobal global = resources.get(parameter.name());
            if (global == null || global.storage() != ShaderStorage.UNIFORM || global.isArray()) {
                throw new IllegalArgumentException("Exposed shader parameter is not a scalar/vector/matrix uniform: "
                        + parameter.name());
            }
            if (global.type() != parameter.type().shaderType()) {
                throw new IllegalArgumentException("Exposed shader parameter type mismatch for '"
                        + parameter.name() + "': parameter=" + parameter.type()
                        + ", uniform=" + global.type());
            }
        }
    }

    public String namespace() {
        return id.substring(0, id.indexOf(':'));
    }

    public String path() {
        return id.substring(id.indexOf(':') + 1);
    }

    public enum Kind {
        FULLSCREEN,
        GRAPHICS
    }
}
