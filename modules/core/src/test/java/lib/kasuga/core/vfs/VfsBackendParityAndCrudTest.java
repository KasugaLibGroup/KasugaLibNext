package lib.kasuga.core.vfs;

import lib.kasuga.core.resource.pack.ScopedVanillaFileResourcePack;
import lib.kasuga.core.vfs.backend.DirectoryVfs;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import lib.kasuga.core.vfs.backend.ZipVfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies semantic parity across DirectoryVfs, InMemoryVfs, and ZipVfs, plus writable CRUD,
 * read-only rejection on ZipVfs, and ScopedVanillaFileResourcePack bug fixes.
 */
class VfsBackendParityAndCrudTest {

    @Test
    void directoryInMemoryAndZipProduceIdenticalTreeQueriesEvenWithImplicitZipDirectories(@TempDir Path tempDir) throws IOException {
        Path diskRoot = tempDir.resolve("disk");
        Files.createDirectories(diskRoot.resolve("assets/kuayue/models"));
        Files.createDirectories(diskRoot.resolve("assets/kuayue/textures"));
        Files.writeString(diskRoot.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34}}");
        Files.writeString(diskRoot.resolve("assets/kuayue/models/coach.json"), "{\"model\":\"coach\"}");
        Files.writeString(diskRoot.resolve("assets/kuayue/textures/coach.png"), "PNGDATA");

        // Deliberately write ONLY file entries without explicit directory entries into the ZIP
        Path zipPath = tempDir.resolve("pack.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            writeZipEntry(zos, "pack.mcmeta", "{\"pack\":{\"pack_format\":34}}");
            writeZipEntry(zos, "assets/kuayue/models/coach.json", "{\"model\":\"coach\"}");
            writeZipEntry(zos, "assets/kuayue/textures/coach.png", "PNGDATA");
        }

