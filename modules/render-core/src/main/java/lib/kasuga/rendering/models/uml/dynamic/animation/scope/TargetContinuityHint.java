package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.jetbrains.annotations.Nullable;

/**
 * Optional result metadata that overrides the default continuity selected for a logical step.
 *
 * <p>An evaluator uses this when two committed outputs must not be interpolated even though both
 * were produced by ordinary positive-delta steps, for example across a clip loop boundary.
 */
public interface TargetContinuityHint {

    /** Required continuity for this output, or {@code null} to use the normal step policy. */
    @Nullable
    Continuity continuityHint();
}
