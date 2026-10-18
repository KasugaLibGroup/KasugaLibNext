package lib.kasuga.shader;

import java.util.Objects;

/** Imperative Java DSL for a graphics program's vertex stage. */
public final class VertexShaderBuilder extends ShaderStageBuilder {
    private final Vec4Expr position = new Vec4Expr(
            new ShaderIr.BuiltinReference(ShaderType.VEC4, ShaderIr.Builtin.GL_POSITION)
    );
    private boolean wrotePosition;

    VertexShaderBuilder() {
    }

    /** Writes GLSL's required {@code gl_Position} output. */
    public void position(Vec4Expr value) {
        assign(position, Objects.requireNonNull(value, "value"));
        wrotePosition = true;
    }

    public ShaderVariable<FloatExpr> outputFloat(String name) {
        return output(name, ShaderType.FLOAT, FloatExpr::new);
    }

    public ShaderVariable<Vec2Expr> outputVec2(String name) {
        return output(name, ShaderType.VEC2, Vec2Expr::new);
    }

    public ShaderVariable<Vec3Expr> outputVec3(String name) {
        return output(name, ShaderType.VEC3, Vec3Expr::new);
    }

    public ShaderVariable<Vec4Expr> outputVec4(String name) {
        return output(name, ShaderType.VEC4, Vec4Expr::new);
    }

    public void outputFloat(String name, FloatExpr value) {
        outputFloat(name).set(value);
    }

    public void outputVec2(String name, Vec2Expr value) {
        outputVec2(name).set(value);
    }

    public void outputVec3(String name, Vec3Expr value) {
        outputVec3(name).set(value);
    }

    public void outputVec4(String name, Vec4Expr value) {
        outputVec4(name).set(value);
    }

    ShaderModule build() {
        ensureOpen();
        if (!wrotePosition) throw new IllegalStateException("A vertex shader must write gl_Position");
        return finish();
    }
}
