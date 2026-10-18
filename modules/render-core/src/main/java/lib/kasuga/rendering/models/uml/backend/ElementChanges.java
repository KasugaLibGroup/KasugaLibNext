package lib.kasuga.rendering.models.uml.backend;

import java.util.BitSet;

/** Independent readers can catch up after any number of skipped frames without consuming changes. */
public final class ElementChanges {
    private final long[] changedAt;
    private long version;

    public ElementChanges(int size) { changedAt = new long[size]; }
    public long version() { return version; }

    public void mark(int index) { changedAt[index] = ++version; }

    public void mark(BitSet indices) {
        if (indices.isEmpty()) return;
        long next = ++version;
        for (int i = indices.nextSetBit(0); i >= 0; i = indices.nextSetBit(i + 1)) changedAt[i] = next;
    }

    /** Reuses the reader's bitset; no history allocations or retention limit. */
    public long collectSince(long previous, BitSet destination) {
        destination.clear();
        if (previous != version) {
            for (int i = 0; i < changedAt.length; i++) if (changedAt[i] > previous) destination.set(i);
        }
        return version;
    }
}
