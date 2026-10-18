package lib.kasuga.shader;

/** User-facing value kinds supported by an exposed shader parameter. */
public enum ShaderParameterType {
    FLOAT(ShaderType.FLOAT, 1, false),
    INTEGER(ShaderType.INT, 1, true),
    BOOLEAN(ShaderType.BOOL, 1, true),
    VEC2(ShaderType.VEC2, 2, false),
    VEC3(ShaderType.VEC3, 3, false),
    VEC4(ShaderType.VEC4, 4, false),
    COLOR_RGB(ShaderType.VEC3, 3, false),
    COLOR_RGBA(ShaderType.VEC4, 4, false),
    MAT2(ShaderType.MAT2, 4, false),
    MAT3(ShaderType.MAT3, 9, false),
    MAT4(ShaderType.MAT4, 16, false);

    private final ShaderType shaderType;
    private final int componentCount;
    private final boolean integral;

    ShaderParameterType(ShaderType shaderType, int componentCount, boolean integral) {
        this.shaderType = shaderType;
        this.componentCount = componentCount;
        this.integral = integral;
    }

    public ShaderType shaderType() {
        return shaderType;
    }

    public int componentCount() {
        return componentCount;
    }

    public boolean integral() {
        return integral;
    }
}
