package lib.kasuga.core.vfs;

import lib.kasuga.core.resource.ResourceSystem;
import lib.kasuga.core.resource.ScopedResourceManager;
import lib.kasuga.core.resource.pack.ScopedPackResources;
import lib.kasuga.core.resource.pack.ScopedVanillaFileResourcePack;
import lib.kasuga.core.resource.pack.ScopedVanillaPathResourcePack;
import lib.kasuga.core.resource.pack.VfsBackedPackResources;
import lib.kasuga.core.resource.pack.VfsPackResourcesBridge;
import lib.kasuga.core.resource.pack.VfsScopedPackResourcesAdapter;
import lib.kasuga.core.vfs.VirtualFileSystem;
import lib.kasuga.core.vfs.backend.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ResourceFilterSection;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VfsReviewRegressionTest {
    @TempDir Path tempDir;

    private static VfsPath p(String path) { return VfsPath.of(path); }

    private VirtualFileSystem writable(String backend) throws IOException {
        return backend.equals("memory") ? new InMemoryVfs("memory") : new DirectoryVfs("disk", tempDir.resolve("disk"));
    }

    private static PackLocationInfo packInfo() {
        return new PackLocationInfo("regression", Component.literal("Regression"), PackSource.BUILT_IN, Optional.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "disk"})
    void movingOntoAnAncestorRejectsBeforeChangingAnything(String backend) throws IOException {
        try (var vfs = writable(backend)) {
            vfs.writeString(p("a/b/file.txt"), "source");
            vfs.writeString(p("a/sibling.txt"), "sibling");
            assertThrows(IOException.class, () -> vfs.move(p("a/b"), p("a")));
            assertEquals("source", vfs.readString(p("a/b/file.txt")));
            assertEquals("sibling", vfs.readString(p("a/sibling.txt")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memoryDeleteIncludesAllDescendantsDespiteInterleavedNames(boolean transaction) throws IOException {
        try (var vfs = new InMemoryVfs("memory")) {
            vfs.writeString(p("a/child.txt"), "child");
            vfs.writeString(p("a-b"), "dash");
            vfs.writeString(p("a.b"), "dot");
            if (transaction) {
                try (var tx = vfs.beginTransaction()) { tx.delete(p("a")).commit(); }
            } else { vfs.delete(p("a")); }
            assertFalse(vfs.exists(p("a/child.txt")), "No orphan descendants after delete");
            assertEquals(List.of(p("a-b"), p("a.b")), vfs.walk(VfsPath.ROOT));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memoryMoveIncludesAllDescendantsDespiteInterleavedNames(boolean transaction) throws IOException {
        try (var vfs = new InMemoryVfs("memory")) {
            vfs.writeString(p("a/child.txt"), "child");
            vfs.mkdirs(p("a/empty"));
            vfs.writeString(p("a-b"), "dash");
            if (transaction) {
                try (var tx = vfs.beginTransaction()) { tx.move(p("a"), p("moved")).commit(); }
            } else { vfs.move(p("a"), p("moved")); }
            assertFalse(vfs.exists(p("a/child.txt")));
            assertEquals("child", vfs.readString(p("moved/child.txt")));
            assertTrue(vfs.isDirectory(p("moved/empty")));
            assertEquals("dash", vfs.readString(p("a-b")));
        }
    }

    @Test
    void diskTransactionRestoresOverwritesDeletesMovesAndDirectoriesAfterIoFailure() throws IOException {
        Path root = tempDir.resolve("journal");
        try (var vfs = new DirectoryVfs("disk", root)) {
            vfs.writeString(p("keep.txt"), "original");
            vfs.writeString(p("deleted/sub/data.txt"), "deleted-data");
            vfs.mkdirs(p("deleted/empty"));
            vfs.writeString(p("source.txt"), "source-data");
            List<VfsChangeEvent> events = new ArrayList<>();
            vfs.watchAll(events::add);
            try (var tx = vfs.beginTransaction()) {
                tx.putString(p("keep.txt"), "overwritten").delete(p("deleted"))
                        .move(p("source.txt"), p("new/moved.txt"))
                        .putString(p("added.txt"), "partial")
                        .putString(p("x".repeat(300)), "must-fail-on-disk");
                assertThrows(IOException.class, tx::commit);
            }
            assertAll(
                    () -> assertEquals("original", vfs.readString(p("keep.txt"))),
                    () -> assertEquals("deleted-data", vfs.readString(p("deleted/sub/data.txt"))),
                    () -> assertTrue(vfs.isDirectory(p("deleted/empty"))),
                    () -> assertEquals("source-data", vfs.readString(p("source.txt"))),
                    () -> assertFalse(vfs.exists(p("new"))),
                    () -> assertFalse(vfs.exists(p("added.txt"))),
                    () -> assertTrue(events.isEmpty(), "Failed transactions must publish no events"),
                    () -> assertTrue(vfs.pollExternalChanges().isEmpty()));
            assertNoTemporaryFiles(root);
        }
    }

    @Test
    void recoveryPreservesDirectoryPermissions() throws IOException {
        Path root = tempDir.resolve("permissions");
        Files.createDirectories(root.resolve("private/sub"));
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        var permissions = java.nio.file.attribute.PosixFilePermissions.fromString("r-x------");
        Files.setPosixFilePermissions(root.resolve("private"), permissions);
        try (var vfs = new DirectoryVfs("disk", root)) {
            try (var tx = vfs.beginTransaction()) {
                tx.move(p("private"), p("relocated")).mkdirs(p("private/sub"))
                        .putString(p("x".repeat(300)), "must-fail");
                assertThrows(IOException.class, tx::commit);
            }
            assertEquals(permissions, Files.getPosixFilePermissions(root.resolve("private")));
            assertTrue(vfs.isDirectory(p("private/sub")));
            assertFalse(vfs.exists(p("relocated")));
            assertNoTemporaryFiles(root);
        }
        // Let the test framework remove the deliberately read-only fixture directory.
        Files.setPosixFilePermissions(root.resolve("private"), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void multiMountTransactionDoesNotCommitFirstBackendWhenSecondBackendFails() throws IOException {
        try (var a = new InMemoryVfs("a"); var b = new InMemoryVfs("b"); var table = new MountTableVfs("table")) {
            table.mount(p("a"), a); table.mount(p("b"), b);
            a.writeString(p("keep"), "original");
            List<VfsChangeEvent> events = new ArrayList<>();
            table.watchAll(events::add);
            a.watchAll(events::add);
            try (var tx = table.beginTransaction()) {
                tx.putString(p("a/keep"), "partial").move(p("b/missing"), p("b/destination"));
                assertThrows(IOException.class, tx::commit);
            }
            assertEquals("original", a.readString(p("keep")));
            assertTrue(events.isEmpty());
        }
    }

    @Test
    void multiMountIoFailureRestoresAlreadyAppliedBackendWithoutPublishing() throws IOException {
        Path root = tempDir.resolve("mount-disk");
        try (var a = new InMemoryVfs("a"); var b = new DirectoryVfs("b", root); var table = new MountTableVfs("table")) {
            table.mount(p("a"), a); table.mount(p("b"), b);
            a.writeString(p("keep"), "original-a"); b.writeString(p("keep"), "original-b");
            List<VfsChangeEvent> events = new ArrayList<>();
            table.watchAll(events::add); a.watchAll(events::add); b.watchAll(events::add);
            try (var tx = table.beginTransaction()) {
                tx.putString(p("a/keep"), "partial-a").move(p("a/keep"), p("b/moved"))
                        .putString(p("b/keep"), "partial-b")
                        .putString(p("b/" + "x".repeat(300)), "must-fail");
                assertThrows(IOException.class, tx::commit);
            }
            assertAll(() -> assertEquals("original-a", a.readString(p("keep"))),
                    () -> assertEquals("original-b", b.readString(p("keep"))),
                    () -> assertFalse(b.exists(p("moved"))),
                    () -> assertTrue(events.isEmpty()), () -> assertTrue(b.pollExternalChanges().isEmpty()));
            assertNoTemporaryFiles(root);
        }
    }

    @Test
    void successfulMultiMountObserversSeeAllChangesWithoutParticipantLocks() throws Exception {
        try (var a = new InMemoryVfs("a"); var b = new InMemoryVfs("b"); var table = new MountTableVfs("table")) {
            table.mount(p("a"), a); table.mount(p("b"), b);
            var executor = Executors.newSingleThreadExecutor();
            List<String> observed = new ArrayList<>();
            List<VfsChangeEvent> tableEvents = new ArrayList<>();
            table.watchAll(tableEvents::add);
            try {
                a.watchAll(event -> {
                    try {
                        observed.add(executor.submit(() -> b.readString(p("value"))).get(2, TimeUnit.SECONDS));
                    } catch (Exception failure) { observed.add(failure.getClass().getSimpleName()); }
                });
                try (var tx = table.beginTransaction()) {
                    VfsChangeEvent receipt = tx.putString(p("a/value"), "A").putString(p("b/value"), "B")
                            .mkdirs(p("a/staged/empty"))
                            .putString(p("a/staged/file"), "staged-before-move")
                            .move(p("a/staged"), p("b/moved"))
                            .move(p("b/moved/file"), p("a/returned"))
                            .commit();
                    assertEquals(List.of(receipt), tableEvents, "A unified commit must publish one coherent event");
                }
                assertEquals(List.of("B"), observed);
                assertEquals("staged-before-move", a.readString(p("returned")));
                assertTrue(b.isDirectory(p("moved/empty")));
                assertFalse(a.exists(p("staged")));
                assertFalse(b.exists(p("moved/file")));
            } finally { executor.shutdownNow(); }
        }
    }

    @Test
    void scopedDiskPackAcceptsDotWorkspacePathsWithoutAllowingEscape() throws IOException {
        Path root = tempDir.resolve("pack");
        Files.createDirectories(root.resolve("scripts/packages/child"));
        Files.writeString(root.resolve("scripts/packages/child/package.toml"), "name='child'");
        try (var pack = new PathPackResources(packInfo(), root)) {
            var scoped = new ScopedVanillaPathResourcePack(root, pack);
            assertEquals(List.of("child"), scoped.list("scripts", "./packages"));
            assertTrue(scoped.exists("scripts/./packages/child", "./package.toml"));
            assertThrows(IllegalArgumentException.class, () -> ScopedPackResources.safeResolve(root, "scripts", "../../outside"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"pack\":{\"description\":\"ok\",\"pack_format\":34},\"filter\":0}", "{broken-json"})
    void invalidOptionalMetadataReturnsNullLikeVanilla(String metadata) throws IOException {
        try (var vfs = new InMemoryVfs("metadata")) {
            vfs.writeString(p("pack.mcmeta"), metadata);
            var bridge = new VfsPackResourcesBridge(vfs, packInfo());
            assertNull(bridge.getMetadataSection(ResourceFilterSection.TYPE));
            if (metadata.contains("pack_format")) {
                assertNotNull(bridge.getMetadataSection(PackMetadataSection.TYPE));
            }
        }
    }

    @Test
    void archiveRetainsMinecraftNamesThatAreWindowsDeviceNames() throws IOException {
        Path zipPath = tempDir.resolve("devices.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (String name : List.of("assets/demo/models/con.json", "assets/aux/models/item.json")) {
                out.putNextEntry(new ZipEntry(name)); out.write(name.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
        }
        try (var zip = new ZipVfs("archive", zipPath); var zipFile = new ZipFile(zipPath.toFile())) {
            var scoped = new ScopedVanillaFileResourcePack(zipFile);
            assertTrue(scoped.exists("assets/demo", "models/con.json"));
            assertEquals(List.of("con.json"), scoped.list("assets/demo", "models"));
            var bridge = new VfsPackResourcesBridge(zip, packInfo());
            assertTrue(bridge.getNamespaces(PackType.CLIENT_RESOURCES).contains("aux"));
            var resource = bridge.getResource(PackType.CLIENT_RESOURCES, ResourceLocation.fromNamespaceAndPath("demo", "models/con.json"));
            assertNotNull(resource);
            try (var in = resource.get()) { assertEquals("assets/demo/models/con.json", new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
        }
    }

    @Test
    void crossMountMovePreservesNestedEmptyDirectories() throws IOException {
        try (var a = new InMemoryVfs("a"); var b = new DirectoryVfs("b", tempDir.resolve("destination")); var table = new MountTableVfs("table")) {
            table.mount(p("a"), a); table.mount(p("b"), b);
            a.mkdirs(p("tree/empty/nested")); a.writeString(p("tree/file"), "data");
            table.move(p("a/tree"), p("b/tree"));
            assertAll(() -> assertFalse(a.exists(p("tree"))), () -> assertTrue(b.isDirectory(p("tree/empty/nested"))),
                    () -> assertEquals("data", b.readString(p("tree/file"))));
        }
    }

    @Test
    void closingOldHandleDoesNotUnmountReplacement() throws IOException {
        try (var a = new InMemoryVfs("a"); var b = new InMemoryVfs("b"); var table = new MountTableVfs("table")) {
            var old = table.mount(p("runtime"), a); table.unmount(p("runtime"));
            var replacement = table.mount(p("runtime"), b); b.writeString(p("value"), "replacement");
            old.close(); old.close();
            assertFalse(replacement.isClosed());
            assertEquals("replacement", table.readString(p("runtime/value")));
        }
    }

    @Test
    void danglingLinkDoesNotPreventWalkingOrPollingOtherFiles() throws IOException {
        Path root = tempDir.resolve("links");
        try (var vfs = new DirectoryVfs("links", root)) {
            try { Files.createSymbolicLink(root.resolve("dangling"), Path.of("missing")); }
            catch (IOException | UnsupportedOperationException e) { assumeTrue(false, "Host cannot create symlinks: " + e); }
            Files.writeString(root.resolve("real.txt"), "changed");
            assertEquals(List.of(p("real.txt")), vfs.walk(VfsPath.ROOT));
            assertTrue(vfs.pollExternalChanges().orElseThrow().changes().stream().anyMatch(c -> c.path().equals(p("real.txt"))));
            assertTrue(vfs.pollExternalChanges().isEmpty());
        }
        // The same directory must also be mountable with an already dangling link.
        try (var vfs = new DirectoryVfs("links-again", root)) { assertEquals("changed", vfs.readString(p("real.txt"))); }
    }

    @Test
    void compositePollForwardsEachExternalChangeExactlyOnce() throws IOException {
        Path root = tempDir.resolve("overlay");
        try (var dir = new DirectoryVfs("dir", root); var composite = new CompositeVfs("overlay", List.of(dir), VfsConflictPolicy.FIRST_WINS)) {
            List<VfsChangeEvent> events = new ArrayList<>(); composite.watchAll(events::add);
            Files.writeString(root.resolve("external.txt"), "external");
            assertTrue(composite.pollExternalChanges().isPresent());
            assertEquals(1, events.size());
            assertTrue(composite.pollExternalChanges().isEmpty());
            assertEquals(1, events.size());
        }
    }

    @Test
    void externalEmptyDirectoryCreationMoveAndDeletionAreReported() throws IOException {
        Path root = tempDir.resolve("directories");
        try (var dir = new DirectoryVfs("dir", root)) {
            Files.createDirectory(root.resolve("old"));
            assertDirectoryChange(dir, "old", VfsChangeEvent.ChangeKind.CREATED);
            Files.move(root.resolve("old"), root.resolve("new"));
            var moved = dir.pollExternalChanges().orElseThrow();
            assertTrue(moved.affects(p("old"))); assertTrue(moved.affects(p("new")));
            Files.delete(root.resolve("new"));
            assertDirectoryChange(dir, "new", VfsChangeEvent.ChangeKind.DELETED);
            assertTrue(dir.pollExternalChanges().isEmpty());
        }
    }

    @Test
    void vfsAndZipScopedPacksAcceptDotWorkspacePathsAndRejectEmptyOrTraversalSegments() throws IOException {
        Path zipPath = tempDir.resolve("dot-workspace.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (var entry : List.of(
                    Map.entry("scripts/package.toml", "name='root'"),
                    Map.entry("scripts/packages/child/package.toml", "name='child'")
            )) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        try (var zipFile = new ZipFile(zipPath.toFile()); var memory = new InMemoryVfs("mem")) {
            memory.writeString(p("scripts/package.toml"), "name='root'");
            memory.writeString(p("scripts/packages/child/package.toml"), "name='child'");
            var packs = List.of(
                    new ScopedVanillaFileResourcePack(zipFile),
                    new VfsScopedPackResourcesAdapter(memory)
            );
            for (var scoped : packs) {
                assertEquals(List.of("child"), scoped.list("scripts", "./packages"));
                assertTrue(scoped.exists("scripts/./packages/child", "./package.toml"));
                try (var in = scoped.open("scripts/./packages/child", "./package.toml")) {
                    assertEquals("name='child'", new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                List<String> entries = scoped.listEntries("./scripts").toList();
                assertTrue(entries.contains("packages/child/package.toml"));
                assertThrows(IllegalArgumentException.class, () -> scoped.exists("scripts", ".//a"));
                assertThrows(IllegalArgumentException.class, () -> scoped.exists("scripts", "./../outside"));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> ScopedPackResources.safeResolve(tempDir, ".//a"));
        assertThrows(IllegalArgumentException.class, () -> ScopedPackResources.safeResolve(tempDir, "a//."));
    }

    @Test
    void resourceSystemIsolatesServerScopedMountsAndCloseablesFromClientAndOtherServers() throws Exception {
        ResourceSystem resourceSystem = new ResourceSystem();
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        MinecraftServer server1 = (MinecraftServer) unsafe.allocateInstance(DedicatedServer.class);
        MinecraftServer server2 = (MinecraftServer) unsafe.allocateInstance(DedicatedServer.class);

        InMemoryVfs server1Vfs = new InMemoryVfs("s1");
        InMemoryVfs server2Vfs = new InMemoryVfs("s2");
        InMemoryVfs clientVfs = new InMemoryVfs("client");
        server1Vfs.writeString(p("a.txt"), "s1");
        server2Vfs.writeString(p("b.txt"), "s2");
        clientVfs.writeString(p("c.txt"), "client");

        AtomicBoolean s1Closed = new AtomicBoolean();
        AtomicBoolean s2Closed = new AtomicBoolean();
        AtomicBoolean clientClosed = new AtomicBoolean();

        resourceSystem.registerManagedCloseable(server1, () -> s1Closed.set(true));
        resourceSystem.registerManagedCloseable(server2, () -> s2Closed.set(true));
        resourceSystem.registerClientManagedCloseable(() -> clientClosed.set(true));

        resourceSystem.mountScoped(server1, p("s1"), server1Vfs, true);
        resourceSystem.mountScoped(server2, p("s2"), server2Vfs, true);
        resourceSystem.mountClientScoped(p("client"), clientVfs, true);

        MountTableVfs unified = resourceSystem.getUnifiedVfs();
        assertEquals("s1", unified.readString(p("s1/a.txt")));
        assertEquals("s2", unified.readString(p("s2/b.txt")));
        assertEquals("client", unified.readString(p("client/c.txt")));

        resourceSystem.closeManagedResources(server1);
        assertTrue(s1Closed.get());
        assertFalse(unified.exists(p("s1/a.txt")));
        assertThrows(IOException.class, () -> server1Vfs.writeString(p("closed.txt"), "x"));

        assertFalse(s2Closed.get());
        assertFalse(clientClosed.get());
        assertEquals("s2", unified.readString(p("s2/b.txt")));
        assertEquals("client", unified.readString(p("client/c.txt")));

        resourceSystem.closeClientManagedResources();
        assertTrue(clientClosed.get());
        assertFalse(unified.exists(p("client/c.txt")));
        assertThrows(IOException.class, () -> clientVfs.writeString(p("closed.txt"), "x"));
        assertFalse(s2Closed.get());
        assertEquals("s2", unified.readString(p("s2/b.txt")));

        resourceSystem.closeManagedResources(server2);
        assertTrue(s2Closed.get());
        assertFalse(unified.exists(p("s2/b.txt")));
        assertThrows(IOException.class, () -> server2Vfs.writeString(p("closed.txt"), "x"));
    }

    @Test
    void scopedResourceManagerAdaptsVfsBackedPackResourcesBeforePathPackResources() throws IOException {
        InMemoryVfs vfs = new InMemoryVfs("vfs-backed-dummy-path");
        vfs.writeString(p("assets/demo/hello.txt"), "world");
        PackLocationInfo info = new PackLocationInfo("demo_pack", Component.literal("Demo"), PackSource.BUILT_IN, Optional.empty());

        class DummyPathVfsPack extends PathPackResources implements VfsBackedPackResources {
            DummyPathVfsPack() {
                super(info, Path.of("dummy-nonexistent-path"));
            }

            @Override
            public VirtualFileSystem getVfs() {
                return vfs;
            }
        }

        try (DummyPathVfsPack pack = new DummyPathVfsPack()) {
            ResourceManager proxyRm = (ResourceManager) Proxy.newProxyInstance(
                    ResourceManager.class.getClassLoader(),
                    new Class<?>[]{ResourceManager.class},
                    (proxy, method, args) -> method.getName().equals("listPacks") ? Stream.of(pack) : null
            );
            ScopedResourceManager scopedRm = new ScopedResourceManager(proxyRm);
            scopedRm.reload(proxyRm);

            assertEquals(1, scopedRm.getPackResources().size());
            ScopedPackResources adapted = scopedRm.getPackResources().getFirst();
            assertTrue(adapted instanceof VfsScopedPackResourcesAdapter);
            assertTrue(adapted.exists("assets/demo", "hello.txt"));
            try (var in = adapted.open("assets/demo", "hello.txt")) {
                assertEquals("world", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally {
            vfs.close();
        }
    }

    private static void assertDirectoryChange(DirectoryVfs dir, String path, VfsChangeEvent.ChangeKind kind) throws IOException {
        var changes = dir.pollExternalChanges().orElseThrow().changes();
        assertTrue(changes.stream().anyMatch(c -> c.path().equals(p(path)) && c.kind() == kind));
    }

    private static void assertNoTemporaryFiles(Path root) throws IOException {
        try (var files = Files.walk(root)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".ksgvfs-"))); }
    }
}
