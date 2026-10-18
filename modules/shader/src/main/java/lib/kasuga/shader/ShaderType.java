package lib.kasuga.shader;

/** Value types supported by the first Kasuga shader profile. */
public enum ShaderType implements ShaderValueType {
    FLOAT("float"),
    INT("int"),
    BOOL("bool"),
    VEC2("vec2"),
    VEC3("vec3"),
    VEC4("vec4"),
    MAT2("mat2"),
    MAT3("mat3"),
    MAT4("mat4"),
    SAMPLER_2D("sampler2D");

    private final String glslName;

    ShaderType(String glslName) {
        this.glslName = glslName;
    }

    public String glslName() {
        return glslName;
    }
}
