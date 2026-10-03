package lib.kasuga.rendering.models.uml.framework.buffer;

import lib.kasuga.rendering.models.uml.backend.ElementChanges;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

/**
 * Backend-independent upload ring. The supplied device owns all API operations.
 * Busy storage is orphaned, never overwritten or explicitly waited for. Call
 * markSubmitted after every GPU consumer, including repeated draws of unchanged data.
 */
public final class UploadRing implements UploadBuffer {
    public record Stats(long uploads, long bytesUploaded, long storageAllocations,
                        long busyOrphans, long fencePolls, long fencesCreated, long policyOrphans) {}

    private static final class Slot {
        int buffer, capacity, layout = -1;
        long version, fence;
        boolean read;
    }

    private final UploadDevice device;
    private final Slot[] slots;
    private final BitSet updates = new BitSet();
    private ElementChanges changes;
    private int cursor, current = -1, bytes = -1, stride, layout;
    private boolean closed;
    private long uploads, bytesUploaded, allocations, orphans, polls, fences, policyOrphans;

    public UploadRing(int capacity, UploadDevice device) {
        if (capacity < 1) throw new IllegalArgumentException("Ring capacity must be positive");
        this.device = Objects.requireNonNull(device);
        slots = new Slot[capacity];
        Arrays.setAll(slots, ignored -> new Slot());
    }

    public int bufferId() { return current < 0 ? 0 : slots[current].buffer; }
    public int capacityBytes() { return current < 0 ? 0 : slots[current].capacity; }
    public Stats stats() { return new Stats(uploads, bytesUploaded, allocations, orphans, polls, fences, policyOrphans); }
    public String strategy() { return device.strategy(); }

    /** A complete snapshot; input position and limit are preserved. */
    public int upload(ByteBuffer snapshot) {
        return upload(snapshot, null, Math.max(1, snapshot.remaining()), 0, true);
    }

    /**
     * Each slot independently catches up all element changes since its last upload.
     * The snapshot must include unchanged elements too. Empty changes retain the current slot.
     */
    public int upload(ByteBuffer snapshot, BitSet dirty, int elementStride, int mergeGap, boolean force) {
        if (closed) throw new IllegalStateException("Upload ring is closed");
        if (!snapshot.isDirect()) throw new IllegalArgumentException("Direct snapshot required");
        int length = snapshot.remaining();
        if (elementStride < 1 || length % elementStride != 0 || mergeGap < 0)
            throw new IllegalArgumentException("Invalid snapshot layout");
        int elements = length / elementStride;
        if (dirty != null && dirty.length() > elements) throw new IllegalArgumentException("Dirty element outside snapshot");
        boolean resized = bytes != length || stride != elementStride;
        if (resized) {
            bytes = length; stride = elementStride; layout++;
            changes = new ElementChanges(elements);
        }
        if (resized || force || dirty == null) {
            updates.clear(); updates.set(0, elements); changes.mark(updates);
        } else if (!dirty.isEmpty()) changes.mark(dirty);
        else if (current >= 0 && slots[current].layout == layout
                && slots[current].version == changes.version()) return bufferId();

        if (device.orphanEveryUpload()) {
            Slot slot = slots[cursor];
            if (slot.buffer == 0) {
                slot.buffer = device.createBuffer();
                if (slot.buffer == 0) throw new IllegalStateException("Cannot allocate upload buffer");
            }
            try { device.uploadOrphaned(slot.buffer, snapshot); }
            catch (RuntimeException | Error error) {
                slot.layout = -1;
                if (current == cursor) current = -1;
                throw error;
            }
            slot.capacity = Math.max(16, length); slot.layout = layout;
            slot.version = changes.version(); slot.read = false;
            current = cursor; cursor = (cursor + 1) % slots.length;
            uploads++; allocations++; policyOrphans++; bytesUploaded += length;
            return slot.buffer;
        }

        // Seal all reads of the previous snapshot once, immediately before its
        // replacement. Static geometry/palettes incur no fence per repeated draw.
        sealCurrent();
        int selected = -1;
        for (int offset = 0; offset < slots.length; offset++) {
            int index = (cursor + offset) % slots.length;
            Slot slot = slots[index];
            if (slot.fence != 0) {
                polls++;
                if (!device.ready(slot.fence)) continue;
                device.deleteFence(slot.fence); slot.fence = 0;
            }
            selected = index;
            break;
        }
        boolean busy = selected < 0;
        if (busy) selected = cursor;
        Slot slot = slots[selected];
        if (slot.buffer == 0) {
            slot.buffer = device.createBuffer();
            if (slot.buffer == 0) throw new IllegalStateException("Cannot allocate upload buffer");
        }
        if (busy || slot.capacity < length || slot.capacity == 0) {
            int capacity = Math.max(256, slot.capacity);
            while (capacity < length) {
                if (capacity > Integer.MAX_VALUE / 2) { capacity = length; break; }
                capacity *= 2;
            }
            // The device replaces storage without invalidating pending reads.
            // Retiring its completion token must not cancel submitted work.
            device.allocate(slot.buffer, capacity);
            slot.capacity = capacity; slot.layout = -1; allocations++;
            if (busy) orphans++;
            if (slot.fence != 0) { device.deleteFence(slot.fence); slot.fence = 0; }
        }
        if (slot.layout != layout) { updates.clear(); updates.set(0, elements); }
        else changes.collectSince(slot.version, updates);
        try { device.write(slot.buffer, snapshot, updates, elementStride, mergeGap); }
        catch (RuntimeException | Error error) {
            slot.layout = -1;
            if (current == selected) current = -1;
            throw error;
        }
        bytesUploaded += writtenBytes(updates, elementStride, mergeGap);
        slot.layout = layout; slot.version = changes.version(); slot.read = false;
        current = selected; cursor = (selected + 1) % slots.length; uploads++;
        return slot.buffer;
    }

    private static long writtenBytes(BitSet elements, int stride, int gap) {
        long result = 0;
        for (int start = elements.nextSetBit(0); start >= 0;) {
            int end = elements.nextClearBit(start), next = elements.nextSetBit(end);
            while (next >= 0 && next - end <= gap) { end = elements.nextClearBit(next); next = elements.nextSetBit(end); }
            result += (long) (end - start) * stride; start = next;
        }
        return result;
    }

    public void markSubmitted() {
        if (closed) throw new IllegalStateException("Upload ring is closed");
        if (current >= 0) slots[current].read = true;
    }

    private void sealCurrent() {
        if (current < 0 || !slots[current].read) return;
        Slot slot = slots[current];
        long next = device.fence();
        if (next == 0) throw new IllegalStateException("Cannot fence upload buffer");
        if (slot.fence != 0) device.deleteFence(slot.fence);
        slot.fence = next; slot.read = false; fences++;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (Slot slot : slots) {
            try { if (slot.fence != 0) device.deleteFence(slot.fence); }
            catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            try { if (slot.buffer != 0) device.deleteBuffer(slot.buffer); }
            catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            slot.fence = 0; slot.buffer = 0;
        }
        current = -1;
        if (failure != null) throw failure;
    }

}
