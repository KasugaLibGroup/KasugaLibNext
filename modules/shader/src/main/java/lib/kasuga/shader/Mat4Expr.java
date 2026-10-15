package lib.kasuga.shader;

public final class Mat4Expr extends ShaderExpression {
    Mat4Expr(ShaderIr.Expression expression) {
        super(expression, ShaderType.MAT4);
    }

    public Vec4Expr transform(Vec4Expr value) {
        return new Vec4Expr(binary(ShaderType.VEC4, "*", this, value));
    }

    public Mat4Expr mul(Mat4Expr right) {
        return new Mat4Expr(binary(ShaderType.MAT4, "*", this, right));
    }
}
