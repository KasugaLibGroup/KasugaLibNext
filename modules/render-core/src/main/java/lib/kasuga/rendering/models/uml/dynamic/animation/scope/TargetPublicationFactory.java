package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/**
 * Converts successive target outputs into immutable publications with continuity metadata.
 *
 * <p>One factory belongs to one target binding. It stores that target's previous output and
 * chooses continuity from the committed logical step.
 *
 * @param <O> immutable, target-specific output type
 */
public final class TargetPublicationFactory<O> {

    private O previousOutput;
    private boolean hasPreviousOutput;

    public OutputState<O> create(
            TargetEvaluationContext context,
            O currentOutput,
            boolean active
    ) {
        Objects.requireNonNull(context, "context");

        if (!active) {
            return new OutputState.Cleared<>("inactive");
        }
        Objects.requireNonNull(currentOutput, "currentOutput");

        O previous = hasPreviousOutput ? previousOutput : null;
        Continuity continuity;
        if (!hasPreviousOutput || context.timeStep().discontinuity() == TemporalDiscontinuity.SEEK) {
            continuity = Continuity.SNAP;
        } else if (currentOutput instanceof TargetContinuityHint hint
                && hint.continuityHint() != null) {
            continuity = hint.continuityHint();
        } else if (context.timeStep().deltaSeconds() == 0.0) {
            continuity = Continuity.HOLD_CURRENT;
        } else {
            continuity = Continuity.INTERPOLATE;
        }

        return new OutputState.Active<>(previous, currentOutput, continuity);
    }

    /**
     * Advances the visible-output history after a target accepted the publication.
     */
    public void commit(OutputState<O> publishedState) {
        Objects.requireNonNull(publishedState, "publishedState");
        if (publishedState instanceof OutputState.Active<O> active) {
            previousOutput = active.currentOutput();
            hasPreviousOutput = true;
        } else {
            previousOutput = null;
            hasPreviousOutput = false;
        }
    }
}
