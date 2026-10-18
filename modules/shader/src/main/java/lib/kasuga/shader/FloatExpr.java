package lib.kasuga.shader;

public class FloatExpr extends ShaderExpression {
    FloatExpr(ShaderIr.Expression expression) {
        super(expression, ShaderType.FLOAT);
    }

    public FloatExpr add(FloatExpr right) { return new FloatExpr(binary(type(), "+", this, right)); }
    public FloatExpr add(float right) { return add(literal(right)); }
    public FloatExpr sub(FloatExpr right) { return new FloatExpr(binary(type(), "-", this, right)); }
    public FloatExpr sub(float right) { return sub(literal(right)); }
    public FloatExpr mul(FloatExpr right) { return new FloatExpr(binary(type(), "*", this, right)); }
    public FloatExpr mul(float right) { return mul(literal(right)); }
    public FloatExpr div(FloatExpr right) { return new FloatExpr(binary(type(), "/", this, right)); }
    public FloatExpr div(float right) { return div(literal(right)); }
    public FloatExpr negate() { return new FloatExpr(unary(type(), "-", this)); }

    public FloatExpr abs() { return function("abs"); }
    public FloatExpr sin() { return function("sin"); }
    public FloatExpr cos() { return function("cos"); }
    public FloatExpr exp() { return function("exp"); }
    public FloatExpr sqrt() { return function("sqrt"); }
    /** Emits atan(this, x), the two-argument GLSL arctangent. */
    public FloatExpr atan2(FloatExpr x) { return function("atan", x); }
    /** Emits step(edge, this). */
    public FloatExpr step(FloatExpr edge) { return new FloatExpr(call(type(), "step", edge, this)); }
    public FloatExpr max(FloatExpr right) { return function("max", right); }
    public FloatExpr max(float right) { return max(literal(right)); }
    public FloatExpr min(FloatExpr right) { return function("min", right); }
    public FloatExpr min(float right) { return min(literal(right)); }
    public FloatExpr clamp(FloatExpr minimum, FloatExpr maximum) { return function("clamp", minimum, maximum); }
    public FloatExpr smoothstep(FloatExpr edge0, FloatExpr edge1) {
        return new FloatExpr(call(type(), "smoothstep", edge0, edge1, this));
    }
    public FloatExpr mix(FloatExpr other, FloatExpr amount) { return function("mix", other, amount); }

    public BoolExpr lt(FloatExpr right) { return compare("<", right); }
    public BoolExpr lte(FloatExpr right) { return compare("<=", right); }
    public BoolExpr gt(FloatExpr right) { return compare(">", right); }
    public BoolExpr gte(FloatExpr right) { return compare(">=", right); }
    public BoolExpr equalTo(FloatExpr right) { return compare("==", right); }

    private FloatExpr function(String name, ShaderExpression... arguments) {
        ShaderExpression[] all = new ShaderExpression[arguments.length + 1];
        all[0] = this;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return new FloatExpr(call(type(), name, all));
    }

    private BoolExpr compare(String operator, FloatExpr right) {
        return new BoolExpr(binary(ShaderType.BOOL, operator, this, right));
    }

    static FloatExpr literal(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Shader float literal must be finite");
        String source = Float.toString(value);
        if (!source.contains(".") && !source.contains("E") && !source.contains("e")) source += ".0";
        return new FloatExpr(new ShaderIr.Literal(ShaderType.FLOAT, source));
    }
}
