package lib.kasuga.shader;

public class Vec3Expr extends ShaderExpression {
    Vec3Expr(ShaderIr.Expression expression) {
        super(expression, ShaderType.VEC3);
    }

    public Vec3Expr add(Vec3Expr right) { return new Vec3Expr(binary(type(), "+", this, right)); }
    public Vec3Expr sub(Vec3Expr right) { return new Vec3Expr(binary(type(), "-", this, right)); }
    public Vec3Expr mul(FloatExpr right) { return new Vec3Expr(binary(type(), "*", this, right)); }
    public Vec3Expr mul(float right) { return mul(FloatExpr.literal(right)); }
    public Vec3Expr mul(Vec3Expr right) { return new Vec3Expr(binary(type(), "*", this, right)); }
    public Vec3Expr div(FloatExpr right) { return new Vec3Expr(binary(type(), "/", this, right)); }
    public Vec3Expr div(Vec3Expr right) { return new Vec3Expr(binary(type(), "/", this, right)); }
    public Vec3Expr normalize() { return new Vec3Expr(call(type(), "normalize", this)); }
    public Vec3Expr max(Vec3Expr right) { return new Vec3Expr(call(type(), "max", this, right)); }
    public FloatExpr dot(Vec3Expr right) { return new FloatExpr(call(ShaderType.FLOAT, "dot", this, right)); }
    public FloatExpr length() { return new FloatExpr(call(ShaderType.FLOAT, "length", this)); }
    public FloatExpr x() { return component("x"); }
    public FloatExpr y() { return component("y"); }
    public FloatExpr z() { return component("z"); }

    private FloatExpr component(String name) {
        return new FloatExpr(new ShaderIr.Swizzle(ShaderType.FLOAT, ir(), name));
    }
}
