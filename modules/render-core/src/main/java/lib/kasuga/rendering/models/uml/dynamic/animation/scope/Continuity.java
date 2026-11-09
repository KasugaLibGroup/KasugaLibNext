package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/**
 * How a target combines the previous and current logical outputs during render sampling.
 */
public enum Continuity {
    INTERPOLATE,
    HOLD_CURRENT,
    SNAP
}
