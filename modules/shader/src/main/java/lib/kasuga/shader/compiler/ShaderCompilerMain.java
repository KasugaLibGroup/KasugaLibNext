package lib.kasuga.shader.compiler;

import lib.kasuga.shader.ShaderProgramProvider;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Command-line entry point used by the Gradle plugin. */
public final class ShaderCompilerMain {
    private ShaderCompilerMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: ShaderCompilerMain <output-directory> <class-directories-path-list>"
            );
        }
        Path output = Path.of(args[0]);
        clearDirectory(output);
        Files.createDirectories(output);

        List<ShaderProgramProvider> providers = discoverProviders(args[1]);
        ShaderResourceCompiler.CompilationResult result =
                ShaderResourceCompiler.compileProviders(providers, output);
        System.out.println("Generated " + result.programCount() + " shader programs ("
                + result.resourceCount() + " resources) from " + providers.size() + " Java providers.");
    }

    static List<ShaderProgramProvider> discoverProviders(String classDirectories) throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        List<String> names = new ArrayList<>();
        for (String entry : classDirectories.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path root = Path.of(entry);
            if (!Files.isDirectory(root)) continue;
            try (var files = Files.walk(root)) {
                files.filter(path -> path.getFileName().toString().endsWith(".class"))
                        .map(path -> className(root, path))
                        .filter(name -> !name.equals("module-info") && !name.endsWith("package-info"))
                        .forEach(names::add);
            }
        }
        names.sort(String::compareTo);

        List<ShaderProgramProvider> providers = new ArrayList<>();
        for (String name : names) {
            Class<?> type = Class.forName(name, false, loader);
            if (!ShaderProgramProvider.class.isAssignableFrom(type)
                    || type.isInterface() || Modifier.isAbstract(type.getModifiers())) continue;
            var constructor = type.getDeclaredConstructor();
            if (!Modifier.isPublic(type.getModifiers()) || !Modifier.isPublic(constructor.getModifiers())) {
                throw new IllegalArgumentException("Shader provider and its no-arg constructor must be public: " + name);
            }
            providers.add((ShaderProgramProvider) constructor.newInstance());
        }
        return List.copyOf(providers);
    }

    private static String className(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString();
        return relative.substring(0, relative.length() - ".class".length())
                .replace(java.io.File.separatorChar, '.');
    }

    private static void clearDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
