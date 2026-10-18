package lib.kasuga.rendering.models.uml.dynamic.morph;

/** Blend mode for combining morph deltas onto original values. */
public enum BlendMode {
    /** delta × original (multiplicative) */
    MULTIPLY,
    /** original + delta (additive) */
    ADD
}
