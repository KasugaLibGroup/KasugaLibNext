package lib.kasuga.core.vfs;

import lib.kasuga.core.vfs.backend.DirectoryVfs;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import lib.kasuga.core.vfs.backend.MountTableVfs;
import lib.kasuga.core.vfs.backend.ZipVfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies unified MountTableVfs longest-prefix routing, virtual ancestor synthesis,
 * read-only & mount-point protection, cross-mount move/transactions, and prefixed hook forwarding.
 */
class MountTableVfsTest {

    @Test
    void mountTableSynthesizesVirtualDirectoriesAndRoutesByLongestPrefix(@TempDir Path tempDir) throws IOException {
        Path zipPath = tempDir.resolve("builtin.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            zos.putNextEntry(new ZipEntry("assets/kuayue/models/base.json"));
            zos.write("{\"source\":\"builtin\"}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        try (
                ZipVfs builtin = new ZipVfs("builtin-zip", zipPath);
                DirectoryVfs df11g = new DirectoryVfs("df11g-dir", tempDir.resolve("df11g"));
                InMemoryVfs cr400af = new InMemoryVfs("cr400af-mem", true);
                InMemoryVfs runtime = new InMemoryVfs("runtime-mem");
                InMemoryVfs df11gOverride = new InMemoryVfs("df11g-override");
                MountTableVfs unified = new MountTableVfs("kasuga_unified")
        ) {
            df11g.writeString(VfsPath.of("assets/kuayue/models/body.json"), "{\"source\":\"df11g\"}");
            df11g.writeString(VfsPath.of("assets/kuayue/models/sub/bogey.json"), "{\"source\":\"df11g-parent\"}");
            df11gOverride.writeString(VfsPath.of("bogey.json"), "{\"source\":\"override-mount\"}");
            runtime.writeString(VfsPath.of("cache/index.json"), "{\"cached\":true}");

            unified.mount(VfsPath.of("builtin"), builtin);
            unified.mount(VfsPath.of("packs/df11g"), df11g);
            unified.mount(VfsPath.of("packs/cr400af"), cr400af);
            unified.mount(VfsPath.of("runtime"), runtime);
            // Deeper nested mount overrides parent mount under packs/df11g/assets/kuayue/models/sub
            unified.mount(VfsPath.of("packs/df11g/assets/kuayue/models/sub"), df11gOverride);

            assertTrue(unified.isDirectory(VfsPath.ROOT));
            assertTrue(unified.isDirectory(VfsPath.of("packs")));
            assertEquals(List.of("builtin", "packs", "runtime"), unified.list(VfsPath.ROOT));
            assertEquals(List.of("cr400af", "df11g"), unified.list(VfsPath.of("packs")));
            assertEquals(
                    "{\"source\":\"builtin\"}",
                    unified.readString(VfsPath.of("builtin/assets/kuayue/models/base.json"))
            );
            assertEquals(
                    "{\"source\":\"override-mount\"}",
                    unified.readString(VfsPath.of("packs/df11g/assets/kuayue/models/sub/bogey.json"))
            );
            assertEquals(
                    List.of(
                            VfsPath.of("builtin/assets/kuayue/models/base.json"),
                            VfsPath.of("packs/df11g/assets/kuayue/models/body.json"),
                            VfsPath.of("packs/df11g/assets/kuayue/models/sub/bogey.json"),
                            VfsPath.of("runtime/cache/index.json")
                    ),
                    unified.walk(VfsPath.ROOT)
            );
        }
    }

    @Test
    void mountTableRoutesWritesTransactionsCrossMountMovesAndProtectsMounts(@TempDir Path tempDir) throws IOException {
        Path df11gDir = tempDir.resolve("df11g");
        Path zipPath = tempDir.resolve("ro.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            zos.putNextEntry(new ZipEntry("meta.txt"));
            zos.write("ro".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        try (
                ZipVfs roZip = new ZipVfs("ro", zipPath);
                DirectoryVfs df11g = new DirectoryVfs("df11g", df11gDir);
                InMemoryVfs runtime = new InMemoryVfs("runtime");
                MountTableVfs unified = new MountTableVfs("kasuga_unified")
        ) {
            unified.mount(VfsPath.of("builtin"), roZip);
            unified.mount(VfsPath.of("packs/df11g"), df11g);
            unified.mount(VfsPath.of("runtime"), runtime);

            // Writes route to the underlying mounted directory backend
            unified.writeString(VfsPath.of("packs/df11g/assets/train.json"), "{\"ok\":1}");
            assertEquals("{\"ok\":1}", df11g.readString(VfsPath.of("assets/train.json")));

            // Writing to unmounted path or read-only mount throws VfsReadOnlyException
            assertThrows(VfsReadOnlyException.class, () -> unified.writeString(VfsPath.of("unmounted/a.txt"), "x"));
            assertThrows(VfsReadOnlyException.class, () -> unified.writeString(VfsPath.of("builtin/a.txt"), "x"));

            // Cannot delete or move an active mount point or its virtual ancestor
            assertThrows(VfsException.class, () -> unified.delete(VfsPath.of("packs")));
            assertThrows(VfsException.class, () -> unified.delete(VfsPath.of("packs/df11g")));

            // Cross-mount move from runtime/staging/loco.json -> packs/df11g/assets/loco.json
            unified.writeString(VfsPath.of("runtime/staging/loco.json"), "{\"loco\":\"df11g\"}");
            unified.move(VfsPath.of("runtime/staging/loco.json"), VfsPath.of("packs/df11g/assets/loco.json"));
            assertFalse(runtime.exists(VfsPath.of("staging/loco.json")));
            assertEquals("{\"loco\":\"df11g\"}", df11g.readString(VfsPath.of("assets/loco.json")));

            // Unified multi-mount transaction: fails atomically before applying if any target is read-only
            VfsWriteTransaction badTx = unified.beginTransaction();
            badTx.putString(VfsPath.of("runtime/should-not-exist.txt"), "nope");
            badTx.putString(VfsPath.of("builtin/illegal.txt"), "nope");
            assertThrows(VfsReadOnlyException.class, badTx::commit);
            badTx.close();
            assertFalse(runtime.exists(VfsPath.of("should-not-exist.txt")));

            try (VfsWriteTransaction tx = unified.beginTransaction()) {
                tx.putString(VfsPath.of("packs/df11g/assets/a.json"), "A");
                tx.putString(VfsPath.of("runtime/b.json"), "B");
                tx.commit();
            }
            assertEquals("A", df11g.readString(VfsPath.of("assets/a.json")));
            assertEquals("B", runtime.readString(VfsPath.of("b.json")));
        }
    }

    @Test
    void mountTableRewritesHookPathsForInternalAndExternalChangesAndStopsOnUnmount(@TempDir Path tempDir) throws IOException {
        Path df11gDir = tempDir.resolve("df11g");
        try (
                DirectoryVfs df11g = new DirectoryVfs("df11g", df11gDir);
                MountTableVfs unified = new MountTableVfs("kasuga_unified")
        ) {
            List<VfsChangeEvent> unifiedPackEvents = new CopyOnWriteArrayList<>();
            VfsSubscription packWatch = unified.watch(VfsPath.of("packs/df11g/assets"), unifiedPackEvents::add);

            VfsSubscription mountSub = unified.mount(VfsPath.of("packs/df11g"), df11g);

            // 1. Direct write via unified mount
            unified.writeString(VfsPath.of("packs/df11g/assets/coach.json"), "{\"v\":1}");
            assertEquals(1, unifiedPackEvents.size());
            assertEquals(VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, unifiedPackEvents.get(0).origin());
            assertTrue(unifiedPackEvents.get(0).changes().stream()
                    .anyMatch(c -> c.path().equals(VfsPath.of("packs/df11g/assets/coach.json"))));

            // 2. External disk change on underlying mounted directory is forwarded with mount prefix
            Files.writeString(df11gDir.resolve("assets/coach.json"), "{\"v\":2}");
            Optional<VfsChangeEvent> external = unified.pollExternalChanges();
            assertTrue(external.isPresent());
            assertEquals(2, unifiedPackEvents.size());
            assertEquals(VfsChangeEvent.ChangeOrigin.EXTERNAL_DISK, unifiedPackEvents.get(1).origin());
            assertEquals(
                    VfsPath.of("packs/df11g/assets/coach.json"),
                    unifiedPackEvents.get(1).changes().get(0).path()
            );

            // 3. Unmount removes mount and stops forwarding backend changes
            mountSub.close();
            assertFalse(unified.exists(VfsPath.of("packs/df11g/assets/coach.json")));
            df11g.writeString(VfsPath.of("assets/after_unmount.json"), "{}");
            assertEquals(2, unifiedPackEvents.size());
            packWatch.close();
        }
    }
}
