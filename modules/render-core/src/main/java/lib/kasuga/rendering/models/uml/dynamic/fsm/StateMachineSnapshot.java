package lib.kasuga.rendering.models.uml.dynamic.fsm;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Immutable point-in-time view of a {@link StateMachine}, used for server→client state
 * synchronization. Produced via {@code StateMachine.snapshot()} and applied via
 * {@code StateMachine.conform(StateMachineSnapshot)}; on the client, {@code FsmSyncClient}
 * conforms bound machines directly on the main thread (packets arriving before a machine exists
 * are dropped and recovered by the forced heartbeat).
 *
 * <p>Each {@link LayerState} carries the active state plus transition progress so a conforming
 * machine lands inside an in-flight crossfade instead of jumping to its end.
 */
public record StateMachineSnapshot(int version, List<LayerState> layers) {

    /** One layer's authoritative position at snapshot time. */
    public record LayerState(
            String layerId,
            String stateId,
            int elapsedTicks,
            @Nullable String transitionId,
            float transitionElapsedSeconds
    ) {}
}
