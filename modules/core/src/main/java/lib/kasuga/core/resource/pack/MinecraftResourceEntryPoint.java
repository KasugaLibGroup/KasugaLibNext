package lib.kasuga.core.resource.pack;

import lib.kasuga.core.resource.lifecycle.*;
import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.source.ResourceSnapshot;
import lib.kasuga.core.resource.view.ResourceCatalog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.PackSource;
import java.util.*;
import java.util.concurrent.*;

/** Lifecycle participant for hosts that coordinate their own discovery/reload barrier. */
public final class MinecraftResourceEntryPoint implements ResourceEntryPoint {
    @Override public EntryPointDescriptor descriptor() {
        return new EntryPointDescriptor(ResourceRouting.MINECRAFT_ENTRY_POINT, Set.of(), Set.of(ResourceEvent.RESOURCE_RELOAD),
                Set.of(LogicalSide.CLIENT, LogicalSide.SERVER), Set.of(), true);
    }
    @Override public CompletionStage<? extends PreparedConsumerBatch> prepare(ResourceBatch batch, InvocationContext context) {
        try { return CompletableFuture.completedFuture(new Projection(batch.routes(), context)); }
        catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }
    public static final class Projection implements PreparedConsumerBatch {
        private final List<ResourceRouting.Route> routes;
        private final List<ResourceSnapshot.ResourceLease> leases = new ArrayList<>();
        private final InvocationContext context;
        private boolean closed;
        private Projection(List<ResourceRouting.Route> routes, InvocationContext context) {
            this.routes = List.copyOf(routes); this.context = context;
            try {
                for (var route : routes) {
                    if (route.targetPath().startsWith("assets/") || route.targetPath().startsWith("data/")) MappedPackResources.validateMinecraftPath(route.targetPath());
                    leases.add(route.file().lease());
                }
            } catch (RuntimeException error) { close(); throw error; }
        }
        public synchronized List<MappedPackResources> openLayers() {
            if (closed) throw new IllegalStateException("STALE_REFERENCE: projection closed");
            Map<String, List<ResourceRouting.Route>> layers = new LinkedHashMap<>();
            routes.stream().sorted(Comparator.comparing(ResourceRouting.Route::file, ResourceCatalog.ORDER)).forEach(route -> {
                var uri = route.file().descriptor().uri();
                layers.computeIfAbsent(uri.sourceId() + "/" + uri.rootId(), ignored -> new ArrayList<>()).add(route);
            });
            List<MappedPackResources> result = new ArrayList<>();
            try {
                for (var layer : layers.entrySet()) {
                    String id = "kasuga/" + context.scopeId() + "/" + layer.getKey();
                    var location = new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty());
                    result.add(new MappedPackResources(location, layer.getValue(), context.side() == LogicalSide.CLIENT ? PackType.CLIENT_RESOURCES : PackType.SERVER_DATA));
                }
                return List.copyOf(result);
            } catch (RuntimeException error) { result.forEach(MappedPackResources::close); throw error; }
        }
        @Override public Object value() { return this; }
        @Override public synchronized void close() {
            if (closed) return; closed = true;
            java.io.UncheckedIOException error = null;
            for (var lease : leases) try { lease.close(); }
            catch (java.io.IOException e) { if (error == null) error = new java.io.UncheckedIOException(e); else error.addSuppressed(e); }
            if (error != null) throw error;
        }
    }
}
