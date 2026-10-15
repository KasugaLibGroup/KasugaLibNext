package lib.kasuga.shader;

public final class FloatArrayExpr extends ShaderExpression {
    private final int length;

    FloatArrayExpr(ShaderIr.Expression expression, int length) {
        super(expression, ShaderType.FLOAT);
        if (length <= 1) throw new IllegalArgumentException("Array length must be greater than one");
        this.length = length;
    }

    public int length() {
        return length;
    }

    public FloatExpr get(IntExpr index) {
        return new FloatExpr(new ShaderIr.Index(ShaderType.FLOAT, ir(), index.ir()));
    }
}
