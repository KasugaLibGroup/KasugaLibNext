package lib.kasuga.shader.compiler;

import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.ShaderProgramProvider;
import lib.kasuga.shader.backend.MinecraftGlsl150Backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Executes already-built Java shader definitions and writes their generated jar resources. */
public final class ShaderResourceCompiler {
    private ShaderResourceCompiler() {}

    public static CompilationResult compileProviders(
            Collection<? extends ShaderProgramProvider> providers,
            Path outputDirectory
    ) throws IOException {
        Objects.requireNonNull(providers, "providers");
        return compile(
                providers.stream().flatMap(provider -> provider.shaderPrograms().stream()).toList(),
                outputDirectory
        );
    }

    public static CompilationResult compile(
            Collection<ShaderProgram> programs,
            Path outputDirectory
    ) throws IOException {
        Objects.requireNonNull(programs, "programs");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Map<String, String> resources = new LinkedHashMap<>();
        for (ShaderProgram program : programs) {
            MinecraftGlsl150Backend.generate(Objects.requireNonNull(program, "program"))
                    .resources()
                    .forEach((path, source) -> {
                        if (resources.putIfAbsent(path, source) != null) {
                            throw new IllegalArgumentException("Duplicate generated shader resource: " + path);
                        }
                    });
        }

        for (Map.Entry<String, String> resource : resources.entrySet()) {
            Path output = outputDirectory.resolve(resource.getKey());
            Files.createDirectories(output.getParent());
            Files.writeString(output, resource.getValue(), StandardCharsets.UTF_8);
        }
        return new CompilationResult(programs.size(), resources.size(), Map.copyOf(resources));
    }

    public record CompilationResult(
            int programCount,
            int resourceCount,
            Map<String, String> resources
    ) {}
}
