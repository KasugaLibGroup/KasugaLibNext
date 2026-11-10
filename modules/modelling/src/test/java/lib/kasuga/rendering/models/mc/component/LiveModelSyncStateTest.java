package lib.kasuga.rendering.models.mc.component;

import org.junit.jupiter.api.Test;

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
}
