package lib.kasuga.core.resource.lifecycle;

import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.source.ResourceSource;
import lib.kasuga.core.resource.source.ResourceSnapshot;
import lib.kasuga.core.resource.view.ResourceCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Isolated prepare, one publication executor, latest-request wins. Never waits for script threads. */
public final class ResourceCoordinator implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceCoordinator.class);
    private final String scopeId;
    private final Map<String, ResourceEntryPoint> entries = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Object publicationLock = new Object();
    private volatile ResourceGeneration current;
    private volatile boolean closed;
    public ResourceCoordinator(String scopeId) { this.scopeId = Objects.requireNonNull(scopeId); }
    public synchronized void register(ResourceEntryPoint entry) {
        if (closed || entries.putIfAbsent(entry.descriptor().id(), entry) != null) throw new IllegalStateException("Duplicate/closed entry point registry");
    }
    public Optional<ResourceGeneration> current() { return Optional.ofNullable(current); }
    public boolean isClosed() { return closed; }

    public CompletableFuture<ResourceGeneration> reload(List<ResourceSource> sources, ResourceRouting plan, ResourceEvent event,
                                                          LogicalSide side, Executor prepareExecutor, Executor publishExecutor) {
        List<ResourceSource> approvedSources = List.copyOf(sources);
        long generation = sequence.incrementAndGet();
        InvocationContext context = new InvocationContext(scopeId, generation, event, side, prepareExecutor, publishExecutor);
        Map<String, ResourceEntryPoint> registry;
        synchronized (this) { registry = Map.copyOf(entries); }
        List<ResourceEntryPoint> order;
        try { order = validate(plan, registry, context); }
        catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
        List<ResourceSnapshot> snapshots = new ArrayList<>();
        Map<String, PreparedConsumerBatch> prepared = new LinkedHashMap<>();
        CompletableFuture<ResourceCatalog> captured = CompletableFuture.supplyAsync(() -> {
            ensureCurrent(generation);
            try {
                for (ResourceSource source : approvedSources) { ensureCurrent(generation); snapshots.add(source.capture()); }
                return new ResourceCatalog(scopeId, generation, snapshots);
            } catch (Exception e) { throw new CompletionException(e); }
        }, prepareExecutor);
        CompletableFuture<ResourceCatalog> ready = captured.thenCompose(catalog -> {
            var routes = plan.route(catalog, event, side);
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (ResourceEntryPoint entry : order) {
                List<ResourceRouting.Route> inputs = routes.stream().filter(r -> r.output().entryPointId().equals(entry.descriptor().id())).toList();
                LogicalSide consumerSide = inputs.isEmpty() ? side : inputs.getFirst().output().side();
                if (inputs.stream().anyMatch(r -> (r.output().side() == LogicalSide.NONE) != (consumerSide == LogicalSide.NONE)))
                    throw new IllegalArgumentException("UNSUPPORTED_LIFECYCLE: neutral/game outputs require separate entry points");
                for (var route : inputs) {
                    if (!entry.descriptor().inputTypes().isEmpty() && !entry.descriptor().inputTypes().contains(route.file().descriptor().type()))
                        throw new IllegalArgumentException("TYPE_MISMATCH: " + route.file().descriptor().uri());
                }
                InvocationContext invocation = consumerSide == LogicalSide.NONE ? context.neutral() : context;
                chain = chain.thenComposeAsync(ignored -> {
                    ensureCurrent(generation);
                    return entry.prepare(new ResourceBatch(catalog, inputs), invocation).thenAccept(batch -> {
                        if (batch == null) throw new IllegalStateException("Consumer returned no prepared batch");
                        prepared.put(entry.descriptor().id(), batch);
                    });
                }, prepareExecutor);
            }
            return chain.thenApply(ignored -> catalog);
        });
        CompletableFuture<ResourceGeneration> result = ready.thenApplyAsync(catalog -> {
            ensureCurrent(generation);
            ResourceGeneration replacement = new ResourceGeneration(catalog, snapshots, prepared, publishExecutor);
            try {
                for (PreparedConsumerBatch batch : prepared.values()) { ensureCurrent(generation); batch.publish(); }
                ResourceGeneration old;
                synchronized (publicationLock) {
                    ensureCurrent(generation); old = current; current = replacement;
                }
                if (old != null) try { old.close(); } catch (Exception e) { LOGGER.warn("Retired resource generation cleanup failed", e); }
                return replacement;
            } catch (Exception e) { throw new CompletionException(e); }
        }, publishExecutor);
        // Don't expose a cancellable future whose cancellation discards still-running preparation results.
        CompletableFuture<ResourceGeneration> settled = result.handleAsync((value, error) -> {
            if (error == null) return value;
            List<AutoCloseable> abort = new ArrayList<>(snapshots); abort.addAll(prepared.values()); Collections.reverse(abort);
            for (AutoCloseable object : abort) try { object.close(); } catch (Exception e) { error.addSuppressed(e); }
            throw new CompletionException(error);
        }, publishExecutor);
        // A caller may abandon its wait, but the private settlement stage must still release artifacts.
        return settled.copy();
    }

    private void ensureCurrent(long generation) {
        if (closed || sequence.get() != generation) throw new CancellationException("Resource scope closed or preparation superseded");
    }
    private static List<ResourceEntryPoint> validate(ResourceRouting plan, Map<String, ResourceEntryPoint> entries, InvocationContext context) {
        Set<String> active = new LinkedHashSet<>();
        for (var binding : plan.bindings()) for (var output : binding.outputs()) {
            ResourceEntryPoint entry = entries.get(output.entryPointId());
            if (entry == null) throw new IllegalArgumentException("UNKNOWN_ENTRY_POINT: " + output.entryPointId());
            EntryPointDescriptor d = entry.descriptor();
            if (!d.events().contains(output.when()) || !d.reloadable() && output.when() == ResourceEvent.RESOURCE_RELOAD)
                throw new IllegalArgumentException("UNSUPPORTED_LIFECYCLE: " + d.id());
            Set<LogicalSide> requested = output.side() == LogicalSide.BOTH ? Set.of(LogicalSide.CLIENT, LogicalSide.SERVER) : Set.of(output.side());
            if (!d.sides().containsAll(requested)) throw new IllegalArgumentException("UNSUPPORTED_LIFECYCLE: side: " + d.id());
            if (output.matches(context.event(), context.side())) active.add(d.id());
        }
        List<ResourceEntryPoint> order = new ArrayList<>(); Set<String> done = new HashSet<>(), visiting = new HashSet<>();
        for (String id : active) visit(id, entries, active, visiting, done, order);
        return order;
    }
    private static void visit(String id, Map<String, ResourceEntryPoint> entries, Set<String> active,
                              Set<String> visiting, Set<String> done, List<ResourceEntryPoint> order) {
        if (done.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("Consumer dependency cycle: " + id);
        ResourceEntryPoint entry = entries.get(id);
        if (entry == null || !active.contains(id)) throw new IllegalArgumentException("Missing active consumer dependency: " + id);
        for (String dependency : new TreeSet<>(entry.descriptor().dependencies())) visit(dependency, entries, active, visiting, done, order);
        visiting.remove(id); done.add(id); order.add(entry);
    }
    @Override public void close() throws Exception {
        ResourceGeneration old;
        synchronized (publicationLock) { closed = true; sequence.incrementAndGet(); old = current; current = null; }
        if (old != null) old.close();
    }
}
