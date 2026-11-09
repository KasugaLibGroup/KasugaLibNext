package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlEvaluationRequest;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlFrame;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControllerCheckpoint;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvanceCause;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachineCheckpoint;
import lib.kasuga.rendering.models.uml.dynamic.fsm.VarProvider;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs the element/machine bridge once per logical Scope step for a multi-part proxy.
 */
public final class ProxyMachineController implements RuntimeController<Void> {

    private final Supplier<@Nullable StateMachine<?>> machine;
    private final Consumer<StateMachine<?>> beforeTick;
    private final Consumer<StateMachine<?>> afterTick;
    private final VarProvider varProvider;

    public ProxyMachineController(
            Supplier<@Nullable StateMachine<?>> machine,
            Consumer<StateMachine<?>> beforeTick,
            Consumer<StateMachine<?>> afterTick,
            @Nullable VarProvider varProvider
    ) {
        this.machine = Objects.requireNonNull(machine, "machine");
        this.beforeTick = Objects.requireNonNull(beforeTick, "beforeTick");
        this.afterTick = Objects.requireNonNull(afterTick, "afterTick");
        this.varProvider = varProvider;
    }

    @Override
    public ControllerCheckpoint beginStep() {
        StateMachine<?> current = machine.get();
        if (current == null) {
            return ControllerCheckpoint.NONE;
        }
        StateMachineCheckpoint checkpoint = current.checkpoint();
        return new ControllerCheckpoint() {
            @Override
            public void rollback() {
                current.restore(checkpoint);
            }

            @Override
            public void commit() {
                afterTick.accept(current);
            }
        };
    }

    @Override
    public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
        if (request.timeStep().cause() != ScopeAdvanceCause.GAME_TICK) {
            return new ControlFrame<>(null, List.of(), List.of());
        }
        StateMachine<?> current = machine.get();
        if (current == null) {
            return new ControlFrame<>(null, List.of(), List.of());
        }
        beforeTick.accept(current);
        current.tick((float) request.timeStep().deltaSeconds());
        if (varProvider != null) {
            Map<String, Float> values = new HashMap<>();
            varProvider.provide(
                    current,
                    (float) request.timeStep().deltaSeconds(),
                    values);
        }
        return new ControlFrame<>(null, List.of(), List.of());
    }
}
