package lib.kasuga.rendering.models.uml.dynamic.animation.output;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.Continuity;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetContinuityHint;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Immutable model-pose result produced for one logical revision.
 *
 * <p>The wrapper separates the target output contract from the FSM pose composition type.
 * {@link Pose} already defensively copies its collections and bone transforms. The optional
 * continuity hint lets an FSM mark a loop seam as discontinuous without adding FSM types to the
 * generic Scope contract.
 */
public record PoseOutput(
        Pose pose,
        @Nullable Continuity continuityHint
) implements TargetContinuityHint {

    public PoseOutput {
        Objects.requireNonNull(pose, "pose");
    }

    public PoseOutput(Pose pose) {
        this(pose, null);
    }

    public static PoseOutput empty() {
        return new PoseOutput(Pose.empty());
    }
}
