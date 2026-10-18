package lib.kasuga.rendering.effect.shader;

public enum ShaderLoadState {
    REGISTERED,
    PREPARING,
    QUEUED,
    COMPILING,
    READY,
    FAILED,
    CLOSED
}
