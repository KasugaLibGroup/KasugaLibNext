package lib.kasuga.shader;

import java.util.Objects;
import java.util.Set;

final class ShaderNames {
    private static final Set<String> RESERVED = Set.of(
            "attribute", "const", "uniform", "varying", "layout", "centroid", "flat", "smooth",
            "noperspective", "break", "continue", "do", "for", "while", "switch", "case",
            "default", "if", "else", "subroutine", "in", "out", "inout", "float", "double",
            "int", "void", "bool", "true", "false", "invariant", "discard", "return", "mat2",
            "mat3", "mat4", "vec2", "vec3", "vec4", "sampler2D", "struct"
    );

    private ShaderNames() {}

    static String requireIdentifier(String value) {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*") || value.startsWith("gl_") || RESERVED.contains(value)) {
            throw new IllegalArgumentException("Invalid or reserved shader identifier: " + value);
        }
        return value;
    }
}
