package lib.kasuga.rendering.models.mc.dynamic.fsm.sync;

import lib.kasuga.rendering.models.uml.dynamic.fsm.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Layer;
import lib.kasuga.rendering.models.uml.dynamic.fsm.State;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Transition;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Server side of the FSM sync channel. Push is event-driven with a forced heartbeat: a payload is
 * sent only when the machine version changed for a given {@code (key, player)} (dedup table), and
 * every {@link FsmSyncDedup#HEARTBEAT_INTERVAL_TICKS} ticks an unconditional full-state replay with
 * {@code force=true} bypasses the client's staleness check (recovers dropped or diverged clients).
 * Call {@link #push} from the server tick thread; {@link #unbind} on machine destroy,
 * {@link #removePlayer} on player logout, {@link #clearAll} on resource reload.
 */
public class FsmSyncServer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Process-wide shared instance: dedup/heartbeat tables must be global or per-player cleanup
     * misses entries registered by other owners. Referenced by {@code AnimationBlockEntity.SYNC_SERVER}
     * and {@link FsmSyncServerHooks}.
     */
    public static final FsmSyncServer GLOBAL = new FsmSyncServer();

    final FsmSyncDedup dedup = new FsmSyncDedup();

    private final Function<Id, Integer> definitionHash;

    /** Defaults the definition-hash source to the shared bucket ({@link FsmRegistries#GLOBAL}). */
    public FsmSyncServer() {
        this(FsmRegistries.GLOBAL.definitions()::hash);
    }

    /** Testable entry: inject the per-id definition-hash source. */
    public FsmSyncServer(Function<Id, Integer> definitionHash) {
        this.definitionHash = definitionHash;
    }

    /**
     * Push the machine's current state to every target player that needs it (version changed, or
     * the periodic heartbeat). Safe to call every tick; cheap when nothing changed.
     */
    public void push(FsmSyncKey key, StateMachine<?> machine, Collection<ServerPlayer> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        boolean heartbeat = dedup.isHeartbeatTick(key);
        int version = machine.version();
        FsmSyncPayload payload = null;
        for (ServerPlayer player : targets) {
            UUID playerId = player.getUUID();
            if (!dedup.shouldSend(key, playerId, version, heartbeat)) {
                continue;
            }
            if (payload == null) {
                // lazy: build the wire payload only when at least one player needs it
                payload = toPayload(key, machine, version, heartbeat);
            }
            sendTo(player, payload);
            dedup.recordSent(key, playerId, version);
        }
    }

    /** Send hook — overridable in tests; the default routes through {@link PacketDistributor}. */
    protected void sendTo(ServerPlayer player, FsmSyncPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * BE convenience: push to every player tracking the given block's chunk — the equivalent of
     * {@code PacketDistributor.sendToPlayersTrackingChunk}, routed through the per-player dedup and
     * heartbeat logic of {@link #push}.
     */
    public void pushToChunkTrackers(FsmSyncKey key, StateMachine<?> machine, ServerLevel level, BlockPos pos) {
        List<ServerPlayer> targets = new ArrayList<>(level.getChunkSource().chunkMap.getPlayers(new ChunkPos(pos), false));
        push(key, machine, targets);
    }

    /** Forget a machine: drop its dedup and heartbeat state (machine destroyed / reloaded). */
    public void unbind(FsmSyncKey key) {
        dedup.unbind(key);
    }

    /** Drop every dedup entry belonging to a player (logout — keeps the per-player table bounded). */
    public void removePlayer(ServerPlayer player) {
        dedup.removePlayer(player.getUUID());
    }

    /** Drop all dedup and heartbeat state (resource reload; machines will re-sync via bind). */
    public void clearAll() {
        dedup.clearAll();
    }

    /**
     * Build the wire payload for a push. {@code protected} (not private) so tests can capture the
     * payload without constructing a {@link ServerPlayer}.
     */
    protected FsmSyncPayload toPayload(FsmSyncKey key, StateMachine<?> machine, int version, boolean force) {
        List<FsmSyncPayload.LayerEntry> layers = new ArrayList<>();
        int layerIndex = 0;
        for (Layer<?> layer : machine.layers()) {
            State<?> active = layer.active();
            Transition<?> transition = layer.activeTransition();
            layers.add(new FsmSyncPayload.LayerEntry(
                    layerIndex,
                    active == null ? -1 : layer.activeStateIndex(),
                    layer.stateElapsedTicks(),
                    transition == null ? -1 : layer.activeTransitionIndex(),
                    layer.transitionElapsed()
            ));
            layerIndex++;
        }
        List<FsmSyncPayload.VarEntry> vars = collectVars(key, machine, force);
        return new FsmSyncPayload(
                key.machineId(),
                key.dimension(),
                key.ownerDiscriminator(),
                version,
                definitionHash.apply(key.machineId()),
                force,
                layers,
                vars
        );
    }

    /**
     * Collect the {@code sync}-declared parameters whose value changed since the last push for this
     * key (or all of them on a forced heartbeat). Values of an unknown runtime type (not in the
     * built-in {@link StateVarType} catalog) are skipped with a warning.
     */
    private List<FsmSyncPayload.VarEntry> collectVars(FsmSyncKey key, StateMachine<?> machine, boolean force) {
        List<FsmSyncPayload.VarEntry> vars = new ArrayList<>();
        for (StateVar<?> declared : machine.declaredVars()) {
            if (!(declared instanceof ParameterSpec<?> spec) || !spec.sync()) {
                continue;
            }
            StateVarType<?> type = StateVarType.byClass(spec.type());
            if (type == null) {
                LOGGER.warn("FSM sync: declared sync parameter '{}' has no built-in value type for {}; skipping",
                        spec.id(), spec.type().getSimpleName());
                continue;
            }
            Object value = varValue(machine, spec);
            Map<String, Object> last = dedup.lastVars(key);
            if (force || !Objects.equals(last.get(spec.id().toString()), value)) {
                vars.add(new FsmSyncPayload.VarEntry(spec.id().toString(), type.token(), value));
            }
        }
        if (!vars.isEmpty()) {
            dedup.recordVars(key, vars.stream().collect(Collectors.toMap(
                    FsmSyncPayload.VarEntry::varId, FsmSyncPayload.VarEntry::value)));
        }
        return vars;
    }

    /** Read a declared var's value with the erasure-bridged {@code StateMap.get} signature. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object varValue(StateMachine<?> machine, StateVar<?> var) {
        return machine.vars().get((StateVar) var);
    }
}
