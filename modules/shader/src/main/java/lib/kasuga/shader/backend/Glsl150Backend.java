package lib.kasuga.shader.backend;

import lib.kasuga.shader.ShaderGlobal;
import lib.kasuga.shader.ShaderIr;
import lib.kasuga.shader.ShaderModule;
import lib.kasuga.shader.ShaderStorage;
import lib.kasuga.shader.ShaderStructField;
import lib.kasuga.shader.ShaderStructType;

import java.util.Iterator;

/** Deterministic GLSL 1.50 emitter for the backend-neutral shader IR. */
public final class Glsl150Backend {
    private Glsl150Backend() {}

    public static String emitVertex(ShaderModule module) {
        return emitModule(module);
    }

    public static String emitFragment(ShaderModule module) {
        return emitModule(module);
    }

    private static String emitModule(ShaderModule module) {
        StringBuilder output = new StringBuilder("#version 150\n");
        for (String source : module.rawPreamble()) appendRaw(output, source, 0);
        output.append('\n');
        for (ShaderStructType type : module.structs()) {
            output.append("struct ").append(type.name()).append(" {\n");
            for (ShaderStructField<?> field : type.fields()) {
                output.append("    ").append(field.type().glslName()).append(' ')
                        .append(field.name()).append(";\n");
            }
            output.append("};\n");
        }
        if (!module.structs().isEmpty()) output.append('\n');
        for (ShaderGlobal global : module.globals()) {
            output.append(storage(global.storage()))
                    .append(global.type().glslName())
                    .append(' ')
                    .append(global.name());
            if (global.isArray()) output.append('[').append(global.arrayLength()).append(']');
            output.append(";\n");
        }
        if (!module.globals().isEmpty()) output.append('\n');
        for (String source : module.rawDeclarations()) appendRaw(output, source, 0);
        if (!module.rawDeclarations().isEmpty()) output.append('\n');
        output.append("void main() {\n");
        emitBlock(output, module.entryPoint(), 1);
        output.append("}\n");
        return output.toString();
    }

    public static String fullscreenVertex() {
        return """
                #version 150

                in vec3 Position;

                out vec2 texCoord;

                void main() {
                    vec2 screenPos = Position.xy * 2.0 - 1.0;
                    gl_Position = vec4(screenPos, 1.0, 1.0);
                    texCoord = Position.xy;
                }
                """;
    }

    private static String storage(ShaderStorage storage) {
        return switch (storage) {
            case UNIFORM, SAMPLER -> "uniform ";
            case INPUT -> "in ";
            case OUTPUT -> "out ";
        };
    }

    private static void emitBlock(StringBuilder output, ShaderIr.Block block, int indentation) {
        for (ShaderIr.Statement statement : block.statements()) {
            emitStatement(output, statement, indentation);
        }
    }

