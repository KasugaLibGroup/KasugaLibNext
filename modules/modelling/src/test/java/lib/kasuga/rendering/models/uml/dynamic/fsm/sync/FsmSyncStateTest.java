package lib.kasuga.rendering.models.uml.dynamic.fsm.sync;

import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FsmSyncState staleness rules: version ordering, force replay, bind-reset, clear. */
class FsmSyncStateTest {

    private static FsmSyncKey key(int seed) {
        return new FsmSyncKey(
                Id.fromNamespaceAndPath("test", "demo"),
                "test:overworld",
                seed
        );
    }

    @Test
    void newerVersionsAreAcceptedOlderOrEqualRejected() {
        FsmSyncState state = new FsmSyncState();
        FsmSyncKey key = key(1);

        assertTrue(state.shouldApply(key, 3, false)); // unknown key = new
        state.recordApplied(key, 3);

        assertFalse(state.shouldApply(key, 3, false)); // equal → stale
        assertFalse(state.shouldApply(key, 2, false)); // older → stale
        assertTrue(state.shouldApply(key, 4, false));  // newer → accepted
    }

    @Test
    void forceReplaysAreAlwaysAccepted() {
        FsmSyncState state = new FsmSyncState();
        FsmSyncKey key = key(1);
        state.recordApplied(key, 9);

        assertTrue(state.shouldApply(key, 9, true)); // same version, forced
        assertTrue(state.shouldApply(key, 1, true)); // rolled-back version, forced
    }

    @Test
    void clearReacceptsRolledBackVersions() {
        FsmSyncState state = new FsmSyncState();
        FsmSyncKey key = key(1);
        state.recordApplied(key, 9);
        assertFalse(state.shouldApply(key, 5, false));

        state.clear(key); // bind resets the record
        assertTrue(state.shouldApply(key, 5, false));
    }

    @Test
    void clearAllReacceptsEveryKey() {
        FsmSyncState state = new FsmSyncState();
        FsmSyncKey k1 = key(1);
        FsmSyncKey k2 = key(2);
        state.recordApplied(k1, 1);
        state.recordApplied(k2, 1);
        assertFalse(state.shouldApply(k1, 1, false));

        state.clearAll();
        assertTrue(state.shouldApply(k1, 1, false));
        assertTrue(state.shouldApply(k2, 1, false));
    }

    @Test
    void recordsArePerKey() {
        FsmSyncState state = new FsmSyncState();
        FsmSyncKey k1 = key(1);
        FsmSyncKey k2 = key(2);
        state.recordApplied(k1, 9);

        assertFalse(state.shouldApply(k1, 5, false));
        assertTrue(state.shouldApply(k2, 5, false));
    }
}
