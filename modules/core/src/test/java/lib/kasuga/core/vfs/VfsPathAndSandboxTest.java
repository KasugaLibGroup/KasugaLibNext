package lib.kasuga.core.vfs;

import lib.kasuga.core.resource.pack.ScopedPackResources;
import lib.kasuga.core.resource.pack.ScopedVanillaPathResourcePack;
import lib.kasuga.core.vfs.backend.DirectoryVfs;
import lib.kasuga.core.vfs.backend.ZipVfs;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies cross-platform path validation, normalization, Zip-Slip rejection,
 * symlink escape blocking, and ScopedPackResources sandbox fixes.
 */
class VfsPathAndSandboxTest {

    @Test
    void normalizesValidLogicalPaths() {
        assertEquals(VfsPath.ROOT, VfsPath.of(""));
        assertEquals(VfsPath.ROOT, VfsPath.of("/"));
        assertEquals(VfsPath.ROOT, VfsPath.of("///"));
        assertTrue(VfsPath.ROOT.isRoot());
        assertNull(VfsPath.ROOT.parent());
        assertNull(VfsPath.ROOT.fileName());

        VfsPath nested = VfsPath.of("/assets/kuayue/models/train.json/");
        assertEquals("assets/kuayue/models/train.json", nested.asString());
        assertEquals(List.of("assets", "kuayue", "models", "train.json"), nested.segments());
        assertEquals("train.json", nested.fileName());
        assertEquals(VfsPath.of("assets/kuayue/models"), nested.parent());
        assertEquals(VfsPath.ROOT, VfsPath.of("assets").parent());

        assertEquals(
                VfsPath.of("assets/kuayue/textures/body.png"),
                VfsPath.of("assets/kuayue").resolve("textures/body.png")
        );
        assertTrue(nested.startsWith(VfsPath.of("assets/kuayue")));
        assertFalse(nested.startsWith(VfsPath.of("assets/kua")));
        assertEquals(VfsPath.of("models/train.json"), VfsPath.of("assets/kuayue").relativize(nested));
    }

    @Test
    void rejectsTraversalBackslashesDriveLettersAndControlCharacters() {
        List<String> invalidInputs = List.of(
                "..",
                "assets/../secret.txt",
                ".",
                "assets/./model.json",
                "assets//model.json",
                "assets\\model.json",
                "C:/Windows/win.ini",
                "D:secret.txt",
                "file.txt:stream",
                "assets/foo\0bar.json",
                "assets/foo\nbar.json"
        );

        for (String invalid : invalidInputs) {
            assertThrows(VfsInvalidPathException.class, () -> VfsPath.of(invalid),
                    () -> "Expected VfsInvalidPathException for input: " + invalid);
        }
        assertThrows(VfsInvalidPathException.class, () -> VfsPath.of(null));
    }

    @Test
    void physicalBackendsRejectNonportableNamesWithoutRestrictingArchivePaths(@TempDir Path tempDir) throws IOException {
        List<String> nonportable = List.of(
                "assets/trailingDot.",
                "assets/trailingSpace ",
                "CON",
                "assets/con.txt",
                "PRN",
                "AUX",
                "NUL",
                "COM1",
                "com9.json",
                "LPT1",
                "lpt9.png",
                "bad<name", "bad>name", "bad\"name", "bad|name", "bad?name", "bad*name"
        );
        try (DirectoryVfs directory = new DirectoryVfs("portable", tempDir)) {
            for (String name : nonportable) {
                VfsPath logical = VfsPath.of(name);
                assertThrows(VfsInvalidPathException.class, logical::requirePortable);
                assertThrows(VfsInvalidPathException.class, () -> directory.writeString(logical, "forbidden"));
                assertThrows(IllegalArgumentException.class, () -> ScopedPackResources.safeResolve(tempDir, name));
            }
            assertEquals(List.of(), directory.list(VfsPath.ROOT));
        }
    }

