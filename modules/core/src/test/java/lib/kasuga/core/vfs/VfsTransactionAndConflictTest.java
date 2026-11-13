package lib.kasuga.core.vfs;

import lib.kasuga.core.vfs.backend.CompositeVfs;
import lib.kasuga.core.vfs.backend.DirectoryVfs;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies atomic staging transactions (commit/rollback/failure leaves zero temp files or partial state)
 * and CompositeVfs explicit conflict detection and resolution policies.
 */
class VfsTransactionAndConflictTest {

    @Test
    void atomicTransactionCommitAppliesAllChangesAndLeavesNoTempFiles(@TempDir Path tempDir) throws IOException {
        Path diskRoot = tempDir.resolve("tx-root");
        try (
                InMemoryVfs mem = new InMemoryVfs("mem-tx");
                DirectoryVfs dir = new DirectoryVfs("dir-tx", diskRoot)
        ) {
            for (VirtualFileSystem vfs : List.of(mem, dir)) {
                vfs.writeString(VfsPath.of("old.txt"), "obsolete");
                vfs.writeString(VfsPath.of("move-me.txt"), "moved-content");

                try (VfsWriteTransaction tx = vfs.beginTransaction()) {
                    tx.mkdirs(VfsPath.of("pack/sub"))
                            .putString(VfsPath.of("pack/sub/a.json"), "{\"a\":1}")
                            .putString(VfsPath.of("pack/b.json"), "{\"b\":2}")
                            .delete(VfsPath.of("old.txt"))
                            .move(VfsPath.of("move-me.txt"), VfsPath.of("pack/moved.txt"));
                    VfsChangeEvent event = tx.commit();
                    assertEquals(VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, event.origin());
                    assertFalse(event.changes().isEmpty());
                }

                assertTrue(vfs.isDirectory(VfsPath.of("pack/sub")));
                assertEquals("{\"a\":1}", vfs.readString(VfsPath.of("pack/sub/a.json")));
                assertEquals("{\"b\":2}", vfs.readString(VfsPath.of("pack/b.json")));
                assertFalse(vfs.exists(VfsPath.of("old.txt")));
                assertFalse(vfs.exists(VfsPath.of("move-me.txt")));
                assertEquals("moved-content", vfs.readString(VfsPath.of("pack/moved.txt")));
            }

            try (Stream<Path> walk = Files.walk(diskRoot)) {
                List<String> leftoverTmp = walk
                        .map(p -> p.getFileName().toString())
                        .filter(name -> name.startsWith(".ksgvfs-"))
                        .toList();
                assertTrue(leftoverTmp.isEmpty(), "No temporary files may remain: " + leftoverTmp);
            }
        }
    }

    @Test
    void failedOrRolledBackTransactionPreservesOriginalFiles(@TempDir Path tempDir) throws IOException {
        Path diskRoot = tempDir.resolve("tx-rollback");
        try (
                InMemoryVfs mem = new InMemoryVfs("mem-rb");
                DirectoryVfs dir = new DirectoryVfs("dir-rb", diskRoot)
        ) {
            for (VirtualFileSystem vfs : List.of(mem, dir)) {
                vfs.writeString(VfsPath.of("keep.txt"), "original-v1");

                // Uncommitted transaction closed via try-with-resources automatically rolls back
                try (VfsWriteTransaction tx = vfs.beginTransaction()) {
                    tx.putString(VfsPath.of("keep.txt"), "corrupted-v2");
                    tx.putString(VfsPath.of("new.txt"), "half-baked");
                }
                assertEquals("original-v1", vfs.readString(VfsPath.of("keep.txt")));
                assertFalse(vfs.exists(VfsPath.of("new.txt")));

                // Transaction that fails during commit (moving nonexistent source) leaves prior state intact
                VfsWriteTransaction failing = vfs.beginTransaction();
                failing.putString(VfsPath.of("keep.txt"), "should-not-stick");
                failing.putString(VfsPath.of("added.txt"), "should-not-exist");
                failing.move(VfsPath.of("does-not-exist.txt"), VfsPath.of("dest.txt"));
                assertThrows(IOException.class, failing::commit);
                failing.close();

                assertEquals("original-v1", vfs.readString(VfsPath.of("keep.txt")));
                assertFalse(vfs.exists(VfsPath.of("added.txt")));
            }
        }
    }

    @Test
    void compositeVfsDetectsConflictsAndEnforcesPolicies() throws IOException {
        try (
                InMemoryVfs base = new InMemoryVfs("pack-base");
                InMemoryVfs override = new InMemoryVfs("pack-override")
        ) {
            base.writeString(VfsPath.of("models/train.json"), "from-base");
            base.writeString(VfsPath.of("only-base.txt"), "base-only");
            override.writeString(VfsPath.of("models/train.json"), "from-override");
            override.writeString(VfsPath.of("only-override.txt"), "override-only");

            try (CompositeVfs firstWins = new CompositeVfs("first", List.of(base, override), VfsConflictPolicy.FIRST_WINS)) {
                Map<VfsPath, List<String>> conflicts = firstWins.detectConflicts(VfsPath.ROOT);
                assertEquals(Map.of(VfsPath.of("models/train.json"), List.of("pack-base", "pack-override")), conflicts);
                assertEquals("from-base", firstWins.readString(VfsPath.of("models/train.json")));
                assertEquals("override-only", firstWins.readString(VfsPath.of("only-override.txt")));
                assertEquals(
                        List.of(
                                VfsPath.of("models/train.json"),
                                VfsPath.of("only-base.txt"),
                                VfsPath.of("only-override.txt")
                        ),
                        firstWins.walk(VfsPath.ROOT)
                );
            }

            try (CompositeVfs lastWins = new CompositeVfs("last", List.of(base, override), VfsConflictPolicy.LAST_WINS)) {
                assertEquals("from-override", lastWins.readString(VfsPath.of("models/train.json")));
            }

            try (CompositeVfs strict = new CompositeVfs("strict", List.of(base, override), VfsConflictPolicy.THROW_ON_CONFLICT)) {
                assertEquals("base-only", strict.readString(VfsPath.of("only-base.txt")));
                VfsConflictException ex = assertThrows(
                        VfsConflictException.class,
                        () -> strict.openRead(VfsPath.of("models/train.json"))
                );
                assertEquals(List.of("pack-base", "pack-override"), ex.getConflictingSources());
            }
        }
    }
}
