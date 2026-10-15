package lib.kasuga.shader;

public class Vec4Expr extends ShaderExpression {
    Vec4Expr(ShaderIr.Expression expression) {
        super(expression, ShaderType.VEC4);
    }

    public Vec4Expr add(Vec4Expr right) { return new Vec4Expr(binary(type(), "+", this, right)); }
    public Vec4Expr mul(FloatExpr right) { return new Vec4Expr(binary(type(), "*", this, right)); }
    public Vec4Expr mul(Vec4Expr right) { return new Vec4Expr(binary(type(), "*", this, right)); }
    public FloatExpr x() { return component("x"); }
    public FloatExpr y() { return component("y"); }
    public FloatExpr z() { return component("z"); }
    public FloatExpr w() { return component("w"); }
    public FloatExpr r() { return component("r"); }
    public FloatExpr g() { return component("g"); }
    public FloatExpr b() { return component("b"); }
    public FloatExpr a() { return component("a"); }
    public Vec3Expr rgb() { return new Vec3Expr(new ShaderIr.Swizzle(ShaderType.VEC3, ir(), "rgb")); }

    private FloatExpr component(String name) {
        return new FloatExpr(new ShaderIr.Swizzle(ShaderType.FLOAT, ir(), name));
    }
}
