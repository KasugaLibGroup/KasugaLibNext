package lib.kasuga.shader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Immutable nominal GLSL struct declaration with typed field keys. */
public final class ShaderStructType implements ShaderValueType {
    private final Object identity;
    private final String name;
    private final List<ShaderStructField<?>> fields;

    private ShaderStructType(Object identity, String name, List<ShaderStructField<?>> fields) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.name = ShaderNames.requireIdentifier(name);
        if (fields.isEmpty()) throw new IllegalArgumentException("Shader struct must declare at least one field");
        this.fields = List.copyOf(fields);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    @Override
    public String glslName() {
        return name;
    }

    public List<ShaderStructField<?>> fields() {
        return fields;
    }

    public boolean contains(ShaderStructField<?> field) {
        return Objects.requireNonNull(field, "field").belongsTo(identity);
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof ShaderStructType other
                && name.equals(other.name)
                && fields.equals(other.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, fields);
    }

    @Override
    public String toString() {
        return name;
    }

    public static final class Builder {
        private final Object identity = new Object();
        private final String name;
        private final List<ShaderStructField<?>> fields = new ArrayList<>();
        private final Set<String> names = new HashSet<>();
        private boolean built;

        private Builder(String name) {
            this.name = ShaderNames.requireIdentifier(name);
        }

        public ShaderStructField<FloatExpr> floatField(String fieldName) {
            return add(fieldName, ShaderType.FLOAT, FloatExpr::new);
        }

        public ShaderStructField<IntExpr> intField(String fieldName) {
            return add(fieldName, ShaderType.INT, IntExpr::new);
        }

        public ShaderStructField<BoolExpr> boolField(String fieldName) {
            return add(fieldName, ShaderType.BOOL, BoolExpr::new);
        }

        public ShaderStructField<Vec2Expr> vec2Field(String fieldName) {
            return add(fieldName, ShaderType.VEC2, Vec2Expr::new);
        }

        public ShaderStructField<Vec3Expr> vec3Field(String fieldName) {
            return add(fieldName, ShaderType.VEC3, Vec3Expr::new);
        }

        public ShaderStructField<Vec4Expr> vec4Field(String fieldName) {
            return add(fieldName, ShaderType.VEC4, Vec4Expr::new);
        }

        public ShaderStructField<Mat2Expr> mat2Field(String fieldName) {
            return add(fieldName, ShaderType.MAT2, Mat2Expr::new);
        }

        public ShaderStructField<Mat3Expr> mat3Field(String fieldName) {
            return add(fieldName, ShaderType.MAT3, Mat3Expr::new);
        }

        public ShaderStructField<Mat4Expr> mat4Field(String fieldName) {
            return add(fieldName, ShaderType.MAT4, Mat4Expr::new);
        }

        public ShaderStructField<StructExpr> structField(String fieldName, ShaderStructType type) {
            Objects.requireNonNull(type, "type");
            return add(fieldName, type, expression -> new StructExpr(expression, type));
        }

        public ShaderStructType build() {
            ensureOpen();
            built = true;
            return new ShaderStructType(identity, name, fields);
        }

        private <T extends ShaderExpression> ShaderStructField<T> add(
                String fieldName,
                ShaderValueType type,
                Function<ShaderIr.Expression, T> wrapper
        ) {
            ensureOpen();
            String checkedName = ShaderNames.requireIdentifier(fieldName);
            if (!names.add(checkedName)) {
                throw new IllegalArgumentException("Duplicate shader struct field: " + checkedName);
            }
            ShaderStructField<T> field = new ShaderStructField<>(identity, checkedName, type, wrapper);
            fields.add(field);
            return field;
        }

        private void ensureOpen() {
            if (built) throw new IllegalStateException("Shader struct builder has already been built: " + name);
        }
    }
}
