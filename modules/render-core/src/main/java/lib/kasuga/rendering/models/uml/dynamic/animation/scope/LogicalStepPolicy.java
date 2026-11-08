package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/**
 * Platform-supplied policy for turning raw host deltas into fixed animation logic steps.
 *
 * @param fixedStepSeconds duration of one animation logic step
 * @param maxCatchUpSteps maximum number of steps emitted for one host advance
 */
public record LogicalStepPolicy(double fixedStepSeconds, int maxCatchUpSteps) {

    public LogicalStepPolicy {
        if (!Double.isFinite(fixedStepSeconds) || fixedStepSeconds <= 0.0) {
            throw new IllegalArgumentException("fixedStepSeconds must be finite and positive");
        }
        if (maxCatchUpSteps <= 0) {
            throw new IllegalArgumentException("maxCatchUpSteps must be positive");
        }
    }

    public static LogicalStepPolicy fixed(double fixedStepSeconds, int maxCatchUpSteps) {
        return new LogicalStepPolicy(fixedStepSeconds, maxCatchUpSteps);
    }
}
