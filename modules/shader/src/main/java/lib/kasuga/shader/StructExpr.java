package lib.kasuga.shader;

import java.util.Objects;

/** Expression whose value has one declared nominal struct type. */
public final class StructExpr extends ShaderExpression {
    private final ShaderStructType structType;

    StructExpr(ShaderIr.Expression expression, ShaderStructType structType) {
        super(expression, Objects.requireNonNull(structType, "structType"));
        this.structType = structType;
    }

    public ShaderStructType structType() {
        return structType;
    }

    public <T extends ShaderExpression> T field(ShaderStructField<T> field) {
        Objects.requireNonNull(field, "field");
        if (!structType.contains(field)) {
            throw new IllegalArgumentException("Field " + field.name()
                    + " does not belong to struct " + structType.name());
        }
        return field.wrap(new ShaderIr.Member(field.type(), ir(), field.name()));
    }
}
