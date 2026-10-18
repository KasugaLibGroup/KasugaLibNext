package lib.kasuga.rendering.models.mc.dynamic.fsm.sync;

import lib.kasuga.rendering.models.uml.dynamic.fsm.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.*;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmMachines;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Layer;
import lib.kasuga.rendering.models.uml.dynamic.fsm.State;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachineSnapshot;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Transition;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Client side of the FSM sync channel. Binds locally-built machines to their {@link FsmSyncKey}s
 * (never looked up through {@link FsmMachines#latest}), validates incoming payloads — thread,
 * staleness, definition identity, index bounds — and conforms the machine. {@link #apply} runs on
 * the main thread only: the payload handler enqueues it via {@code IPayloadContext.enqueueWork}.
 */
public final class FsmSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Shared client-side instance wired into {@link FsmSyncChannel}. */
    public static final FsmSyncClient INSTANCE = new FsmSyncClient();

    private final FsmSyncState syncState;
    private final Map<FsmSyncKey, StateMachine<?>> machines = new ConcurrentHashMap<>();
    private final Function<Id, Integer> definitionHash;

    /** Defaults the definition-hash source to the shared bucket ({@link FsmRegistries#GLOBAL}). */
    public FsmSyncClient() {
        this(FsmRegistries.GLOBAL.definitions()::hash);
    }

    /** Testable entry: inject the per-id definition-hash source. */
    public FsmSyncClient(Function<Id, Integer> definitionHash) {
        this(new FsmSyncState(), definitionHash);
    }

    /** Fully injectable entry. */
    public FsmSyncClient(FsmSyncState syncState, Function<Id, Integer> definitionHash) {
        this.syncState = syncState;
        this.definitionHash = definitionHash;
    }

    /**
     * Attach a locally-built machine to a key and reset the key's version record — a rebuilt
     * machine must accept whatever server version is current, even if it rolled back.
     */
    public void bind(FsmSyncKey key, StateMachine<?> machine) {
        machines.put(key, machine);
        syncState.clear(key);
    }

    /** Detach a machine; also clears its version record (safe to call twice). */
    public void unbind(FsmSyncKey key) {
        machines.remove(key);
        syncState.clear(key);
    }

    /** Drop every binding and version record (resource reload / client teardown). */
    public void clearAll() {
        machines.clear();
        syncState.clearAll();
    }

    /**
     * Apply an incoming payload on the main thread only (the {@link FsmSyncChannel} handler
     * enqueues it): validate staleness, definition identity and index bounds, then conform the
     * machine.
     */
    public void apply(FsmSyncPayload payload) {
        FsmSyncKey key = new FsmSyncKey(payload.machineId(), payload.dimension(), payload.ownerDiscriminator());
        if (!syncState.shouldApply(key, payload.version(), payload.force())) {
            return;
        }
        StateMachine<?> machine = machines.get(key);
        if (machine == null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("FSM sync: no bound machine for {} — dropping (heartbeat will retry)", key);
            }
            return;
        }
        int localHash = definitionHash.apply(payload.machineId());
        if (localHash != payload.definitionHash()) {
            LOGGER.warn("FSM sync: definition hash mismatch for {} — server {}, local {}; dropping (definitions out of sync)",
                    key, payload.definitionHash(), localHash);
            return;
        }
        if (machine.conform(toSnapshot(payload, machine))) {
            syncState.recordApplied(key, payload.version());
        }
        // vars land independently of the layer conform — a payload may carry a parameter change
        // while every layer is unchanged (conform returns false, version not bumped)
        applyVars(payload, machine);
    }

    /** Land the payload's synchronized parameters into the machine's value store (setInternal semantics). */
    private static void applyVars(FsmSyncPayload payload, StateMachine<?> machine) {
        for (FsmSyncPayload.VarEntry entry : payload.vars()) {
            ParameterSpec<?> spec = findSyncSpec(machine, entry.varId());
            if (spec == null) {
                LOGGER.warn("FSM sync: payload carries unknown or non-sync parameter '{}'; skipping", entry.varId());
                continue;
            }
            StateVarType<?> type = StateVarType.byClass(spec.type());
            if (type == null || !type.token().equals(entry.type())) {
                LOGGER.warn("FSM sync: parameter '{}' type mismatch (wire {}, local {}); skipping",
                        entry.varId(), entry.type(), type == null ? "<none>" : type.token());
                continue;
            }
            machine.mutableVars().set(rawSpec(spec), entry.value());
        }
    }

    /** A declared {@code sync} parameter whose id matches {@code varId}, or {@code null}. */
    private static ParameterSpec<?> findSyncSpec(StateMachine<?> machine, String varId) {
        for (StateVar<?> declared : machine.declaredVars()) {
            if (declared instanceof ParameterSpec<?> spec && spec.sync() && spec.id().toString().equals(varId)) {
                return spec;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> StateVar<T> rawSpec(StateVar<?> spec) {
        return (StateVar<T>) spec;
    }

    /** Index → id conversion against the bound machine's layers; out-of-bounds entries are skipped. */
    private StateMachineSnapshot toSnapshot(FsmSyncPayload payload, StateMachine<?> machine) {
        List<StateMachineSnapshot.LayerState> layerStates = new ArrayList<>(payload.layers().size());
        List<? extends Layer<?>> machineLayers = machine.layers();
        for (FsmSyncPayload.LayerEntry entry : payload.layers()) {
            if (entry.layerIndex() < 0 || entry.layerIndex() >= machineLayers.size()) {
                LOGGER.warn("FSM sync: layer index {} out of bounds ({} layers) for {} — skipping layer",
                        entry.layerIndex(), machineLayers.size(), payload.machineId());
                continue;
            }
            Layer<?> layer = machineLayers.get(entry.layerIndex());
            if (entry.stateIndex() < 0) {
                continue; // server layer has no active state — nothing to conform
            }
            State<?> state = layer.stateAt(entry.stateIndex());
            if (state == null) {
                LOGGER.warn("FSM sync: state index {} out of bounds for layer '{}' of {} — skipping layer",
                        entry.stateIndex(), layer.id(), payload.machineId());
                continue;
            }
            Transition<?> transition = null;
            if (entry.transitionIndex() >= 0) {
                transition = layer.transitionAt(entry.transitionIndex());
                if (transition == null) {
                    LOGGER.warn("FSM sync: transition index {} out of bounds for layer '{}' of {} — falling back to hard switch",
                            entry.transitionIndex(), layer.id(), payload.machineId());
                }
            }
            layerStates.add(new StateMachineSnapshot.LayerState(
                    layer.id(),
                    state.id(),
                    entry.elapsedTicks(),
                    transition == null ? null : transition.id(),
                    entry.transitionElapsedSeconds()
            ));
        }
        return new StateMachineSnapshot(payload.version(), layerStates);
    }
}
