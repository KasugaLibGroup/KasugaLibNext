package lib.kasuga.shader.showcase;

import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.compiler.ShaderResourceCompiler;
import lib.kasuga.shader.graal.GraalShaderCompiler;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Local demonstration exporter shared by the three real language hosts. */
public final class ShowcaseExport {
    private ShowcaseExport() {}

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args[1]);
        write(new GraalShaderCompiler().compile(Path.of(args[0])), output.resolve("javascript"));
        Files.writeString(output.resolve("jvm-classpath.txt"), System.getProperty("java.class.path"));
    }

    public static void write(ShaderProgram program, Path output) throws IOException {
        var result = ShaderResourceCompiler.compile(List.of(program), output);
        Files.writeString(output.resolve("program.ir.txt"),
                "ShaderProgram " + program.id() + " " + program.kind() + "\n"
                        + "Parameters: " + program.parameterSchema().parameters() + "\n"
                        + tree(program.fragmentModule(), 0) + "\n");
        if (result.resourceCount() != 3) throw new IllegalStateException("Expected exactly three shader resources");
        System.out.println("SHOWCASE_EXPORT " + output + " (typed IR + 3 resources)");
    }

    private static String tree(Object value, int depth) {
        if (value == null) return "null";
        String indent = "  ".repeat(depth);
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return "[]";
            return "[\n" + String.join(",\n", list.stream()
                    .map(item -> indent + "  " + tree(item, depth + 1)).toList()) + "\n" + indent + "]";
        }
        if (!value.getClass().isRecord()) return value.toString();
        var fields = new java.util.ArrayList<String>();
        for (var field : value.getClass().getRecordComponents()) {
            try {
                fields.add(indent + "  " + field.getName() + ": "
                        + tree(field.getAccessor().invoke(value), depth + 1));
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalStateException("Cannot describe typed IR", exception);
            }
        }
        return value.getClass().getSimpleName() + " {\n" + String.join(",\n", fields) + "\n" + indent + "}";
    }
}
