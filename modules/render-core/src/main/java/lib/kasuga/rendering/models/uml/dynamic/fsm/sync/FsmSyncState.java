package lib.kasuga.rendering.models.uml.dynamic.fsm.sync;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side per-key version bookkeeping for the host's FSM sync client. Tracks the <em>server</em>
 * version last applied — deliberately not the client machine's own version, which local ticking
 * pollutes — so stale or duplicate packets can be dropped. {@code force} packets (heartbeat replays)
 * bypass the check.
 */
public final class FsmSyncState {

    private final Map<FsmSyncKey, Integer> versions = new ConcurrentHashMap<>();

    /**
     * Accept a packet when forced (heartbeat replay) or when its server version is newer than the
     * last applied one; an unknown key is treated as new.
     */
    public boolean shouldApply(FsmSyncKey key, int version, boolean force) {
        if (force) {
            return true;
        }
        Integer lastApplied = versions.get(key);
        return lastApplied == null || version > lastApplied;
    }

    /** Record that {@code version} has been applied for this key. */
    public void recordApplied(FsmSyncKey key, int version) {
        versions.put(key, version);
    }

    /**
     * Reset the version record for a key — invoked when the host rebinds so a rebuilt
     * machine whose server version rolled back is never permanently rejected.
     */
    public void clear(FsmSyncKey key) {
        versions.remove(key);
    }

    /** Drop every record (resource reload / client teardown). */
    public void clearAll() {
        versions.clear();
    }
}
