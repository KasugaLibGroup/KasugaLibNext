package lib.kasuga.core.networking.rpc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RpcSessionManager: timeout drain, player matching, overflow saturation, multi-thread stress. */
class RpcSessionManagerTest {

    /** Opaque identity token — the manager only ever compares it with equals (never dereferences). */
    private static final Object PLAYER_A = new Object();
    private static final Object PLAYER_B = new Object();

    @Test
    void checkTimeoutDrainsAllExpiredAndKeepsAlive() {
        RpcSessionManager<String> manager = new RpcSessionManager<>();
        manager.assign(-1000, null);
        manager.assign(-1000, null);
        RpcSessionManager.Session<String> alive = manager.assign(60_000, null);

        List<RpcSessionManager.Session<String>> expired = manager.checkTimeout();
        assertEquals(2, expired.size());
        assertTrue(expired.stream().allMatch(s -> s.future().isCompletedExceptionally()));
        assertEquals(1, manager.sessions.size()); // the alive session remains
        assertTrue(manager.sessionMap.containsKey(alive.id()));

        // second drain finds nothing; the alive session is untouched
        assertTrue(manager.checkTimeout().isEmpty());
        assertFalse(alive.future().isDone());
    }

    @Test
    void expiredFuturesFailWithTimeoutException() {
        RpcSessionManager<String> manager = new RpcSessionManager<>();
        RpcSessionManager.Session<String> session = manager.assign(-1, null);
        manager.checkTimeout();
        assertTrue(session.future().isCompletedExceptionally());
        assertThrows(CompletionException.class, () -> session.future().join());
        try {
            session.future().join();
        } catch (CompletionException e) {
            assertTrue(e.getCause() instanceof RpcTimeoutException, "cause must be RpcTimeoutException");
        }
    }

    @Test
    void acceptMatchesExpectedPlayerOnly() {
        RpcSessionManager<String> manager = new RpcSessionManager<>();
        // accepts == null: any player settles the session
        RpcSessionManager.Session<String> open = manager.assign(60_000, null);
        manager.accept(open.id(), "ok", null);
        assertEquals("ok", open.future().join());
        // repeated accept on an already-settled id is a no-op
        manager.accept(open.id(), "second", null);
        assertEquals("ok", open.future().join());

        // accepts == playerA: a different (null) player is rejected, playerA settles it
        RpcSessionManager.Session<String> owned = manager.assign(60_000, PLAYER_A);
        manager.accept(owned.id(), "stolen", null);
        assertFalse(owned.future().isDone());
        manager.accept(owned.id(), "mine", PLAYER_A);
        assertEquals("mine", owned.future().join());
    }

    @Test
    void rejectOnlySettlesMatchingPlayer() {
        RpcSessionManager<String> manager = new RpcSessionManager<>();
        RpcSessionManager.Session<String> owned = manager.assign(60_000, PLAYER_A);
        manager.reject(owned.id(), new RuntimeException("nope"), null);
        assertFalse(owned.future().isDone());
        manager.reject(owned.id(), new RuntimeException("nope"), PLAYER_A);
        assertTrue(owned.future().isCompletedExceptionally());
    }

    @Test
    void saturatedAddClampsOverflow() {
        assertEquals(3L, RpcSessionManager.saturatedAdd(1, 2));
        assertEquals(Long.MAX_VALUE, RpcSessionManager.saturatedAdd(Long.MAX_VALUE, 1));
        assertEquals(Long.MAX_VALUE, RpcSessionManager.saturatedAdd(Long.MAX_VALUE, Long.MAX_VALUE));
        // no overflow: 0 + (MAX_VALUE - 1) stays MAX_VALUE - 1 (the old default was MAX_VALUE - 1,
        // which only became a bug when added to the current timestamp)
        assertEquals(Long.MAX_VALUE - 1, RpcSessionManager.saturatedAdd(0, Long.MAX_VALUE - 1));
        // negative (already-expired) timeouts pass through untouched
        assertEquals(-5L, RpcSessionManager.saturatedAdd(0, -5));
    }

    @Test
    void hugeTimeoutDoesNotOverflowToImmediateExpiry() {
        RpcSessionManager<String> manager = new RpcSessionManager<>();
        RpcSessionManager.Session<String> session = manager.assign(Long.MAX_VALUE - 1, null);
        assertTrue(session.timeoutAt() > System.currentTimeMillis());
        assertTrue(manager.checkTimeout().isEmpty());
    }

    @Test
    void concurrentAssignAcceptCompletesEachFutureExactlyOnce() throws Exception {
        RpcSessionManager<Integer> manager = new RpcSessionManager<>();
        int sessionCount = 200;
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(sessionCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        List<RpcSessionManager.Session<Integer>> all = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < sessionCount; i++) {
            pool.submit(() -> {
                try {
                    go.await();
                    RpcSessionManager.Session<Integer> session = manager.assign(60_000, null);
                    all.add(session);
                    session.future().whenComplete((result, ex) -> {
                        if (ex == null) {
                            completions.incrementAndGet();
                        } else {
                            failure.compareAndSet(null, ex);
                        }
                    });
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    ready.countDown();
                }
            });
        }

        go.countDown(); // release all workers first — they are all parked on go.await()
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertNull(failure.get());

        for (RpcSessionManager.Session<Integer> session : all) {
            manager.accept(session.id(), 42, null);
        }
        assertEquals(sessionCount, completions.get());
        // second pass: every future is already settled — no double completion
        for (RpcSessionManager.Session<Integer> session : all) {
            manager.accept(session.id(), 43, null);
        }
        assertEquals(sessionCount, completions.get());
        assertTrue(manager.sessionMap.isEmpty());
        assertTrue(manager.sessions.isEmpty());
    }
}
