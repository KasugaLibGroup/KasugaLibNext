package lib.kasuga.rendering.models.uml.backend;

import lib.kasuga.rendering.models.uml.backend.gpu.GpuUploadRing;
import lib.kasuga.rendering.models.uml.backend.gpu.TextureUploadRing;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GpuUploadRingTest {
    private static ByteBuffer data(int elements) {
        ByteBuffer data = ByteBuffer.allocateDirect(elements * 4);
        for (int i = 0; i < elements; i++) data.putInt(i * 4, i);
        return data;
    }
    private static BitSet dirty(int... indices) {
        BitSet result = new BitSet();
        for (int index : indices) result.set(index);
        return result;
    }

    @Test void busySlotsOrphanStorageWithoutOverwritingPreviousReads() {
        Device device = new Device();
        List<ByteBuffer> reads = new ArrayList<>();
        try (GpuUploadRing ring = new GpuUploadRing(3, device)) {
            ByteBuffer data = data(4);
            for (int i = 0; i < 12; i++) {
                data.putInt(0, i);
                int id = ring.upload(data);
                reads.add(device.buffers.get(id)); ring.markSubmitted();
            }
            for (int i = 0; i < reads.size(); i++) assertEquals(i, reads.get(i).getInt(0));
            assertEquals(3, device.created);
            assertEquals(9, ring.stats().busyOrphans());
            assertEquals(12, ring.stats().storageAllocations());
        }
        assertTrue(device.buffers.isEmpty()); assertTrue(device.fences.isEmpty());
    }

    @Test void readySlotsReuseAllocations() {
        Device device = new Device(); device.ready = true;
        try (GpuUploadRing ring = new GpuUploadRing(3, device)) {
            for (int i = 0; i < 30; i++) { ring.upload(data(4)); ring.markSubmitted(); }
            assertEquals(3, ring.stats().storageAllocations());
            assertEquals(0, ring.stats().busyOrphans());
            assertEquals(29, ring.stats().fencesCreated());
        }
    }

    @Test void everySlotCatchesUpSparseChangesAndPreservesOtherVertices() {
        Device device = new Device(); device.ready = true;
        ByteBuffer data = data(32);
        try (GpuUploadRing ring = new GpuUploadRing(3, device)) {
            ring.upload(data, dirty(), 4, 0, true);
            for (int i = 0; i < 36; i++) {
                int vertex = (i * 7) % 32;
                data.putInt(vertex * 4, 100 + i);
                int id = ring.upload(data, dirty(vertex), 4, 0, false);
                assertArrayEquals(bytes(data), bytes(device.buffers.get(id).duplicate().limit(data.remaining())));
                ring.markSubmitted();
            }
            long before = ring.stats().bytesUploaded();
            for (int i = 0; i < 6; i++) {
                data.putInt(0, 1000 + i); ring.upload(data, dirty(0), 4, 0, false); ring.markSubmitted();
            }
            before = ring.stats().bytesUploaded();
            data.putInt(0, 2000); ring.upload(data, dirty(0), 4, 0, false);
            assertEquals(4, ring.stats().bytesUploaded() - before);
        }
    }

    @Test void unchangedRepeatedReadsDeferOneFenceUntilNextUpload() {
        Device device = new Device(); device.ready = true;
        try (GpuUploadRing ring = new GpuUploadRing(3, device)) {
            ByteBuffer data = data(4);
            int first = ring.upload(data, dirty(), 4, 0, true);
            for (int i = 0; i < 100; i++) {
                ring.markSubmitted(); assertEquals(first, ring.upload(data, dirty(), 4, 0, false));
            }
            assertEquals(1, ring.stats().uploads()); assertEquals(0, ring.stats().fencesCreated());
            data.putInt(0, 9); ring.upload(data, dirty(0), 4, 0, false);
            assertEquals(1, ring.stats().fencesCreated());
        }
    }

    @Test void resizeAndStrideChangesReinitializeEverySelectedSlot() {
        Device device = new Device(); device.ready = true;
        try (GpuUploadRing ring = new GpuUploadRing(3, device)) {
            for (int count : new int[]{4, 4, 4, 200, 2, 2, 2, 200}) {
                ByteBuffer data = data(count);
                int id = ring.upload(data, dirty(0), 4, 0, false);
                assertArrayEquals(bytes(data), bytes(device.buffers.get(id).duplicate().limit(data.remaining())));
                ring.markSubmitted();
            }
            ByteBuffer data = data(200);
            int id = ring.upload(data, dirty(1), 8, 0, false);
            assertArrayEquals(bytes(data), bytes(device.buffers.get(id).duplicate().limit(data.remaining())));
        }
    }

    @Test void inputSliceAndMergeGapAreRespected() {
        Device device = new Device(); device.ready = true;
        ByteBuffer data = data(16); data.position(8).limit(40);
        try (GpuUploadRing ring = new GpuUploadRing(1, device)) {
            ring.upload(data, dirty(), 4, 1, true);
            data.putInt(8, 99).putInt(16, 98);
            long before = ring.stats().bytesUploaded();
            int id = ring.upload(data, dirty(0, 2), 4, 1, false);
            assertEquals(12, ring.stats().bytesUploaded() - before);
            assertArrayEquals(bytes(data), bytes(device.buffers.get(id).duplicate().limit(32)));
            assertEquals(8, data.position()); assertEquals(40, data.limit());
        }
    }

    @Test void failedFenceOrPollDoesNotWriteBusyStorage() {
        Device device = new Device();
        try (GpuUploadRing ring = new GpuUploadRing(1, device)) {
            ring.upload(data(4)); ring.markSubmitted();
            device.zeroFence = true;
            assertThrows(IllegalStateException.class, () -> ring.upload(data(4)));
            assertEquals(1, ring.stats().uploads());
            device.zeroFence = false; device.failPoll = true;
            assertThrows(IllegalStateException.class, () -> ring.upload(data(4)));
            assertEquals(1, ring.stats().uploads());
            assertEquals(0, ring.stats().busyOrphans());
        }
    }

    @Test void failedWriteIsNotPublishedAndCanBeRetriedWithoutLosingChanges() {
        Device device = new Device(); device.ready = true;
        try (GpuUploadRing ring = new GpuUploadRing(1, device)) {
            ByteBuffer data = data(4); ring.upload(data, dirty(), 4, 0, true);
            data.putInt(0, 123); device.failWrite = true;
            assertThrows(IllegalStateException.class, () -> ring.upload(data, dirty(0), 4, 0, false));
            assertEquals(0, ring.bufferId());
            device.failWrite = false;
            int id = ring.upload(data, dirty(), 4, 0, false);
            assertEquals(123, device.buffers.get(id).getInt(0));
        }
    }

    @Test void emptySnapshotAndCloseAreSafeAndIdempotent() {
        Device device = new Device(); GpuUploadRing ring = new GpuUploadRing(3, device);
        ring.upload(data(0)); ring.markSubmitted(); ring.close(); ring.close();
        assertEquals(1, device.deleted); assertTrue(device.fences.isEmpty());
        assertThrows(IllegalStateException.class, () -> ring.upload(data(1)));
        assertThrows(IllegalStateException.class, ring::markSubmitted);
        // CPU-only staging must still be possible without loading an OpenGL context.
        new GpuUploadRing().close();
        new TextureUploadRing().close();
    }

    @Test void invalidInputDoesNotChangeOwnership() {
        Device device = new Device();
        try (GpuUploadRing ring = new GpuUploadRing(3, device)) {
            assertThrows(IllegalArgumentException.class, () -> ring.upload(ByteBuffer.allocate(4)));
            assertThrows(IllegalArgumentException.class, () -> ring.upload(data(4), dirty(4), 4, 0, false));
            assertThrows(IllegalArgumentException.class, () -> ring.upload(data(4), dirty(), 3, 0, false));
            assertThrows(IllegalArgumentException.class, () -> ring.upload(data(4), dirty(), 4, -1, false));
            assertEquals(0, device.created);
        }
    }

    @Test void platformOrphanPolicyNeverPollsOrMapsAndKeepsPreviousReadersSafe() {
        Device device = new Device() {
            @Override public boolean orphanEveryUpload() { return true; }
            @Override public void uploadOrphaned(int buffer, ByteBuffer snapshot) {
                allocate(buffer, Math.max(16, snapshot.remaining()));
                BitSet all = new BitSet(); all.set(0, snapshot.remaining());
                write(buffer, snapshot, all, 1, 0);
            }
        };
        device.zeroFence = true; device.failPoll = true;
        List<ByteBuffer> readers = new ArrayList<>();
        try (GpuUploadRing ring = new GpuUploadRing(3, device)) {
            ByteBuffer snapshot = data(4);
            for (int i = 0; i < 12; i++) {
                snapshot.putInt(0, i);
                readers.add(device.buffers.get(ring.upload(snapshot)));
                ring.markSubmitted();
            }
            for (int i = 0; i < 12; i++) assertEquals(i, readers.get(i).getInt(0));
            assertEquals(0, ring.stats().fencesCreated()); assertEquals(0, ring.stats().fencePolls());
            assertEquals(12, ring.stats().policyOrphans()); assertEquals(0, ring.stats().busyOrphans());
            assertEquals(3, device.created);
        }
    }

    private static byte[] bytes(ByteBuffer data) { byte[] result = new byte[data.remaining()]; data.duplicate().get(result); return result; }

    private static class Device implements GpuUploadRing.Device {
        final Map<Integer, ByteBuffer> buffers = new HashMap<>();
        final Set<Long> fences = new HashSet<>();
        boolean ready, zeroFence, failPoll, failWrite;
        int created, deleted; long nextFence;
        @Override public int createBuffer() { return ++created; }
        @Override public void deleteBuffer(int buffer) { buffers.remove(buffer); deleted++; }
        @Override public long fence() { if (zeroFence) return 0; fences.add(++nextFence); return nextFence; }
        @Override public boolean ready(long fence) { assertTrue(fences.contains(fence)); if (failPoll) throw new IllegalStateException("poll"); return ready; }
        @Override public void deleteFence(long fence) { assertTrue(fences.remove(fence)); }
        @Override public void allocate(int buffer, int bytes) { buffers.put(buffer, ByteBuffer.allocate(bytes)); }
        @Override public void write(int buffer, ByteBuffer data, BitSet dirty, int stride, int gap) {
            if (failWrite) throw new IllegalStateException("write");
            for (int start = dirty.nextSetBit(0); start >= 0;) {
                int end = dirty.nextClearBit(start), next = dirty.nextSetBit(end);
                while (next >= 0 && next - end <= gap) { end = dirty.nextClearBit(next); next = dirty.nextSetBit(end); }
                ByteBuffer range = data.duplicate().position(data.position() + start * stride).limit(data.position() + end * stride);
                buffers.get(buffer).duplicate().position(start * stride).put(range);
                start = next;
            }
        }
    }
}
