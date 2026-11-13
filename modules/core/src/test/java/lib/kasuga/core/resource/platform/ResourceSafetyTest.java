package lib.kasuga.core.resource.platform;

import lib.kasuga.core.resource.identity.*;
import lib.kasuga.core.resource.lifecycle.*;
import lib.kasuga.core.resource.routing.*;
import lib.kasuga.core.resource.source.*;
import lib.kasuga.core.resource.view.*;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceSafetyTest {
    private static ResourceSourceDescriptor descriptor(String root, int rank) {
        return new ResourceSourceDescriptor("owner", "demo", root, rank, ResourceSourceDescriptor.Origin.EXTERNAL);
    }
    private static byte[] zip(String... namesAndValues) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndValues.length; i += 2) {
                output.putNextEntry(new ZipEntry(namesAndValues[i])); output.write(namesAndValues[i + 1].getBytes(StandardCharsets.UTF_8)); output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static int central(byte[] bytes) {
        for (int i = 0; i + 4 < bytes.length; i++) if (ByteBuffer.wrap(bytes, i, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == 0x02014b50) return i;
        throw new AssertionError("fixture central header missing");
    }
    @Test void archiveIsReopenedAfterReplacementAndOldBytesRemainLeased(@TempDir Path directory) throws Exception {
        Path archive = directory.resolve("models.jar"); Files.write(archive, zip("model.txt", "old"));
        try (var source = ResourceSources.archive(descriptor("main", 1), archive, InputPolicy.DEFAULT); var old = source.capture()) {
            Files.write(archive, zip("model.txt", "new"));
            try (var fresh = source.capture()) {
                assertEquals("old", new String(old.files().getFirst().readBytes(), StandardCharsets.UTF_8));
                assertEquals("new", new String(fresh.files().getFirst().readBytes(), StandardCharsets.UTF_8));
            }
        }
    }
    @Test void strictArchivesRejectDuplicateTraversalSymlinkAndBadCrc(@TempDir Path directory) throws Exception {
        byte[] duplicate = zip("a.txt", "a", "b.txt", "b");
        for (int i = 0; i + 5 <= duplicate.length; i++) if (new String(duplicate, i, 5, StandardCharsets.US_ASCII).equals("b.txt")) duplicate[i] = 'a';
        byte[] link = zip("link", "target"); int linkHeader = central(link);
        ByteBuffer.wrap(link).order(ByteOrder.LITTLE_ENDIAN).putShort(linkHeader + 4, (short) ((3 << 8) | 20)).putInt(linkHeader + 38, 0120777 << 16);
        byte[] crc = zip("a.txt", "content"); ByteBuffer.wrap(crc).order(ByteOrder.LITTLE_ENDIAN).putInt(central(crc) + 16, 123);
        List<byte[]> bad = List.of(duplicate, link, crc, zip("../escape", "bad"), new byte[]{1,2,3});
        for (int i = 0; i < bad.size(); i++) {
            Path archive = directory.resolve("bad" + i + ".zip"); Files.write(archive, bad.get(i));
            assertThrows(Exception.class, () -> {
                try (var source = ResourceSources.archive(descriptor("main", 1), archive, InputPolicy.DEFAULT); var ignored = source.capture()) {}
            });
        }
    }
    @Test void strictDirectoriesRejectEvenInternalLinks(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("real.txt"), "content"); Files.createSymbolicLink(directory.resolve("link.txt"), Path.of("real.txt"));
        assertThrows(IOException.class, () -> ResourceSources.directory(descriptor("main", 1), directory, InputPolicy.DEFAULT));
    }
    @Test void capturesEnforceActualByteBudgetsAndSpoolLeases() throws Exception {
        var policy = new InputPolicy(8, 16, 32, 2, 4, 0);
        try (var vfs = new InMemoryVfs("limits"); var source = ResourceSources.vfs(descriptor("main", 1), vfs, false, "", "", policy)) {
            for (String name : List.of("a.json", "a.JSON", "unnamed")) {
                vfs.writeString(VfsPath.of(name), "{\"long\":1}"); assertThrows(IOException.class, source::capture);
                vfs.delete(VfsPath.of(name));
            }
            vfs.writeString(VfsPath.of("a.txt"), "small");
            var snapshot = source.capture(); var file = snapshot.files().getFirst(); var lease = file.lease(); snapshot.close();
            try (var stream = file.open(lease)) { assertEquals("small", new String(stream.readAllBytes(), StandardCharsets.UTF_8)); }
            lease.close(); assertThrows(IllegalStateException.class, () -> file.open(lease));
        }
    }
    @Test void visibilityPrecedesOverlaySelectionAndWrongWinnerTypeDoesNotFallThrough() throws Exception {
        try (var a = new InMemoryVfs("a"); var b = new InMemoryVfs("b");
             var low = ResourceSources.vfs(descriptor("base", 1), a, false, "", "", InputPolicy.DEFAULT);
             var high = ResourceSources.vfs(descriptor("top", 2), b, false, "", "", InputPolicy.DEFAULT)) {
            a.writeString(VfsPath.of("same.txt"), "low"); b.writeBytes(VfsPath.of("same.txt"), new byte[]{0,1});
            try (var first = low.capture(); var second = high.capture()) {
                var catalog = new ResourceCatalog("scope", 1, List.of(first, second));
                assertThrows(IllegalArgumentException.class, () -> catalog.find(new ResourceKey("same.txt", "text")));
                assertEquals("base", catalog.find(new ResourceKey("same.txt", "text"), d -> d.uri().rootId().equals("base")).orElseThrow().descriptor().uri().rootId());
                var grant = new ResourceAccess.Grant("owner", "demo", "base", "", Set.of(ResourceAccess.Operation.READ, ResourceAccess.Operation.QUERY));
                try (var access = new ResourceAccess(catalog, List.of(grant), Map.of(), 8, 8)) {
                    assertEquals(1, access.query(ResourceQuery.all(10)).size());
                    assertThrows(SecurityException.class, () -> access.read(ResourceUri.file("demo", "top", "same.txt"), 8));
                    assertEquals("low", access.readText(ResourceUri.file("demo", "base", "same.txt"), 8));
                    access.close(); assertThrows(IllegalStateException.class, () -> access.query(ResourceQuery.all(10)));
                }
            }
        }
    }
    @Test void workspaceIsBoundedAndNeverWritesOriginalSource() throws Exception {
        var catalog = new ResourceCatalog("scope", 1, List.of());
        try (var workspace = new ResourceAccess.Workspace("owner", "work", "main", 4)) {
            var grant = new ResourceAccess.Grant("owner", "work", "main", "out", Set.of(ResourceAccess.Operation.CREATE, ResourceAccess.Operation.WRITE, ResourceAccess.Operation.DELETE));
            try (var access = new ResourceAccess(catalog, List.of(grant), Map.of("mine", workspace), 8, 16)) {
                access.writeText("mine", "out/a.txt", "1234");
                assertThrows(IOException.class, () -> access.writeText("mine", "out/b.txt", "x"));
                assertThrows(SecurityException.class, () -> access.writeText("mine", "elsewhere.txt", "x"));
                assertThrows(IllegalArgumentException.class, () -> access.writeText("mine", "out/../escape", "x"));
                assertTrue(access.delete("mine", "out/a.txt")); access.writeText("mine", "out/b.txt", "x");
            }
        }
    }
    @Test void unknownEntryPointFailsBeforeCaptureOrAnyPreparation() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        ResourceSource source = new ResourceSource() {
            public ResourceSourceDescriptor descriptor() { return ResourceSafetyTest.descriptor("main", 1); }
            public ResourceSnapshot capture() { captures.incrementAndGet(); throw new AssertionError("must not capture"); }
            public void close() {}
        };
        var plan = new ResourceRouting(List.of(new ResourceRouting.Binding("bad", "demo", "", List.of(
                new ResourceRouting.Output("bad", "unregistered", ResourceEvent.RESOURCE_RELOAD, LogicalSide.CLIENT, "")))));
        try (var coordinator = new ResourceCoordinator("scope")) {
            assertThrows(java.util.concurrent.CompletionException.class, () -> coordinator.reload(List.of(source), plan, ResourceEvent.RESOURCE_RELOAD, LogicalSide.CLIENT, Runnable::run, Runnable::run).join());
            assertEquals(0, captures.get());
        }
    }
}
