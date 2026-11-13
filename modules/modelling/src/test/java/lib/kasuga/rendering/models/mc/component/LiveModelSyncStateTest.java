package lib.kasuga.rendering.models.mc.component;

import lib.kasuga.core.vfs.VfsWriteTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LiveModelSyncStateTest {
    @Test
    void coalescesChangesAndQueuesLatestWhileReloading() {
        var state = new LiveModelSyncState(350);
        state.observe("a", 0);
        assertNull(state.beginReload(349));
        state.observe("b", 300);
        assertNull(state.beginReload(649));
        assertEquals("b", state.beginReload(650));
        state.observe("c", 700);
        state.observe("d", 800);
        assertNull(state.beginReload(2000));
        state.completeReload("b");
        assertEquals("d", state.beginReload(2000));
        state.completeReload("d");
        assertNull(state.beginReload(3000));
        state.observe("d", 3500);
        assertNull(state.beginReload(4000));
    }

    @Test
    void liveVfsCreatesMetadataAndPollsMarkerWithSizeAndSchemaValidation(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("kasuga_live");
        try (var vfs = LiveModelSync.openLiveVfs(root)) {
            assertTrue(vfs.isRegularFile(LiveModelSync.PACK_MCMETA));
            assertTrue(Files.isRegularFile(root.resolve("pack.mcmeta")));

            var syncState = new LiveModelSyncState(350);
            String payload = "{\"version\":1,\"generation\":1}";
            try (VfsWriteTransaction tx = vfs.beginTransaction()) {
                tx.putString(LiveModelSync.SYNC_MARKER, payload);
                tx.commit();
            }
            LiveModelSync.pollMarker(vfs, syncState, 100);
            assertNull(syncState.beginReload(449));
            assertEquals(payload, syncState.beginReload(450));

            try (VfsWriteTransaction tx = vfs.beginTransaction()) {
                tx.putString(LiveModelSync.SYNC_MARKER, "{\"version\":2,\"generation\":1}");
                tx.commit();
            }
            assertThrows(IOException.class, () -> LiveModelSync.pollMarker(vfs, syncState, 500));

            try (VfsWriteTransaction tx = vfs.beginTransaction()) {
                tx.putString(LiveModelSync.SYNC_MARKER, "x".repeat(8193));
                tx.commit();
            }
            assertThrows(IOException.class, () -> LiveModelSync.pollMarker(vfs, syncState, 600));
        }
    }
}
