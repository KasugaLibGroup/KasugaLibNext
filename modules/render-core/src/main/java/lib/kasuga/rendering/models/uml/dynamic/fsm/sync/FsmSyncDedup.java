package lib.kasuga.rendering.models.uml.dynamic.fsm.sync;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-{@code (key, player)} version dedup and forced-heartbeat cadence for {@code FsmSyncServer}.
 * Pure logic — player references are reduced to {@link UUID}s so the whole state machine is
 * unit-testable without a Minecraft runtime (constructing {@code ServerPlayer} in a plain JVM
 * triggers entity class-init and fails).
 *
 * <p>Methods are public because {@code FsmSyncServer} (the consumer) lives in the MC layer
 * ({@code lib.kasuga.rendering.models.mc.dynamic.fsm.sync}), a different package.
 */
public final class FsmSyncDedup {

    /** Every N pushes (one push per machine per tick) a forced full-state heartbeat is sent. */
    static final int HEARTBEAT_INTERVAL_TICKS = 20;

    private final Map<FsmSyncKey, Map<UUID, Integer>> sentVersions = new ConcurrentHashMap<>();
    private final Map<FsmSyncKey, Integer> tickCounters = new ConcurrentHashMap<>();
    /** Per-key last-sent values of {@code sync}-declared parameters, keyed by var id string. */
    private final Map<FsmSyncKey, Map<String, Object>> lastVarValues = new ConcurrentHashMap<>();

    /** @return true on every {@link #HEARTBEAT_INTERVAL_TICKS}-th push for this key (forced replay). */
    public boolean isHeartbeatTick(FsmSyncKey key) {
        int ticks = tickCounters.merge(key, 1, Integer::sum);
        return ticks % HEARTBEAT_INTERVAL_TICKS == 0;
    }

    /** @return true when the payload must be sent: heartbeat, first push, or a newer version. */
    public boolean shouldSend(FsmSyncKey key, UUID playerId, int version, boolean heartbeat) {
        if (heartbeat) {
            return true; // forced replay bypasses dedup
        }
        Map<UUID, Integer> perKey = sentVersions.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        Integer sent = perKey.get(playerId);
        return sent == null || version > sent;
    }

    public void recordSent(FsmSyncKey key, UUID playerId, int version) {
        sentVersions.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(playerId, version);
    }

    /** The last values sent for this key's {@code sync}-declared parameters; lazily created per key. */
    public Map<String, Object> lastVars(FsmSyncKey key) {
        return lastVarValues.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
    }

    /** Record the values just sent for this key's {@code sync}-declared parameters (incremental baseline). */
    public void recordVars(FsmSyncKey key, Map<String, Object> sent) {
        lastVarValues.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).putAll(sent);
    }

    /** Forget a machine: drop its dedup, heartbeat and var-value state (machine destroyed / reloaded). */
    public void unbind(FsmSyncKey key) {
        sentVersions.remove(key);
        tickCounters.remove(key);
        lastVarValues.remove(key);
    }

    /** Drop every dedup entry belonging to a player (logout — keeps the per-player table bounded). */
    public void removePlayer(UUID playerId) {
        sentVersions.values().forEach(perKey -> perKey.remove(playerId));
    }

    /** Drop all dedup and heartbeat state (resource reload; machines will re-sync via bind). */
    public void clearAll() {
        sentVersions.clear();
        tickCounters.clear();
        lastVarValues.clear();
    }
}
