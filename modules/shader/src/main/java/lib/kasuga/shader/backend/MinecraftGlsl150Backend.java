package lib.kasuga.shader.backend;

import lib.kasuga.shader.ShaderGlobal;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.ShaderStorage;
import lib.kasuga.shader.ShaderType;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Emits GLSL 1.50 plus Minecraft's core-shader JSON metadata. */
public final class MinecraftGlsl150Backend {
    private MinecraftGlsl150Backend() {}

    public static MinecraftShaderBundle generate(ShaderProgram program) {
        Objects.requireNonNull(program, "program");
        String vertexSource = switch (program.kind()) {
            case FULLSCREEN -> Glsl150Backend.fullscreenVertex();
            case GRAPHICS -> Glsl150Backend.emitVertex(Objects.requireNonNull(program.vertexModule()));
        };
        return new MinecraftShaderBundle(
                program,
                vertexSource,
                Glsl150Backend.emitFragment(program.fragmentModule()),
                emitJson(program)
        );
    }

    private static String emitJson(ShaderProgram program) {
        List<ShaderGlobal> resources = linkedResources(program);
        List<ShaderGlobal> samplers = resources.stream()
                .filter(global -> global.storage() == ShaderStorage.SAMPLER)
                .toList();
        List<ShaderGlobal> uniforms = resources.stream()
                .filter(global -> global.storage() == ShaderStorage.UNIFORM)
                .toList();

        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"vertex\": \"").append(program.id()).append("\",\n")
                .append("  \"fragment\": \"").append(program.id()).append("\",\n")
                .append("  \"samplers\": [");
        if (!samplers.isEmpty()) json.append('\n');
        for (int index = 0; index < samplers.size(); index++) {
            json.append("    { \"name\": \"").append(samplers.get(index).name()).append("\" }");
            json.append(index + 1 == samplers.size() ? '\n' : ",\n");
        }
        json.append("  ],\n  \"uniforms\": [");
        if (!uniforms.isEmpty()) json.append('\n');
        for (int index = 0; index < uniforms.size(); index++) {
            ShaderGlobal uniform = uniforms.get(index);
            UniformMetadata metadata = metadata(uniform);
            json.append("    { \"name\": \"").append(uniform.name())
                    .append("\", \"type\": \"").append(metadata.type)
                    .append("\", \"count\": ").append(metadata.count)
                    .append(", \"values\": [ ");
            List<Number> defaults = uniform.defaultValues().isEmpty()
                    ? List.of(0) : uniform.defaultValues();
            for (int value = 0; value < defaults.size(); value++) {
                if (value > 0) json.append(", ");
                json.append(number(defaults.get(value), metadata.type));
            }
            json.append(" ] }");
            json.append(index + 1 == uniforms.size() ? '\n' : ",\n");
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    private static List<ShaderGlobal> linkedResources(ShaderProgram program) {
        Map<String, ShaderGlobal> resources = new LinkedHashMap<>();
        if (program.vertexModule() != null) mergeResources(resources, program.vertexModule().globals());
        mergeResources(resources, program.fragmentModule().globals());
        return List.copyOf(resources.values());
    }

    private static void mergeResources(Map<String, ShaderGlobal> resources, List<ShaderGlobal> globals) {
        for (ShaderGlobal global : globals) {
            if (global.storage() != ShaderStorage.UNIFORM && global.storage() != ShaderStorage.SAMPLER) continue;
            ShaderGlobal existing = resources.putIfAbsent(global.name(), global);
            if (existing != null && !existing.equals(global)) {
                throw new IllegalArgumentException("Shader resource '" + global.name()
                        + "' has incompatible declarations across stages");
            }
        }
    }

    private static UniformMetadata metadata(ShaderGlobal uniform) {
        if (!(uniform.type() instanceof ShaderType type)) {
            throw new IllegalArgumentException("Struct uniforms are not supported by Minecraft metadata: "
                    + uniform.name());
        }
        if (uniform.isArray()) {
            if (type != ShaderType.FLOAT) {
                throw new IllegalArgumentException("Only float uniform arrays are supported");
            }
            return new UniformMetadata("float", uniform.arrayLength());
        }
        return switch (type) {
            case FLOAT -> new UniformMetadata("float", 1);
            case INT, BOOL -> new UniformMetadata("int", 1);
            case VEC2 -> new UniformMetadata("float", 2);
            case VEC3 -> new UniformMetadata("float", 3);
            case VEC4 -> new UniformMetadata("float", 4);
            case MAT2 -> new UniformMetadata("matrix2x2", 4);
            case MAT3 -> new UniformMetadata("matrix3x3", 9);
            case MAT4 -> new UniformMetadata("matrix4x4", 16);
            case SAMPLER_2D -> throw new IllegalArgumentException("Sampler cannot be emitted as a uniform");
        };
    }

    private static String number(Number value, String type) {
        if (type.equals("int")) return Integer.toString(value.intValue());
        float number = value.floatValue();
        if (!Float.isFinite(number)) throw new IllegalArgumentException("Uniform default must be finite");
        String source = Float.toString(number);
        if (!source.contains(".") && !source.contains("e") && !source.contains("E")) source += ".0";
        return source;
    }

    private record UniformMetadata(String type, int count) {}
}
