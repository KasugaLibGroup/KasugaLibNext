package lib.kasuga.rendering.models.mc.backend;

import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** Single render-thread producer. In-flight GPU results are never overwritten or waited on. */
final class QueryResultRing {
    private final boolean[] pending;
    private int cursor;

    QueryResultRing(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Ring capacity must be positive");
        pending = new boolean[capacity];
    }

    /** Returns -1 when all slots are busy. Consume only results reported ready. */
    int acquire(IntPredicate ready, IntConsumer consume) {
        for (int offset = 0; offset < pending.length; offset++) {
            int slot = (cursor + offset) % pending.length;
            if (pending[slot]) {
                if (!ready.test(slot)) continue;
                consume.accept(slot);
                pending[slot] = false;
            }
            cursor = (slot + 1) % pending.length;
            return slot;
        }
        return -1;
    }

    void submit(int slot) {
        if (pending[slot]) throw new IllegalStateException("Query slot is still in flight");
        pending[slot] = true;
    }

    void reset() {
        Arrays.fill(pending, false);
        cursor = 0;
    }
}