    private static void emitStatement(StringBuilder output, ShaderIr.Statement statement, int indentation) {
        if (statement instanceof ShaderIr.VariableDeclaration declaration) {
            indent(output, indentation);
            output.append(declaration.type().glslName()).append(' ').append(declaration.name())
                    .append(" = ");
            appendExpression(output, declaration.initializer());
            output.append(";\n");
        } else if (statement instanceof ShaderIr.Assignment assignment) {
            indent(output, indentation);
            appendExpression(output, assignment.target());
            output.append(" = ");
            appendExpression(output, assignment.value());
            output.append(";\n");
        } else if (statement instanceof ShaderIr.IfStatement conditional) {
            indent(output, indentation);
            output.append("if (");
            appendExpression(output, conditional.condition());
            output.append(") {\n");
            emitBlock(output, conditional.thenBlock(), indentation + 1);
            indent(output, indentation);
            if (conditional.elseBlock() == null) {
                output.append("}\n");
            } else {
                output.append("} else {\n");
                emitBlock(output, conditional.elseBlock(), indentation + 1);
                indent(output, indentation);
                output.append("}\n");
            }
        } else if (statement instanceof ShaderIr.ForRange loop) {
            indent(output, indentation);
            output.append("for (int ").append(loop.indexName()).append(" = ");
            appendExpression(output, loop.startInclusive());
            output.append("; ").append(loop.indexName()).append(" < ");
            appendExpression(output, loop.endExclusive());
            output.append("; ++").append(loop.indexName()).append(") {\n");
            emitBlock(output, loop.body(), indentation + 1);
            indent(output, indentation);
            output.append("}\n");
        } else if (statement instanceof ShaderIr.WhileLoop loop) {
            indent(output, indentation);
            output.append("while (");
            appendExpression(output, loop.condition());
            output.append(") {\n");
            emitBlock(output, loop.body(), indentation + 1);
            indent(output, indentation);
            output.append("}\n");
        } else if (statement instanceof ShaderIr.DoWhileLoop loop) {
            indent(output, indentation);
            output.append("do {\n");
            emitBlock(output, loop.body(), indentation + 1);
            indent(output, indentation);
            output.append("} while (");
            appendExpression(output, loop.condition());
            output.append(");\n");
        } else if (statement instanceof ShaderIr.SwitchStatement selection) {
            indent(output, indentation);
            output.append("switch (");
            appendExpression(output, selection.selector());
            output.append(") {\n");
            for (ShaderIr.SwitchCase shaderCase : selection.cases()) {
                indent(output, indentation + 1);
                output.append("case ").append(shaderCase.label()).append(":\n");
                emitBlock(output, shaderCase.body(), indentation + 2);
            }
            if (selection.defaultBlock() != null) {
                indent(output, indentation + 1);
                output.append("default:\n");
                emitBlock(output, selection.defaultBlock(), indentation + 2);
            }
            indent(output, indentation);
            output.append("}\n");
        } else if (statement == ShaderIr.BreakStatement.INSTANCE) {
            indent(output, indentation);
            output.append("break;\n");
        } else if (statement == ShaderIr.ContinueStatement.INSTANCE) {
            indent(output, indentation);
            output.append("continue;\n");
        } else if (statement instanceof ShaderIr.RawStatement raw) {
            appendRaw(output, raw.source(), indentation);
        } else {
            throw new IllegalArgumentException("Unsupported shader statement: " + statement);
        }
    }

    private static void appendExpression(StringBuilder output, ShaderIr.Expression expression) {
        if (expression instanceof ShaderIr.Literal literal) {
            output.append(literal.source());
        } else if (expression instanceof ShaderIr.Reference reference) {
            output.append(reference.name());
        } else if (expression instanceof ShaderIr.BuiltinReference reference) {
            output.append(reference.builtin().glslName());
        } else if (expression instanceof ShaderIr.Unary unary) {
            output.append('(').append(unary.operator());
            appendExpression(output, unary.operand());
            output.append(')');
        } else if (expression instanceof ShaderIr.Binary binary) {
            output.append('(');
            appendExpression(output, binary.left());
            output.append(' ').append(binary.operator()).append(' ');
            appendExpression(output, binary.right());
            output.append(')');
        } else if (expression instanceof ShaderIr.Call call) {
            output.append(call.function()).append('(');
            appendArguments(output, call.arguments().iterator());
            output.append(')');
        } else if (expression instanceof ShaderIr.Construct construct) {
            output.append(construct.type().glslName()).append('(');
            appendArguments(output, construct.arguments().iterator());
            output.append(')');
        } else if (expression instanceof ShaderIr.Index index) {
            appendExpression(output, index.array());
            output.append('[');
            appendExpression(output, index.index());
            output.append(']');
        } else if (expression instanceof ShaderIr.Swizzle swizzle) {
            output.append('(');
            appendExpression(output, swizzle.value());
            output.append(").").append(swizzle.components());
        } else if (expression instanceof ShaderIr.Member member) {
            output.append('(');
            appendExpression(output, member.value());
            output.append(").").append(member.member());
        } else if (expression instanceof ShaderIr.RawExpression raw) {
            output.append('(').append(raw.source()).append(')');
        } else {
            throw new IllegalArgumentException("Unsupported shader expression: " + expression);
        }
    }

    private static void appendArguments(
            StringBuilder output,
            Iterator<ShaderIr.Expression> arguments
    ) {
        boolean first = true;
        while (arguments.hasNext()) {
            if (!first) output.append(", ");
            appendExpression(output, arguments.next());
            first = false;
        }
    }

    private static void indent(StringBuilder output, int indentation) {
        output.append("    ".repeat(indentation));
    }

    private static void appendRaw(StringBuilder output, String source, int indentation) {
        String[] lines = source.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index == lines.length - 1 && lines[index].isEmpty()) continue;
            indent(output, indentation);
            output.append(lines[index]).append('\n');
        }
    }
}
