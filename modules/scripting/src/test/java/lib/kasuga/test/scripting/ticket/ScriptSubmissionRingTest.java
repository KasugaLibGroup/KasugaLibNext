package lib.kasuga.test.scripting.ticket;

import lib.kasuga.core.resource.script.*;
import lib.kasuga.scripting.resource.ScriptSubmissionRing;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ScriptSubmissionRingTest {
    private static final ScriptContract CONTRACT = new ScriptContract(List.of(ScriptContract.Type.NUMBER), ScriptContract.Type.NUMBER);
    @Test void submissionsReturnWithoutRunningAndDrainHonorsBatchLimit() {
        AtomicInteger calls = new AtomicInteger();
        try (var ring = new ScriptSubmissionRing("scope", 1, Thread.currentThread(), 3, () -> {})) {
            var a = ring.submit(CONTRACT, List.of(3), args -> { calls.incrementAndGet(); return ((Number) args.getFirst()).intValue() + 1; });
            var b = ring.submit(CONTRACT, List.of(4), args -> { calls.incrementAndGet(); return 5; });
            assertEquals(0, calls.get()); assertTrue(a.poll().isEmpty());
            assertEquals(1, ring.drain(1, 1_000_000_000));
            assertEquals(4, a.poll().orElseThrow().value()); assertTrue(b.poll().isEmpty());
            ring.drain(10, 1_000_000_000); assertEquals(ScriptTicket.Status.SUCCEEDED, b.status());
        }
    }
    @Test void boundedQueueErrorsAndClosureHaveTerminalTickets() {
        try (var ring = new ScriptSubmissionRing("scope", 7, Thread.currentThread(), 1, () -> {})) {
            var queued = ring.submit(CONTRACT, List.of(1), args -> 1);
            var overflow = ring.submit(CONTRACT, List.of(2), args -> 2);
            assertEquals(ScriptTicket.Status.REJECTED, overflow.status());
            ring.close(); assertEquals(ScriptTicket.Status.CANCELLED, queued.status());
            assertEquals(ScriptTicket.Status.CANCELLED, ring.submit(CONTRACT, List.of(1), args -> 1).status());
            assertEquals(0, ring.drain(10, 1_000_000));
        }
    }
    @Test void exceptionsAndInvalidDataStayInTickets() {
        try (var ring = new ScriptSubmissionRing("scope", 1, Thread.currentThread(), 4, () -> {})) {
            var invalid = ring.submit(CONTRACT, List.of(new Object()), args -> 0);
            assertEquals(ScriptTicket.Status.REJECTED, invalid.status());
            var failed = ring.submit(CONTRACT, List.of(1), args -> { throw new IllegalArgumentException("failure"); });
            ring.drain(5, 1_000_000_000);
            assertEquals(ScriptTicket.Status.FAILED, failed.status());
            assertEquals("failure", failed.poll().orElseThrow().failure().message());
        }
    }
    @Test void closingDuringExecutionCancelsRunningAndQueuedTicketsWithoutWaiting() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ownerFailure = new AtomicReference<Throwable>();
        var ringRef = new AtomicReference<ScriptSubmissionRing>();
        Thread owner = new Thread(() -> {
            try { ringRef.get().drain(8, TimeUnit.SECONDS.toNanos(5)); }
            catch (Throwable failure) { ownerFailure.set(failure); }
        }, "ticket-test-owner");
        try (var ring = new ScriptSubmissionRing("scope", 3, owner, 4, () -> {})) {
            ringRef.set(ring);
            var running = ring.submit(CONTRACT, List.of(1), args -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return 2; });
            var queued = ring.submit(CONTRACT, List.of(2), args -> { fail("retired queued callback ran"); return 3; });
            assertThrows(IllegalStateException.class, () -> ring.drain(1, 1_000_000));
            owner.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(ScriptTicket.Status.RUNNING, running.status());
            ring.close();
            assertTrue(owner.isAlive(), "close must not wait for the running callback");
            assertEquals(ScriptTicket.Status.CANCELLED, running.status());
            assertEquals(ScriptTicket.Status.CANCELLED, queued.status());
            assertEquals(0, ring.pendingCount());
            release.countDown(); owner.join(5000);
            assertFalse(owner.isAlive()); assertNull(ownerFailure.get());
            assertEquals(ScriptTicket.Status.CANCELLED, running.poll().orElseThrow().status(), "late result must not overwrite cancellation");
        } finally { release.countDown(); owner.join(5000); }
    }
    @Test void submissionCopiesDataAndCompletionRunsOnTheRequestedExecutor() throws Exception {
        var contract = new ScriptContract(List.of(ScriptContract.Type.OBJECT), ScriptContract.Type.NUMBER);
        try (var ring = new ScriptSubmissionRing("scope", 1, Thread.currentThread(), 2, () -> {})) {
            var values = new ArrayList<>(List.of(1));
            var object = new HashMap<String, Object>(); object.put("values", values);
            var ticket = ring.submit(contract, List.of(object), args -> ((List<?>) ((Map<?, ?>) args.getFirst()).get("values")).getFirst());
            values.set(0, 99); object.clear();
            var completionQueue = new java.util.ArrayDeque<Runnable>();
            var completed = new AtomicInteger();
            ticket.onComplete(completionQueue::add, outcome -> { assertEquals(1, outcome.value()); completed.incrementAndGet(); });
            ring.drain(1, 1_000_000_000);
            assertEquals(1, ticket.poll().orElseThrow().value());
            assertEquals(0, completed.get());
            completionQueue.remove().run(); assertEquals(1, completed.get());
        }
    }
}
