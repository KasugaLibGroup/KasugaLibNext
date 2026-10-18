package lib.kasuga.shader;

public class Vec2Expr extends ShaderExpression {
    Vec2Expr(ShaderIr.Expression expression) {
        super(expression, ShaderType.VEC2);
    }

    public Vec2Expr add(Vec2Expr right) { return new Vec2Expr(binary(type(), "+", this, right)); }
    public Vec2Expr sub(Vec2Expr right) { return new Vec2Expr(binary(type(), "-", this, right)); }
    public Vec2Expr mul(FloatExpr right) { return new Vec2Expr(binary(type(), "*", this, right)); }
    public Vec2Expr mul(float right) { return mul(FloatExpr.literal(right)); }
    public Vec2Expr mul(Vec2Expr right) { return new Vec2Expr(binary(type(), "*", this, right)); }
    public Vec2Expr div(FloatExpr right) { return new Vec2Expr(binary(type(), "/", this, right)); }
    public Vec2Expr normalize() { return new Vec2Expr(call(type(), "normalize", this)); }
    public Vec2Expr clamp(Vec2Expr minimum, Vec2Expr maximum) {
        return new Vec2Expr(call(type(), "clamp", this, minimum, maximum));
    }
    public FloatExpr length() { return new FloatExpr(call(ShaderType.FLOAT, "length", this)); }
    public FloatExpr x() { return component("x"); }
    public FloatExpr y() { return component("y"); }

    private FloatExpr component(String name) {
        return new FloatExpr(new ShaderIr.Swizzle(ShaderType.FLOAT, ir(), name));
    }
}
