package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/**
 * Inputs available while one model writes its animation slots for a render frame.
 */
public record RenderSampleContext(
        RenderFrameToken frameToken,
        double interpolationAlpha,
        FrameInputSnapshot frameInputs) {

    public RenderSampleContext {
        Objects.requireNonNull(frameToken, "frameToken");
        if (!Double.isFinite(interpolationAlpha)
                || interpolationAlpha < 0.0
                || interpolationAlpha > 1.0) {
            throw new IllegalArgumentException(
                    "interpolationAlpha must be finite and in [0, 1]: " + interpolationAlpha);
        }
        Objects.requireNonNull(frameInputs, "frameInputs");
    }
}
