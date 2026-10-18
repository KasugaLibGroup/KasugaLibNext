package lib.kasuga.shader;

public final class Sampler2DExpr extends ShaderExpression {
    Sampler2DExpr(ShaderIr.Expression expression) {
        super(expression, ShaderType.SAMPLER_2D);
    }

    public Vec4Expr sample(Vec2Expr uv) {
        return new Vec4Expr(call(ShaderType.VEC4, "texture", this, uv));
    }
}
