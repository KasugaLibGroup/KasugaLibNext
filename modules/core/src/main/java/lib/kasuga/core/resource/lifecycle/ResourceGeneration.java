package lib.kasuga.core.resource.lifecycle;

import lib.kasuga.core.resource.source.ResourceSnapshot;
import lib.kasuga.core.resource.view.ResourceCatalog;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Refcounted runtime publication. Retirement revokes calls; final lease release frees old artifacts. */
public final class ResourceGeneration implements AutoCloseable {
    private final ResourceCatalog catalog;
    private final List<ResourceSnapshot> snapshots;
    private final Map<String, PreparedConsumerBatch> consumers;
    private int references = 1;
    private boolean retired;
    private final java.util.concurrent.Executor disposalExecutor;
    private final java.util.concurrent.CompletableFuture<Void> disposal = new java.util.concurrent.CompletableFuture<>();
    public ResourceGeneration(ResourceCatalog catalog, List<ResourceSnapshot> snapshots, Map<String, PreparedConsumerBatch> consumers) {
        this(catalog, snapshots, consumers, Runnable::run);
    }
    public ResourceGeneration(ResourceCatalog catalog, List<ResourceSnapshot> snapshots, Map<String, PreparedConsumerBatch> consumers,
                              java.util.concurrent.Executor disposalExecutor) {
        this.catalog = catalog; this.snapshots = List.copyOf(snapshots); this.consumers = Collections.unmodifiableMap(new LinkedHashMap<>(consumers));
        this.disposalExecutor = java.util.Objects.requireNonNull(disposalExecutor);
    }
    public java.util.concurrent.CompletionStage<Void> disposal() { return disposal.minimalCompletionStage(); }
    public ResourceCatalog catalog() { return catalog; }
    public Map<String, PreparedConsumerBatch> consumers() { return consumers; }
    public synchronized Lease lease() {
        if (retired) throw new IllegalStateException("STALE_REFERENCE: retired generation");
        references++; return new Lease();
    }
    public final class Lease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        public ResourceCatalog catalog() {
            if (closed.get()) throw new IllegalStateException("STALE_REFERENCE: generation lease closed");
            return catalog;
        }
        public Object consumer(String id) {
            catalog(); PreparedConsumerBatch batch = consumers.get(id);
            if (batch == null) throw new IllegalArgumentException("Unknown consumer: " + id);
            return batch.value();
        }
        @Override public void close() throws Exception { if (closed.compareAndSet(false, true)) release(); }
    }
    private void release() throws Exception {
        boolean dispose;
        synchronized (this) { dispose = --references == 0; }
        if (dispose) {
            try {
                disposalExecutor.execute(() -> {
                    try { dispose(); disposal.complete(null); }
                    catch (Exception e) { disposal.completeExceptionally(e); }
                });
            } catch (RuntimeException e) { disposal.completeExceptionally(e); throw e; }
        }
    }
    @Override public void close() throws Exception {
        synchronized (this) { if (retired) return; retired = true; }
        RuntimeException failure = null;
        for (PreparedConsumerBatch batch : consumers.values()) {
            try { batch.retire(); } catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        try { release(); } catch (Exception e) { if (failure == null) failure = new IllegalStateException(e); else failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }
    private void dispose() throws Exception {
        List<AutoCloseable> closeables = new ArrayList<>(snapshots);
        closeables.addAll(consumers.values()); Collections.reverse(closeables);
        Exception failure = null;
        for (AutoCloseable closeable : closeables) {
            try { closeable.close(); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
    }
}
