package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

/**
 * Stateless extension seam. Mutable controller state is supplied and returned explicitly by the
 * scope transaction, so a failed step cannot partially modify a controller.
 */
@FunctionalInterface
public interface RuntimeController<S> {

    ControlFrame<S> evaluate(ControlEvaluationRequest<S> request);

    /** A controller that emits the canonical empty committed frame. */
    static RuntimeController<Void> none() {
        return request -> new ControlFrame<>(null, java.util.List.of(), java.util.List.of());
    }

    default S initialState() {
        return null;
    }

    /**
     * Captures rollback information before evaluation.
     *
     * <p>Controllers should normally compute against supplied immutable state. A controller that
     * must mutate an adapter-owned runtime can return a checkpoint here; the scope rolls it back if
     * any later controller or command abort the logical step.
     */
    default ControllerCheckpoint beginStep() {
        return ControllerCheckpoint.NONE;
    }
}
