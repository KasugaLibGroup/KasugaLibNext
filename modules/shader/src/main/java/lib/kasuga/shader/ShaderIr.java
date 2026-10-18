package lib.kasuga.shader;

import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/** Backend-neutral typed intermediate representation produced directly by the Java DSL. */
public final class ShaderIr {
    private ShaderIr() {}

    public sealed interface Expression permits Literal, Reference, BuiltinReference, Unary, Binary, Call,
            Construct, Index, Swizzle, Member, RawExpression {
        ShaderValueType type();
    }

    public record BuiltinReference(ShaderType type, Builtin builtin) implements Expression {
        public BuiltinReference {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(builtin, "builtin");
            if (builtin.type() != type) {
                throw new IllegalArgumentException("Built-in type mismatch: " + builtin + " is " + builtin.type());
            }
        }
    }

    public enum Builtin {
        GL_POSITION("gl_Position", ShaderType.VEC4);

        private final String glslName;
        private final ShaderType type;

        Builtin(String glslName, ShaderType type) {
            this.glslName = glslName;
            this.type = type;
        }

        public String glslName() { return glslName; }
        public ShaderType type() { return type; }
    }

    public record Literal(ShaderValueType type, String source) implements Expression {
        public Literal {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(source, "source");
        }
    }

    public record Reference(ShaderValueType type, String name) implements Expression {
        public Reference {
            Objects.requireNonNull(type, "type");
            ShaderNames.requireIdentifier(name);
        }
    }

    public record Unary(ShaderValueType type, String operator, Expression operand) implements Expression {
        public Unary {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
        }
    }

    public record Binary(ShaderValueType type, String operator, Expression left, Expression right)
            implements Expression {
        public Binary {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    public record Call(ShaderValueType type, String function, List<Expression> arguments) implements Expression {
        public Call {
            Objects.requireNonNull(type, "type");
            ShaderNames.requireIdentifier(function);
            arguments = List.copyOf(arguments);
        }
    }

    public record Construct(ShaderValueType type, List<Expression> arguments) implements Expression {
        public Construct {
            Objects.requireNonNull(type, "type");
            arguments = List.copyOf(arguments);
        }
    }

    public record Index(ShaderValueType type, Expression array, Expression index) implements Expression {
        public Index {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(array, "array");
            Objects.requireNonNull(index, "index");
        }
    }

    public record Swizzle(ShaderValueType type, Expression value, String components) implements Expression {
        public Swizzle {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
            if (!components.matches("[xyzwrgba]{1,4}")) {
                throw new IllegalArgumentException("Invalid swizzle: " + components);
            }
        }
    }

    public record Member(ShaderValueType type, Expression value, String member) implements Expression {
        public Member {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
            ShaderNames.requireIdentifier(member);
        }
    }

    @DelicateShaderApi
    public record RawExpression(ShaderValueType type, String source) implements Expression {
        public RawExpression {
            Objects.requireNonNull(type, "type");
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("Raw shader expression cannot be blank");
            }
        }
    }

    public sealed interface Statement permits VariableDeclaration, Assignment, IfStatement, ForRange,
            WhileLoop, DoWhileLoop, SwitchStatement, BreakStatement, ContinueStatement, RawStatement {}

    public record Block(List<Statement> statements) {
        public Block {
            statements = List.copyOf(statements);
        }
    }

    public record VariableDeclaration(ShaderValueType type, String name, Expression initializer)
            implements Statement {
        public VariableDeclaration {
            Objects.requireNonNull(type, "type");
            ShaderNames.requireIdentifier(name);
            Objects.requireNonNull(initializer, "initializer");
            if (!type.equals(initializer.type())) {
                throw new IllegalArgumentException("Variable initializer type mismatch: "
                        + type + " <- " + initializer.type());
            }
        }
    }

    public record Assignment(Expression target, Expression value) implements Statement {
        public Assignment {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(value, "value");
            if (!(target instanceof Reference || target instanceof BuiltinReference
                    || target instanceof Index || target instanceof Swizzle || target instanceof Member)) {
                throw new IllegalArgumentException("Assignment target is not writable");
            }
            if (!target.type().equals(value.type())) {
                throw new IllegalArgumentException("Assignment type mismatch: "
                        + target.type() + " <- " + value.type());
            }
        }
    }

    public record IfStatement(Expression condition, Block thenBlock, Block elseBlock) implements Statement {
        public IfStatement {
            Objects.requireNonNull(condition, "condition");
            if (condition.type() != ShaderType.BOOL) {
                throw new IllegalArgumentException("If condition must be bool");
            }
            Objects.requireNonNull(thenBlock, "thenBlock");
        }
    }

    public record ForRange(String indexName, Expression startInclusive, Expression endExclusive, Block body)
            implements Statement {
        public ForRange {
            ShaderNames.requireIdentifier(indexName);
            Objects.requireNonNull(startInclusive, "startInclusive");
            Objects.requireNonNull(endExclusive, "endExclusive");
            Objects.requireNonNull(body, "body");
            if (startInclusive.type() != ShaderType.INT || endExclusive.type() != ShaderType.INT) {
                throw new IllegalArgumentException("For-range bounds must be int");
            }
        }
    }

    public record WhileLoop(Expression condition, Block body) implements Statement {
        public WhileLoop {
            requireBooleanCondition(condition, "While");
            Objects.requireNonNull(body, "body");
        }
    }

    public record DoWhileLoop(Block body, Expression condition) implements Statement {
        public DoWhileLoop {
            Objects.requireNonNull(body, "body");
            requireBooleanCondition(condition, "Do-while");
        }
    }

    public record SwitchCase(int label, Block body) {
        public SwitchCase {
            Objects.requireNonNull(body, "body");
        }
    }

    public record SwitchStatement(Expression selector, List<SwitchCase> cases, Block defaultBlock)
            implements Statement {
        public SwitchStatement {
            Objects.requireNonNull(selector, "selector");
            if (selector.type() != ShaderType.INT) {
                throw new IllegalArgumentException("Switch selector must be int");
            }
            cases = List.copyOf(cases);
            Set<Integer> labels = new HashSet<>();
            for (SwitchCase shaderCase : cases) {
                if (!labels.add(shaderCase.label())) {
                    throw new IllegalArgumentException("Duplicate shader switch case: " + shaderCase.label());
                }
            }
        }
    }

    @DelicateShaderApi
    public record RawStatement(String source) implements Statement {
        public RawStatement {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("Raw shader statement cannot be blank");
            }
        }
    }

    public enum BreakStatement implements Statement {
        INSTANCE
    }

    public enum ContinueStatement implements Statement {
        INSTANCE
    }

    private static void requireBooleanCondition(Expression condition, String statement) {
        Objects.requireNonNull(condition, "condition");
        if (condition.type() != ShaderType.BOOL) {
            throw new IllegalArgumentException(statement + " condition must be bool");
        }
    }
}
