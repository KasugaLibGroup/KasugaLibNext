package lib.kasuga.shader;

/** A built-in or nominal struct value type represented by the typed shader IR. */
public sealed interface ShaderValueType permits ShaderType, ShaderStructType {
    String glslName();
}
