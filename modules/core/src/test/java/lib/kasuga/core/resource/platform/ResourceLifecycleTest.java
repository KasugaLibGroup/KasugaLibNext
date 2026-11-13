package lib.kasuga.core.resource.platform;

import lib.kasuga.core.resource.lifecycle.*;
import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.view.ResourceCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ResourceLifecycleTest {
    private static final ResourceRouting PLAN = new ResourceRouting(List.of(new ResourceRouting.Binding("test", "demo", "", List.of(
            new ResourceRouting.Output("test", "demo:test", ResourceEvent.RESOURCE_RELOAD, LogicalSide.CLIENT, "")))));
    private static ResourceEntryPoint entry(CompletableFuture<PreparedConsumerBatch> preparation) {
        return new ResourceEntryPoint() {
            public EntryPointDescriptor descriptor() { return new EntryPointDescriptor("demo:test", Set.of(), Set.of(ResourceEvent.RESOURCE_RELOAD), Set.of(LogicalSide.CLIENT), Set.of(), true); }
            public CompletionStage<PreparedConsumerBatch> prepare(ResourceBatch batch, InvocationContext context) { return preparation; }
        };
    }
    @Test void cancellingReturnedFutureCannotSuppressAbortedPreparationCleanup() throws Exception {
        var prepared = new CompletableFuture<PreparedConsumerBatch>();
        var ownerQueue = new ArrayDeque<Runnable>();
        var closed = new AtomicInteger();
        try (var coordinator = new ResourceCoordinator("scope")) {
            coordinator.register(entry(prepared));
            var returned = coordinator.reload(List.of(), PLAN, ResourceEvent.RESOURCE_RELOAD, LogicalSide.CLIENT, Runnable::run, ownerQueue::add);
            assertTrue(returned.cancel(false));
            coordinator.close();
            prepared.complete(new PreparedConsumerBatch() {
                public Object value() { return "unused"; }
                public void close() { closed.incrementAndGet(); }
            });
            while (!ownerQueue.isEmpty()) ownerQueue.remove().run();
            assertEquals(1, closed.get(), "caller cancellation must not detach internal cleanup");
            assertTrue(coordinator.current().isEmpty());
        }
    }
    @Test void supersededPreparationClosesOnPublicationExecutorAndCannotReplaceNewerGeneration() throws Exception {
        var preparations = new ArrayDeque<CompletableFuture<PreparedConsumerBatch>>();
        var oldPreparation = new CompletableFuture<PreparedConsumerBatch>();
        preparations.add(oldPreparation);
        preparations.add(CompletableFuture.completedFuture(new PreparedConsumerBatch() { public Object value() { return "new"; } public void close() {} }));
        var ownerQueue = new ArrayDeque<Runnable>();
        var closed = new AtomicInteger();
        try (var coordinator = new ResourceCoordinator("scope")) {
            coordinator.register(new ResourceEntryPoint() {
                public EntryPointDescriptor descriptor() { return entry(oldPreparation).descriptor(); }
                public CompletionStage<PreparedConsumerBatch> prepare(ResourceBatch batch, InvocationContext context) { return preparations.remove(); }
            });
            var old = coordinator.reload(List.of(), PLAN, ResourceEvent.RESOURCE_RELOAD, LogicalSide.CLIENT, Runnable::run, ownerQueue::add);
            var fresh = coordinator.reload(List.of(), PLAN, ResourceEvent.RESOURCE_RELOAD, LogicalSide.CLIENT, Runnable::run, ownerQueue::add);
            while (!ownerQueue.isEmpty()) ownerQueue.remove().run();
            assertTrue(fresh.isDone());
            oldPreparation.complete(new PreparedConsumerBatch() {
                public Object value() { return "old"; }
                public void close() { closed.incrementAndGet(); }
            });
            assertEquals(0, closed.get(), "cleanup must await the publication executor");
            while (!ownerQueue.isEmpty()) ownerQueue.remove().run();
            assertThrows(CompletionException.class, old::join);
            assertEquals(1, closed.get());
            assertSame(fresh.join(), coordinator.current().orElseThrow());
        }
        while (!ownerQueue.isEmpty()) ownerQueue.remove().run();
    }
    @Test void lastLeaseSchedulesDisposalOnOwnerAndRetirementIsImmediate() throws Exception {
        var ownerQueue = new ArrayDeque<Runnable>();
        var retired = new AtomicInteger();
        var closed = new AtomicInteger();
        var generation = new ResourceGeneration(new ResourceCatalog("scope", 1, List.of()), List.of(), Map.of("test", new PreparedConsumerBatch() {
            public Object value() { return "value"; }
            public void retire() { retired.incrementAndGet(); }
            public void close() { closed.incrementAndGet(); }
        }), ownerQueue::add);
        var lease = generation.lease();
        generation.close();
        assertEquals(1, retired.get());
        assertEquals("value", lease.consumer("test"));
        assertThrows(IllegalStateException.class, generation::lease);
        lease.close();
        assertEquals(0, closed.get());
        assertFalse(generation.disposal().toCompletableFuture().isDone());
        ownerQueue.remove().run();
        assertEquals(1, closed.get());
        assertTrue(generation.disposal().toCompletableFuture().isDone());
    }
}
