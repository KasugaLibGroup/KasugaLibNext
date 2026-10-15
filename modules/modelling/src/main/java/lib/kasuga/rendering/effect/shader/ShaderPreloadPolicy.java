package lib.kasuga.rendering.effect.shader;

/** Controls when a registered custom shader is compiled and linked. */
public enum ShaderPreloadPolicy {
    /** Compile during the normal resource reload, or enqueue immediately when registered later. */
    EAGER,
    /** Skip blocking resource reload and enqueue into the frame-budgeted scheduler. */
    DEFERRED,
    /** Do not enqueue automatically; callers must invoke a preload API explicitly. */
    MANUAL
}
