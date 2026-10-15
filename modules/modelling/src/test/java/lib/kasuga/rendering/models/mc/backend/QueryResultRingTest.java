package lib.kasuga.rendering.models.mc.backend;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QueryResultRingTest {
    @Test void fillsFreeSlotsWithoutTouchingGpuResults() {
        var ring = new QueryResultRing(3);
        for (int i = 0; i < 3; i++) {
            int slot = ring.acquire(s -> fail("free slot must not query GPU"), s -> fail("no result yet"));
            assertEquals(i, slot);
            ring.submit(slot);
        }
        assertEquals(-1, ring.acquire(s -> false, s -> fail("unfinished result must not be read")));
    }

    @Test void consumesOnlyReadyResultsBeforeReuse() {
        var ring = new QueryResultRing(3);
        for (int i = 0; i < 3; i++) ring.submit(ring.acquire(s -> false, s -> fail()));
        List<Integer> consumed = new ArrayList<>();
        assertEquals(1, ring.acquire(s -> s == 1, consumed::add));
        assertEquals(List.of(1), consumed);
        ring.submit(1);
        assertEquals(-1, ring.acquire(s -> false, s -> fail()));
    }

    @Test void wrapsAroundAndRejectsOverwritingInFlightSlots() {
        var ring = new QueryResultRing(2);
        for (int i = 0; i < 2; i++) ring.submit(ring.acquire(s -> false, s -> fail()));
        assertThrows(IllegalStateException.class, () -> ring.submit(0));
        assertEquals(0, ring.acquire(s -> true, s -> {}));
        ring.submit(0);
        assertEquals(1, ring.acquire(s -> true, s -> {}));
        ring.reset();
        assertEquals(0, ring.acquire(s -> fail(), s -> fail()));
    }
}