        try (
                InMemoryVfs mem = new InMemoryVfs("mem");
                DirectoryVfs dir = new DirectoryVfs("dir", diskRoot);
                ZipVfs zip = new ZipVfs("zip", zipPath)
        ) {
            mem.writeString(VfsPath.of("pack.mcmeta"), "{\"pack\":{\"pack_format\":34}}");
            mem.writeString(VfsPath.of("assets/kuayue/models/coach.json"), "{\"model\":\"coach\"}");
            mem.writeString(VfsPath.of("assets/kuayue/textures/coach.png"), "PNGDATA");

            for (VirtualFileSystem vfs : List.of(mem, dir, zip)) {
                assertTrue(vfs.isDirectory(VfsPath.ROOT));
                assertTrue(vfs.isDirectory(VfsPath.of("assets")));
                assertTrue(vfs.isDirectory(VfsPath.of("assets/kuayue")));
                assertTrue(vfs.isRegularFile(VfsPath.of("pack.mcmeta")));
                assertTrue(vfs.isRegularFile(VfsPath.of("assets/kuayue/models/coach.json")));
                assertFalse(vfs.exists(VfsPath.of("assets/kuayue/missing.json")));

                assertEquals(List.of("assets", "pack.mcmeta"), vfs.list(VfsPath.ROOT));
                assertEquals(List.of("kuayue"), vfs.list(VfsPath.of("assets")));
                assertEquals(List.of("models", "textures"), vfs.list(VfsPath.of("assets/kuayue")));
                assertEquals(List.of("coach.json"), vfs.list(VfsPath.of("assets/kuayue/models")));

                assertEquals(
                        List.of(
                                VfsPath.of("assets/kuayue/models/coach.json"),
                                VfsPath.of("assets/kuayue/textures/coach.png"),
                                VfsPath.of("pack.mcmeta")
                        ),
                        vfs.walk(VfsPath.ROOT)
                );

                VfsNodeStat stat = vfs.stat(VfsPath.of("assets/kuayue/models/coach.json"));
                assertEquals(VfsNodeKind.FILE, stat.kind());
                assertEquals("{\"model\":\"coach\"}".getBytes(StandardCharsets.UTF_8).length, stat.size());
                assertEquals("{\"model\":\"coach\"}", vfs.readString(VfsPath.of("assets/kuayue/models/coach.json")));

                assertThrows(NoSuchFileException.class, () -> vfs.list(VfsPath.of("nonexistent")));
            }
        }
    }

    @Test
    void directoryAndInMemorySupportFullWritableCrud(@TempDir Path tempDir) throws IOException {
        try (
                InMemoryVfs mem = new InMemoryVfs("mem-rw");
                DirectoryVfs dir = new DirectoryVfs("dir-rw", tempDir.resolve("rw"))
        ) {
            for (VirtualFileSystem vfs : List.of(mem, dir)) {
                assertFalse(vfs.isReadOnly());

                vfs.mkdirs(VfsPath.of("data/routes"));
                assertTrue(vfs.isDirectory(VfsPath.of("data/routes")));

                vfs.writeString(VfsPath.of("data/routes/line1.json"), "{\"speed\":160}");
                assertEquals("{\"speed\":160}", vfs.readString(VfsPath.of("data/routes/line1.json")));

                try (OutputStream out = vfs.openWrite(VfsPath.of("data/routes/line2.json"))) {
                    out.write("{\"speed\":200}".getBytes(StandardCharsets.UTF_8));
                }
                assertEquals("{\"speed\":200}", vfs.readString(VfsPath.of("data/routes/line2.json")));

                vfs.move(VfsPath.of("data/routes/line2.json"), VfsPath.of("data/archive/line2.json"));
                assertFalse(vfs.exists(VfsPath.of("data/routes/line2.json")));
                assertEquals("{\"speed\":200}", vfs.readString(VfsPath.of("data/archive/line2.json")));

                assertTrue(vfs.delete(VfsPath.of("data/archive")));
                assertFalse(vfs.exists(VfsPath.of("data/archive")));
                assertFalse(vfs.delete(VfsPath.of("data/does-not-exist")));
            }
        }
    }

    @Test
    void zipVfsRejectsAllWriteOperationsWithReadOnlyException(@TempDir Path tempDir) throws IOException {
        Path zipPath = tempDir.resolve("ro.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            writeZipEntry(zos, "a.txt", "hello");
        }

        try (ZipVfs zip = new ZipVfs("zip-ro", zipPath)) {
            assertTrue(zip.isReadOnly());
            assertThrows(VfsReadOnlyException.class, () -> zip.writeString(VfsPath.of("b.txt"), "x"));
            assertThrows(VfsReadOnlyException.class, () -> zip.openWrite(VfsPath.of("b.txt")));
            assertThrows(VfsReadOnlyException.class, () -> zip.mkdirs(VfsPath.of("dir")));
            assertThrows(VfsReadOnlyException.class, () -> zip.delete(VfsPath.of("a.txt")));
            assertThrows(VfsReadOnlyException.class, () -> zip.move(VfsPath.of("a.txt"), VfsPath.of("b.txt")));
            assertThrows(VfsReadOnlyException.class, zip::beginTransaction);
        }
    }

    @Test
    void scopedVanillaFileResourcePackHandlesMissingEntriesWithoutNpeAndSupportsHierarchicalList(@TempDir Path tempDir) throws IOException {
        Path zipPath = tempDir.resolve("legacy-pack.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            writeZipEntry(zos, "assets/kuayue/scripts/main.js", "console.log('hi');");
        }

        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            ScopedVanillaFileResourcePack pack = new ScopedVanillaFileResourcePack(zipFile);
            assertFalse(pack.exists("assets/kuayue", "missing.js"));
            assertFalse(pack.isRegularFile("assets/kuayue", "missing.js"));
            assertFalse(pack.isDirectory("assets/kuayue", "missing_dir"));
            assertTrue(pack.isDirectory("assets/kuayue", "scripts"));
            assertTrue(pack.isRegularFile("assets/kuayue", "scripts/main.js"));
            assertEquals(List.of("main.js"), pack.list("assets/kuayue", "scripts"));
        }
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
