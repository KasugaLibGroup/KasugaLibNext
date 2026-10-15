package lib.kasuga.rendering.effect.shader;

/** Defines whether one shader failure is local or aborts the resource reload. */
public enum ShaderFailurePolicy {
    /** Keep the shader unavailable and allow the rest of the render system to load. */
    DISABLE_PIPELINE,
    /** Abort the active resource reload when compilation fails. */
    FAIL_RELOAD
}
