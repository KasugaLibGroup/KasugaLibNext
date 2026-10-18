package lib.kasuga.shader;

public class IntExpr extends ShaderExpression {
    IntExpr(ShaderIr.Expression expression) {
        super(expression, ShaderType.INT);
    }

    public IntExpr add(IntExpr right) { return new IntExpr(binary(type(), "+", this, right)); }
    public IntExpr add(int right) { return add(literal(right)); }
    public IntExpr sub(IntExpr right) { return new IntExpr(binary(type(), "-", this, right)); }
    public IntExpr mul(IntExpr right) { return new IntExpr(binary(type(), "*", this, right)); }
    public IntExpr mul(int right) { return mul(literal(right)); }
    public IntExpr div(IntExpr right) { return new IntExpr(binary(type(), "/", this, right)); }
    public FloatExpr toFloat() {
        return new FloatExpr(new ShaderIr.Construct(ShaderType.FLOAT, java.util.List.of(ir())));
    }

    public BoolExpr lt(IntExpr right) { return compare("<", right); }
    public BoolExpr lte(IntExpr right) { return compare("<=", right); }
    public BoolExpr gt(IntExpr right) { return compare(">", right); }
    public BoolExpr gte(IntExpr right) { return compare(">=", right); }
    public BoolExpr equalTo(IntExpr right) { return compare("==", right); }

    private BoolExpr compare(String operator, IntExpr right) {
        return new BoolExpr(binary(ShaderType.BOOL, operator, this, right));
    }

    static IntExpr literal(int value) {
        return new IntExpr(new ShaderIr.Literal(ShaderType.INT, Integer.toString(value)));
    }
}
