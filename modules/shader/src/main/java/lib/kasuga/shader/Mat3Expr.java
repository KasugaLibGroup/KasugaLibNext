package lib.kasuga.shader;

/** Typed GLSL {@code mat3} expression. */
public final class Mat3Expr extends ShaderExpression {
    Mat3Expr(ShaderIr.Expression expression) {
        super(expression, ShaderType.MAT3);
    }

    /** Emits matrix-vector multiplication. */
    public Vec3Expr transform(Vec3Expr value) {
        return new Vec3Expr(binary(ShaderType.VEC3, "*", this, value));
    }

    /** Emits matrix-matrix multiplication. */
    public Mat3Expr mul(Mat3Expr right) {
        return new Mat3Expr(binary(type(), "*", this, right));
    }
}
