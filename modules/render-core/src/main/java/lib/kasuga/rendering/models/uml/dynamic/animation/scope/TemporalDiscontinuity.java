package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/** Why interpolation across two temporal steps must not be treated as continuous. */
public enum TemporalDiscontinuity {
    NONE,
    CONTROL,
    SEEK
}
