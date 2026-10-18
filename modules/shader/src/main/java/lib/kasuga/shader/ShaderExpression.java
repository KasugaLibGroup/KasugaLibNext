package lib.kasuga.shader;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public abstract class ShaderExpression {
    private final ShaderIr.Expression expression;

    ShaderExpression(ShaderIr.Expression expression, ShaderValueType expectedType) {
        this.expression = Objects.requireNonNull(expression, "expression");
        if (!expression.type().equals(expectedType)) {
            throw new IllegalArgumentException("Expected " + expectedType + " but got " + expression.type());
        }
    }

    public final ShaderValueType type() {
        return expression.type();
    }

    public final ShaderIr.Expression ir() {
        return expression;
    }

    static ShaderIr.Expression binary(ShaderValueType type, String operator,
                                      ShaderExpression left, ShaderExpression right) {
        return new ShaderIr.Binary(type, operator, left.ir(), right.ir());
    }

    static ShaderIr.Expression unary(ShaderValueType type, String operator, ShaderExpression operand) {
        return new ShaderIr.Unary(type, operator, operand.ir());
    }

    static ShaderIr.Expression call(ShaderValueType type, String name, ShaderExpression... arguments) {
        List<ShaderIr.Expression> nodes = Arrays.stream(arguments).map(ShaderExpression::ir).toList();
        return new ShaderIr.Call(type, name, nodes);
    }
}
