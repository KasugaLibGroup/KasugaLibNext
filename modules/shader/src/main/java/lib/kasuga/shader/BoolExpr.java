package lib.kasuga.shader;

public class BoolExpr extends ShaderExpression {
    BoolExpr(ShaderIr.Expression expression) {
        super(expression, ShaderType.BOOL);
    }

    public BoolExpr and(BoolExpr right) { return new BoolExpr(binary(type(), "&&", this, right)); }
    public BoolExpr or(BoolExpr right) { return new BoolExpr(binary(type(), "||", this, right)); }
    public BoolExpr not() { return new BoolExpr(unary(type(), "!", this)); }

    static BoolExpr literal(boolean value) {
        return new BoolExpr(new ShaderIr.Literal(ShaderType.BOOL, Boolean.toString(value)));
    }
}
