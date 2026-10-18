package lib.kasuga.rendering.models.uml.dynamic.fsm.sync;

import lib.kasuga.rendering.models.mc.dynamic.fsm.sync.FsmSyncServer;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.State;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dedup, forced-heartbeat cadence, player removal and reset semantics of the sync server side.
 * The pure {@link FsmSyncDedup} is exercised directly with {@link UUID}s — constructing a real
 * {@code ServerPlayer} in a plain JVM triggers entity class-init and fails, so the thin
 * {@link FsmSyncServer} wrapper (payload build + send hook) is covered by the no-op path here and
 * by the contentTesting game tests.
 */
class FsmSyncServerTest {

    private static UUID player(long seed) {
        return new UUID(0, seed);
    }

    private static StateMachine<Object> machine() {
        return StateMachine.builder(new Object())
                .layer("loco", layer -> {
                    State<Object> a = layer.state("a").durationTicks(2);
                    State<Object> b = layer.state("b");
                    layer.initial(a);
                    layer.transition("a_to_b", a, b).whenComplete();
                })
                .build();
    }

    private static FsmSyncKey key() {
        return new FsmSyncKey(
                Id.fromNamespaceAndPath("test", "demo"),
                "test:overworld",
                1L
        );
    }

    @Test
    void dedupesUnchangedVersionsPerPlayer() {
        FsmSyncDedup dedup = new FsmSyncDedup();
        FsmSyncKey key = key();
        UUID p1 = player(1);

        assertTrue(dedup.shouldSend(key, p1, 0, false)); // first push: version 0
        dedup.recordSent(key, p1, 0);

        assertFalse(dedup.shouldSend(key, p1, 0, false)); // unchanged version → deduped

        assertTrue(dedup.shouldSend(key, p1, 1, false)); // newer version → sent
        dedup.recordSent(key, p1, 1);
        assertFalse(dedup.shouldSend(key, p1, 1, false));

        // per-player isolation: a second player still gets the first payload
        UUID p2 = player(2);
        assertTrue(dedup.shouldSend(key, p2, 0, false));
    }

    @Test
    void heartbeatReplaysEveryTwentyPushes() {
        FsmSyncDedup dedup = new FsmSyncDedup();
        FsmSyncKey key = key();
        UUID p1 = player(1);

        dedup.recordSent(key, p1, 0);
        for (int i = 1; i <= 19; i++) {
            assertFalse(dedup.isHeartbeatTick(key) && i != 20, "no forced heartbeat before the 20th push");
        }
        // pushes 1..19 are not heartbeats; the 20th is
        FsmSyncDedup fresh = new FsmSyncDedup();
        boolean heartbeat = false;
        for (int i = 1; i <= 20; i++) {
            heartbeat = fresh.isHeartbeatTick(key);
        }
        assertTrue(heartbeat);
        // forced replay bypasses dedup even for an unchanged version
        assertTrue(fresh.shouldSend(key, p1, 0, true));
    }

    @Test
    void removePlayerClearsItsDedupEntries() {
        FsmSyncDedup dedup = new FsmSyncDedup();
        FsmSyncKey key = key();
        UUID p1 = player(1);
        UUID p2 = player(2);

        dedup.recordSent(key, p1, 0);
        dedup.recordSent(key, p2, 0);

        dedup.removePlayer(p1);
        assertTrue(dedup.shouldSend(key, p1, 0, false)); // p1 needs a resend
        assertFalse(dedup.shouldSend(key, p2, 0, false)); // p2 unaffected
    }

    @Test
    void unbindAndClearAllResetDedupState() {
        FsmSyncDedup dedup = new FsmSyncDedup();
        FsmSyncKey key = key();
        UUID p1 = player(1);

        dedup.recordSent(key, p1, 0);
        assertFalse(dedup.shouldSend(key, p1, 0, false));

        dedup.unbind(key);
        assertTrue(dedup.shouldSend(key, p1, 0, false)); // dedup forgotten → resent

        dedup.recordSent(key, p1, 0);
        dedup.clearAll();
        assertTrue(dedup.shouldSend(key, p1, 0, false));
    }

    @Test
    void pushWithNoTargetsIsNoOp() {
        FsmSyncServer server = new FsmSyncServer(id -> 9);
        StateMachine<Object> machine = machine();
        server.push(key(), machine, List.of());
        server.push(key(), machine, null);
        // nothing to observe beyond "no exception"; the send path needs a real player
        assertTrue(true);
    }
}
