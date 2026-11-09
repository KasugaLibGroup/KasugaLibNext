package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.state.MutableStateMap;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Complete in-memory FSM state captured before one transactional scope step. */
public record StateMachineCheckpoint(
        int version,
        long tickCount,
        MutableStateMap values,
        Map<String, LayerRuntimeState> layers,
        Map<String, Integer> locks,
        Set<StateVar<Boolean>> bufferedTriggers
) {

    public StateMachineCheckpoint {
        Objects.requireNonNull(values, "values");
        layers = Map.copyOf(layers);
        locks = Map.copyOf(locks);
        bufferedTriggers = Set.copyOf(bufferedTriggers);
    }

    /** Mutable state of one layer that must survive a failed logical step. */
    public record LayerRuntimeState(
            String activeStateId,
            String transitionId,
            float transitionElapsed,
            int stateElapsedTicks,
            String pendingStateId,
            boolean activeChanged,
            float clipPreviousTime,
            float clipTime
    ) {
    }
}
