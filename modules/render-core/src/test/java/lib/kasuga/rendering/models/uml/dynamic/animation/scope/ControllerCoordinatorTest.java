package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControllerCoordinatorTest {

    private static final InputKey<Integer> VALUE = new InputKey<>("test/value", Integer.class);

    @Test
    void everyControllerReadsTheSamePreStepInputAndCommitsAfterTheRound() {
        RecordingController first = new RecordingController("first", List.of("first"));
        RecordingController second = new RecordingController("second", List.of("second"));
        ControllerBinding<Long> firstBinding = ControllerBinding.create(first);
        ControllerBinding<Long> secondBinding = ControllerBinding.create(second);
        ControllerCoordinator coordinator = new ControllerCoordinator(List.of(
                firstBinding,
                secondBinding
        ));
        ScopeInputStore inputs = new ScopeInputStore();
        inputs.inputPort().write(VALUE, 7);
        TemporalStep step = new TemporalStep(
                0.0, 0.05, 0.05, ScopeAdvanceCause.GAME_TICK, TemporalDiscontinuity.NONE);
        ControllerEvaluationContext context = new ControllerEvaluationContext(
                step, inputs.freeze(), List.of(), List.of());

        ControllerRound round = coordinator.evaluate(context);

        assertEquals(List.of("first", "second"), round.events().stream().map(ControlEvent::typeId).toList());
        assertEquals(7, first.lastRequest.inputs().value(VALUE).orElseThrow());
        assertEquals(7, second.lastRequest.inputs().value(VALUE).orElseThrow());
        assertEquals(0L, firstBinding.state());
        assertEquals(0L, secondBinding.state());

        round.commit();

        assertEquals(1L, firstBinding.state());
        assertEquals(1L, secondBinding.state());
    }

    @Test
    void aDiscardedRoundDoesNotCommitControllerState() {
        RecordingController controller = new RecordingController("only", List.of("event"));
        ControllerBinding<Long> binding = ControllerBinding.create(controller);
        ControllerCoordinator coordinator = new ControllerCoordinator(List.of(binding));
        TemporalStep step = new TemporalStep(
                0.0, 0.05, 0.05, ScopeAdvanceCause.GAME_TICK, TemporalDiscontinuity.NONE);

        coordinator.evaluate(new ControllerEvaluationContext(
                step, new ScopeInputStore().freeze(), List.of(), List.of()));

        assertEquals(0L, binding.state());
    }

    @Test
    void laterControllerFailureRollsBackEarlierAdapterSideEffects() {
        int[] checkpoints = {0};
        int[] rollbacks = {0};
        RuntimeController<Void> first = new RuntimeController<>() {
            @Override
            public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
                return new ControlFrame<>(null, List.of(), List.of());
            }

            @Override
            public ControllerCheckpoint beginStep() {
                checkpoints[0]++;
                return () -> rollbacks[0]++;
            }
        };
        RuntimeController<Void> failing = new RuntimeController<>() {
            @Override
            public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
                throw new IllegalStateException("controller failed");
            }
        };
        ControllerCoordinator coordinator = new ControllerCoordinator(List.of(
                ControllerBinding.create(first),
                ControllerBinding.create(failing)));
        TemporalStep step = new TemporalStep(
                0.0, 0.05, 0.05, ScopeAdvanceCause.GAME_TICK, TemporalDiscontinuity.NONE);

        assertThrows(IllegalStateException.class, () -> coordinator.evaluate(
                new ControllerEvaluationContext(
                        step, new ScopeInputStore().freeze(), List.of(), List.of())));

        assertEquals(1, checkpoints[0]);
        assertEquals(1, rollbacks[0]);
    }

    private static final class RecordingController implements RuntimeController<Long> {
        private final String id;
        private final List<String> events;
        private ControlEvaluationRequest<Long> lastRequest;

        private RecordingController(String id, List<String> events) {
            this.id = id;
            this.events = events;
        }

        @Override
        public ControlFrame<Long> evaluate(ControlEvaluationRequest<Long> request) {
            lastRequest = request;
            return new ControlFrame<>(
                    request.state() + 1,
                    List.of(),
                    events.stream().map(ControlEvent::new).toList());
        }

        @Override
        public Long initialState() {
            return 0L;
        }

        @Override
        public String toString() {
            return id;
        }
    }
}
