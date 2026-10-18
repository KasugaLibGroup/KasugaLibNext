package lib.kasuga.shader;

import java.util.Objects;

/** Imperative Java DSL that directly records a typed fragment-shader IR. */
public final class FragmentShaderBuilder extends ShaderStageBuilder {
    private static final String TEX_COORD = "texCoord";
    private static final String FRAGMENT_COLOR = "fragColor";

    private final Vec2Expr texCoord;
    private final Vec4Expr fragmentColor;
    private boolean wroteFragmentColor;

    FragmentShaderBuilder(boolean fullscreen) {
        texCoord = fullscreen ? inputVec2(TEX_COORD) : null;
        fragmentColor = output(FRAGMENT_COLOR, ShaderType.VEC4, Vec4Expr::new).get();
    }

    /** Built-in texture coordinate supplied by {@link ShaderProgram#fullscreen}. */
    public Vec2Expr texCoord() {
        if (texCoord == null) {
            throw new IllegalStateException("Graphics fragment shaders must declare their own stage inputs");
        }
        return texCoord;
    }

    public void fragmentColor(Vec4Expr value) {
        assign(fragmentColor, Objects.requireNonNull(value, "value"));
        wroteFragmentColor = true;
    }

    ShaderModule build() {
        ensureOpen();
        if (!wroteFragmentColor) {
            throw new IllegalStateException("A fragment shader must write fragColor");
        }
        return finish();
    }
}
