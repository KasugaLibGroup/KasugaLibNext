package lib.kasuga.shader;

/** Typed GLSL {@code mat2} expression. */
public final class Mat2Expr extends ShaderExpression {
    Mat2Expr(ShaderIr.Expression expression) {
        super(expression, ShaderType.MAT2);
    }

    /** Emits matrix-vector multiplication. */
    public Vec2Expr transform(Vec2Expr value) {
        return new Vec2Expr(binary(ShaderType.VEC2, "*", this, value));
    }

    /** Emits matrix-matrix multiplication. */
    public Mat2Expr mul(Mat2Expr right) {
        return new Mat2Expr(binary(type(), "*", this, right));
    }
}
