package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlEvaluationRequest;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlFrame;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControllerBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ManagedAnimationScope;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.PlaybackCommand;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.PlaybackInstanceId;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvanceCause;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvance;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeInputStore;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TemporalDiscontinuity;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TemporalStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FsmStepControllerTest {

    @Test
    void onlyAGameTickAdvancesTheMachine() {
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("main", layer -> layer.initial(layer.state("idle")))
                .build();
        FsmStepController controller = new FsmStepController(machine);

        controller.evaluate(request(ScopeAdvanceCause.PAUSE, 0.0));
        assertEquals(0, machine.tickCount());

        controller.evaluate(request(ScopeAdvanceCause.GAME_TICK, 0.05));
        assertEquals(1, machine.tickCount());
    }

    @Test
    void failedCommandTransactionRollsBackTheMachineTick() {
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("main", layer -> layer.initial(layer.state("idle")))
                .build();
        PlaybackInstanceId id = new PlaybackInstanceId(UUID.randomUUID(), 1);
        RuntimeController<Void> commandController = new RuntimeController<>() {
            @Override
            public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
                return new ControlFrame<>(
                        null,
                        List.of(new PlaybackCommand.Resume(id)),
                        List.of());
            }
        };
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        UUID.randomUUID(), LogicalStepPolicy.fixed(0.05, 5))
                .controller(ControllerBinding.create(new FsmStepController(machine)))
                .controller(ControllerBinding.create(commandController))
                .commandApplier(command -> {
                    throw new IllegalStateException("command staging failed");
                })
                .build();

        var result = scope.advance(
                new ScopeAdvance(0.05, ScopeAdvanceCause.GAME_TICK));

        assertEquals(true, result.aborted());
        assertEquals(0, machine.tickCount());
    }

    private static ControlEvaluationRequest<Void> request(
            ScopeAdvanceCause cause,
            double deltaSeconds
    ) {
        return new ControlEvaluationRequest<>(
                new TemporalStep(
                        0.0, deltaSeconds, deltaSeconds, cause,
                        cause == ScopeAdvanceCause.PAUSE
                                ? TemporalDiscontinuity.CONTROL
                                : TemporalDiscontinuity.NONE),
                new ScopeInputStore().freeze(),
                null,
                List.of(),
                List.of());
    }
}
