package lib.kasuga.rendering.models.uml.dynamic;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PublicationConcurrencyTest {
    @Test
    void bootstrapSubscriptionCloseDoesNotClaimThePublicationThread() throws Exception {
        var coordinator = new ModelPublicationCoordinator();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread bootstrap = new Thread(() -> {
            try { coordinator.onPrepare(ignored -> {}).close(); }
            catch (Throwable problem) { failure.set(problem); }
        });
        bootstrap.start(); bootstrap.join(5000);
        assertFalse(bootstrap.isAlive());
        assertNull(failure.get());
        assertDoesNotThrow(coordinator::checkThread, "bootstrap subscription teardown must not bind the game thread");
    }
    @Test
    void threadReleaseIsExplicitAndRejectedDuringPreparation() throws Exception {
        var coordinator = new ModelPublicationCoordinator();
        var publisher = coordinator.<String>publisher();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var subscription = coordinator.onPrepare(preparation -> {
            calls.incrementAndGet();
            assertThrows(IllegalStateException.class, coordinator::releaseThread);
            assertThrows(IllegalStateException.class, () -> coordinator.onPrepare(ignored -> {}));
        })) {
            publisher.replace(java.util.Map.of());
            AtomicReference<Throwable> foreign = new AtomicReference<>();
            Thread rejected = new Thread(() -> {
                try { coordinator.releaseThread(); }
                catch (Throwable failure) { foreign.set(failure); }
            });
            rejected.start(); rejected.join(5000);
            assertTrue(foreign.get() instanceof IllegalStateException);
            coordinator.releaseThread();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread replacement = new Thread(() -> {
                try { publisher.replace(java.util.Map.of()); coordinator.releaseThread(); }
                catch (Throwable problem) { failure.set(problem); }
            });
            replacement.start(); replacement.join(5000);
            assertFalse(replacement.isAlive());
            assertNull(failure.get());
            assertEquals(2, calls.get());
        }
        publisher.close();
        coordinator.releaseThread();
    }
    @Test
    void callbacksDoNotHoldTheCoordinatorMonitorWhileForeignRegistrationIsRejected() throws Exception {
        var coordinator = new ModelPublicationCoordinator();
        var publisher = coordinator.<String>publisher();
        AtomicReference<Throwable> rejected = new AtomicReference<>();
        try (var subscription = coordinator.onPrepare(preparation -> {
            Thread foreign = new Thread(() -> {
                try { coordinator.onPrepare(ignored -> {}); }
                catch (Throwable failure) { rejected.set(failure); }
            });
            foreign.start();
            try { foreign.join(1500); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
            assertFalse(foreign.isAlive(), "user callbacks must not hold a lock needed to reject other-thread calls");
        })) {
            publisher.replace(java.util.Map.of());
            assertTrue(rejected.get() instanceof IllegalStateException);
        }
        publisher.close();
    }
}
