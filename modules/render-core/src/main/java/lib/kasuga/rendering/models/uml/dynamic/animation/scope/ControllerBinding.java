package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/** Type-safe pairing between one controller and its transactional state. */
public final class ControllerBinding<S> implements ControllerRoute {

    private final RuntimeController<S> controller;
    private S state;

    private ControllerBinding(RuntimeController<S> controller, S state) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.state = state;
    }

    public static <S> ControllerBinding<S> create(RuntimeController<S> controller) {
        Objects.requireNonNull(controller, "controller");
        return new ControllerBinding<>(controller, controller.initialState());
    }

    public static <S> ControllerBinding<S> create(RuntimeController<S> controller, S initialState) {
        return new ControllerBinding<>(controller, initialState);
    }

    public S state() {
        return state;
    }

    @Override
    public ControllerStep evaluate(ControllerEvaluationContext context) {
        ControllerCheckpoint checkpoint = controller.beginStep();
        try {
            ControlFrame<S> frame = controller.evaluate(new ControlEvaluationRequest<>(
                    context.timeStep(), context.inputs(), state,
                    context.activePlaybacks(), context.playbackEvents(), context.playbackTimes()));
            return new ControllerStep(frame, () -> commit(frame.nextState()), checkpoint);
        } catch (RuntimeException failure) {
            try {
                checkpoint.rollback();
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private void commit(S nextState) {
        state = nextState;
    }
}
