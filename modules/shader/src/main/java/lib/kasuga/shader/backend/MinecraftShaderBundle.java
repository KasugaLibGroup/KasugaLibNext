package lib.kasuga.shader.backend;

import lib.kasuga.shader.ShaderProgram;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record MinecraftShaderBundle(
        ShaderProgram program,
        String vertexSource,
        String fragmentSource,
        String programJson
) {
    public MinecraftShaderBundle {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(vertexSource, "vertexSource");
        Objects.requireNonNull(fragmentSource, "fragmentSource");
        Objects.requireNonNull(programJson, "programJson");
    }

    /** Resource paths and contents ready to place in a jar. */
    public Map<String, String> resources() {
        String base = "assets/" + program.namespace() + "/shaders/core/" + program.path();
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put(base + ".vsh", vertexSource);
        resources.put(base + ".fsh", fragmentSource);
        resources.put(base + ".json", programJson);
        return Map.copyOf(resources);
    }
}
