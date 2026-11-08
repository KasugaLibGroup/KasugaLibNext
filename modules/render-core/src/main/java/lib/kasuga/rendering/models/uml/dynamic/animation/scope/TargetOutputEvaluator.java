package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/**
 * Computes one target's logical output for a committed scope state.
 *
 * @param <O> immutable, target-specific output type
 */
@FunctionalInterface
public interface TargetOutputEvaluator<O> {

    /**
     * @return the current output, or {@code null} when this target has no output
     */
    O evaluate(TargetEvaluationContext context);
}
