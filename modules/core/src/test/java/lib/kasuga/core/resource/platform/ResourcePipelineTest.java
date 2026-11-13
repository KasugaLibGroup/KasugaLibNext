package lib.kasuga.core.resource.platform;

import lib.kasuga.core.resource.identity.ResourceUri;
import lib.kasuga.core.resource.lifecycle.*;
import lib.kasuga.core.resource.pack.ApprovedResourcePacks;
import lib.kasuga.core.resource.pack.McManagedResourceView;
import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.source.*;
import lib.kasuga.core.resource.view.*;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePipelineTest {
    private static ResourceSource source(String id, String root, int rank, InMemoryVfs vfs) {
        return ResourceSources.vfs(new ResourceSourceDescriptor("demo", id, root, rank, ResourceSourceDescriptor.Origin.EXTERNAL),
                vfs, false, "", "", InputPolicy.DEFAULT);
    }
    private static ResourceRouting plan() {
        return ResourceRouting.decode("""
                {"format_version":1,"bindings":[{"id":"root","source_id":"demo","schema_id":"minecraft_resource_schema"}]}
                """, ResourceRouting.builtinTemplates(), Set.of("demo"));
    }
    @Test void stableCapturedBytesAndLeasesSurviveSourceChangesThenExpire() throws Exception {
        try (var vfs = new InMemoryVfs("input"); var source = source("demo", "main", 1, vfs)) {
            vfs.writeString(VfsPath.of("model.txt"), "old");
            var snapshot = source.capture(); var file = snapshot.files().getFirst(); var lease = file.lease();
            vfs.writeString(VfsPath.of("model.txt"), "new"); snapshot.close();
            try (var stream = file.open(lease)) { assertEquals("old", new String(stream.readAllBytes(), StandardCharsets.UTF_8)); }
            assertThrows(IllegalStateException.class, file::open);
            lease.close(); assertThrows(IllegalStateException.class, () -> file.open(lease));
        }
    }
    @Test void schemasChooseStructuralChildBeforeSideAndDoNotFallBack() throws Exception {
        String json = """
                {"format_version":1,"bindings":[
                  {"id":"root","source_id":"demo","outputs":[{"id":"a","entry_point_id":"demo:a","when":"resource_reload","side":"both","target_prefix":"root"}]},
                  {"id":"child","source_id":"demo","source_prefix":"a/b","outputs":[{"id":"b","entry_point_id":"demo:b","when":"resource_reload","side":"server","target_prefix":"child"}]}]}
                """;
        var plan = ResourceRouting.decode(json, Map.of(), Set.of("demo"));
        try (var vfs = new InMemoryVfs("input"); var source = source("demo", "main", 1, vfs)) {
            vfs.writeString(VfsPath.of("a/b/one.txt"), "1"); vfs.writeString(VfsPath.of("a/bc/two.txt"), "2");
            try (var snapshot = source.capture()) {
                var catalog = new ResourceCatalog("scope", 1, List.of(snapshot));
                var routes = plan.route(catalog, ResourceEvent.RESOURCE_RELOAD, LogicalSide.CLIENT);
                assertEquals(List.of("root/a/bc/two.txt"), routes.stream().map(ResourceRouting.Route::targetPath).toList());
                assertEquals(2, plan.route(catalog, ResourceEvent.RESOURCE_RELOAD, LogicalSide.SERVER).size());
            }
        }
    }
    @Test void realPackDiscoveryAndManagerRetainBothRootProviders() throws Exception {
        try (var low = new InMemoryVfs("low"); var high = new InMemoryVfs("high"); var packs = new ApprovedResourcePacks()) {
            for (String directory : List.of("assets", "data")) {
                low.writeString(VfsPath.of(directory + "/demo/test.txt"), "low"); high.writeString(VfsPath.of(directory + "/demo/test.txt"), "high");
            }
            var a = source("demo", "base", 1, low); var b = source("demo", "override", 2, high);
            packs.register("demo", List.of(a, b), plan());
            for (PackType type : PackType.values()) {
            List<Pack> discovered = new ArrayList<>(); packs.repositorySource(type).loadPacks(discovered::add);
            assertEquals(2, discovered.size());
            try (var manager = new MultiPackResourceManager(type, discovered.stream().map(Pack::open).toList())) {
                var id = ResourceLocation.parse("demo:test.txt");
                assertEquals(List.of("low", "high"), manager.getResourceStack(id).stream().map(r -> {
                    try (var in = r.open()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }).toList());
                var borrowed = new McManagedResourceView(() -> manager, type);
                assertEquals(2, borrowed.stack(id).size());
                var captured = borrowed.capture("", id::equals, packId -> {
                    boolean base = packId.endsWith("/base");
                    return new ResourceSourceDescriptor("minecraft", "demo", base ? "base" : "override", base ? 1 : 2, ResourceSourceDescriptor.Origin.MC_MANAGED);
                }, InputPolicy.DEFAULT);
                try {
                    var catalog = new ResourceCatalog("borrowed", 1, captured);
                    assertEquals(2, catalog.stack(type.getDirectory() + "/demo/test.txt").size());
                    assertThrows(IllegalArgumentException.class, () -> plan().route(catalog, ResourceEvent.RESOURCE_RELOAD,
                            type == PackType.CLIENT_RESOURCES ? LogicalSide.CLIENT : LogicalSide.SERVER));
                } finally { for (var snapshot : captured) snapshot.close(); }
                try (var stream = manager.getResourceOrThrow(id).open()) {
                    assertEquals("high", new String(stream.readAllBytes(), StandardCharsets.UTF_8), "closing borrowed captures must not close MC packs");
                }
            }
            }
        }
    }
    @Test void failedConsumerPrepareKeepsPublishedGenerationAndClosesAbortedArtifacts() throws Exception {
        AtomicInteger attempts = new AtomicInteger(), closed = new AtomicInteger();
        try (var vfs = new InMemoryVfs("source"); var source = source("demo", "main", 1, vfs); var coordinator = new ResourceCoordinator("scope")) {
            vfs.writeString(VfsPath.of("a.txt"), "a");
            coordinator.register(new ResourceEntryPoint() {
                public EntryPointDescriptor descriptor() { return new EntryPointDescriptor("demo:a", Set.of(), Set.of(ResourceEvent.RESOURCE_RELOAD), Set.of(LogicalSide.CLIENT), Set.of(), true); }
                public java.util.concurrent.CompletionStage<PreparedConsumerBatch> prepare(ResourceBatch batch, InvocationContext ctx) {
                    if (attempts.incrementAndGet() == 2) return CompletableFuture.failedFuture(new IllegalArgumentException("bad input"));
                    return CompletableFuture.completedFuture(new PreparedConsumerBatch() { public Object value() { return "ready"; } public void close() { closed.incrementAndGet(); } });
                }
            });
            var routing = new ResourceRouting(List.of(new ResourceRouting.Binding("a", "demo", "", List.of(
                    new ResourceRouting.Output("a", "demo:a", ResourceEvent.RESOURCE_RELOAD, LogicalSide.CLIENT, "")))));
            var first = coordinator.reload(List.of(source), routing, ResourceEvent.RESOURCE_RELOAD, LogicalSide.CLIENT, Runnable::run, Runnable::run).join();
            assertThrows(java.util.concurrent.CompletionException.class, () -> coordinator.reload(List.of(source), routing, ResourceEvent.RESOURCE_RELOAD, LogicalSide.CLIENT, Runnable::run, Runnable::run).join());
            assertSame(first, coordinator.current().orElseThrow()); assertEquals(0, closed.get());
        }
        assertEquals(1, closed.get());
    }
    @Test void strictJsonRejectsDuplicateKeysUnknownFieldsAndUnauthorizedSources() {
        assertThrows(IllegalArgumentException.class, () -> ResourceRouting.decode("{\"format_version\":1,\"format_version\":1}", Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> ResourceRouting.decode("{\"format_version\":1,\"wat\":0}", Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> ResourceRouting.decode("{\"format_version\":1,\"bindings\":[{\"id\":\"x\",\"source_id\":\"hidden\"}]}", Map.of(), Set.of("demo")));
        assertThrows(IllegalArgumentException.class, () -> lib.kasuga.core.resource.script.ScriptReferenceCodec.decode(
                lib.kasuga.core.resource.routing.StrictResourceJson.parse("{\"package\":\"ksg://demo/roots/main/scripts/package.toml\",\"block\":\"call\",\"side\":\"client\"}"),
                "/callback", LogicalSide.CLIENT, ignored -> true));
    }
}
