package lib.kasuga.rendering.models.uml.dynamic.animation.output;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.Continuity;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.OutputState;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.dynamic.fsm.PoseBlend;

import java.util.Objects;

/**
 * Resolves the render-frame pose from a target publication.
 *
 * <p>{@link Continuity#INTERPOLATE} blends consecutive committed outputs. The other continuity
 * policies use the current output directly. A cleared publication resolves to an empty pose so the
 * pose sink can release channels written by an earlier publication.
 */
public final class PoseOutputInterpolator {

    public Pose interpolate(OutputState<PoseOutput> outputState, double interpolationAlpha) {
        Objects.requireNonNull(outputState, "outputState");
        if (!Double.isFinite(interpolationAlpha)
                || interpolationAlpha < 0.0
                || interpolationAlpha > 1.0) {
            throw new IllegalArgumentException(
                    "interpolationAlpha must be finite and in [0, 1]: " + interpolationAlpha);
        }

        if (outputState instanceof OutputState.Cleared<PoseOutput>) {
            return Pose.empty();
        }

        OutputState.Active<PoseOutput> active = (OutputState.Active<PoseOutput>) outputState;
        PoseOutput current = active.currentOutput();
        if (active.previousOutput() == null
                || active.continuity() != Continuity.INTERPOLATE) {
            return current.pose();
        }
        return PoseBlend.blend(
                active.previousOutput().pose(),
                current.pose(),
                (float) interpolationAlpha);
    }
}