    @Test
    void directoryVfsBlocksSymlinkEscapeReadsWritesStatAndListing(@TempDir Path tempDir) throws IOException {
        Path outsideDir = tempDir.resolve("outside");
        Files.createDirectories(outsideDir);
        Path secretFile = outsideDir.resolve("secret.txt");
        Files.writeString(secretFile, "top-secret");

        Path mountDir = tempDir.resolve("mount");
        Files.createDirectories(mountDir);
        Files.writeString(mountDir.resolve("safe.txt"), "ok");

        Path fileSymlink = mountDir.resolve("link-to-secret.txt");
        Path dirSymlink = mountDir.resolve("link-dir");
        try {
            Files.createSymbolicLink(fileSymlink, secretFile);
            Files.createSymbolicLink(dirSymlink, outsideDir);
        } catch (UnsupportedOperationException | IOException e) {
            // Skip only if host filesystem disallows symlink creation without privileges
            return;
        }

        try (DirectoryVfs vfs = new DirectoryVfs("dir-sandbox", mountDir)) {
            assertEquals("ok", vfs.readString(VfsPath.of("safe.txt")));
            assertThrows(VfsInvalidPathException.class, () -> vfs.openRead(VfsPath.of("link-to-secret.txt")));
            assertThrows(VfsInvalidPathException.class, () -> vfs.stat(VfsPath.of("link-to-secret.txt")));
            assertThrows(VfsInvalidPathException.class, () -> vfs.writeString(VfsPath.of("link-to-secret.txt"), "pwn"));
            assertThrows(VfsInvalidPathException.class, () -> vfs.readString(VfsPath.of("link-dir/secret.txt")));
            assertThrows(VfsInvalidPathException.class, () -> vfs.writeString(VfsPath.of("link-dir/nested/new.txt"), "pwn"));
            assertThrows(VfsInvalidPathException.class, () -> vfs.list(VfsPath.of("link-dir")));
        }
    }

    @Test
    void directoryVfsWorksWhenMountRootItselfGoesThroughSymlink(@TempDir Path tempDir) throws IOException {
        Path realRoot = tempDir.resolve("real-root");
        Files.createDirectories(realRoot);
        Path symlinkRoot = tempDir.resolve("symlink-root");
        try {
            Files.createSymbolicLink(symlinkRoot, realRoot);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }

        try (DirectoryVfs vfs = new DirectoryVfs("symlinked-mount", symlinkRoot)) {
            vfs.writeString(VfsPath.of("nested/hello.txt"), "world");
            assertEquals("world", vfs.readString(VfsPath.of("nested/hello.txt")));
            assertEquals(List.of("nested"), vfs.list(VfsPath.ROOT));
            assertEquals(List.of(VfsPath.of("nested/hello.txt")), vfs.walk(VfsPath.ROOT));
        }
    }

    @Test
    void zipVfsIgnoresAndBlocksZipSlipEntries(@TempDir Path tempDir) throws IOException {
        Path zipPath = tempDir.resolve("malicious.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            zos.putNextEntry(new ZipEntry("../outside.txt"));
            zos.write("bad".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("assets/good.txt"));
            zos.write("good".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        try (ZipVfs zipVfs = new ZipVfs("malicious-zip", zipPath)) {
            assertEquals(List.of(VfsPath.of("assets/good.txt")), zipVfs.walk(VfsPath.ROOT));
            assertEquals("good", zipVfs.readString(VfsPath.of("assets/good.txt")));
            assertThrows(VfsInvalidPathException.class, () -> zipVfs.exists(VfsPath.of("../outside.txt")));
        }
    }

    @Test
    void scopedPackResourcesSafeResolveAndVanillaPathPackRejectEscape(@TempDir Path tempDir) throws IOException {
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "nope");
        Path root = tempDir.resolve("pack");
        Files.createDirectories(root.resolve("assets"));
        Files.writeString(root.resolve("assets/ok.txt"), "yes");

        assertThrows(IllegalArgumentException.class, () -> ScopedPackResources.safeResolve(root, "assets", "../../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> ScopedPackResources.safeResolve(root, "assets\\..\\outside.txt"));

        PackLocationInfo info = new PackLocationInfo("test-path-pack", Component.literal("test"), PackSource.BUILT_IN, Optional.empty());
        PathPackResources vanillaPack = new PathPackResources(info, root);
        ScopedVanillaPathResourcePack scoped = new ScopedVanillaPathResourcePack(root, vanillaPack);

        assertTrue(scoped.exists("assets", "ok.txt"));
        assertThrows(IllegalArgumentException.class, () -> scoped.exists("assets", "../../outside.txt"));

        try {
            Path escapedLink = root.resolve("assets/escape-link.txt");
            Files.createSymbolicLink(escapedLink, outside);
            assertThrows(IllegalArgumentException.class, () -> ScopedPackResources.safeResolve(root, "assets", "escape-link.txt"));
            assertThrows(IllegalArgumentException.class, () -> scoped.open("assets", "escape-link.txt"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Host disallowed symlink creation
        }
    }
}
