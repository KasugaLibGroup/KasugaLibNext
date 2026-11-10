package lib.kasuga.create.client.rendering;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredGameThreadTaskTest {

    @Test
    void coalescesRepeatedRequestsAndAcceptsAnotherAfterCompletion() {
        ArrayDeque<Runnable> queued = new ArrayDeque<>();
        DeferredGameThreadTask task = new DeferredGameThreadTask(queued::add);

        assertTrue(task.schedule(() -> {}));
        assertFalse(task.schedule(() -> {}));
        assertTrue(task.isPending());
        assertEquals(1, queued.size());

        queued.remove().run();

        assertFalse(task.isPending());
        assertTrue(task.schedule(() -> {}));
        assertEquals(1, queued.size());
    }
}
