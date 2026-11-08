package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlEvaluationRequest;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlFrame;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControllerCheckpoint;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvanceCause;

import java.util.List;
import java.util.Objects;

/** Advances one FSM from the Scope's committed logical step duration. */
public final class FsmStepController implements RuntimeController<Void> {

    private final StateMachine<?> machine;

    public FsmStepController(StateMachine<?> machine) {
        this.machine = Objects.requireNonNull(machine, "machine");
    }

    public StateMachine<?> machine() {
        return machine;
    }

    @Override
    public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
        if (request.timeStep().cause() == ScopeAdvanceCause.GAME_TICK) {
            machine.tick((float) request.timeStep().deltaSeconds());
        }
        return new ControlFrame<>(null, List.of(), List.of());
    }

    @Override
    public ControllerCheckpoint beginStep() {
        StateMachineCheckpoint checkpoint = machine.checkpoint();
        return () -> machine.restore(checkpoint);
    }
}
