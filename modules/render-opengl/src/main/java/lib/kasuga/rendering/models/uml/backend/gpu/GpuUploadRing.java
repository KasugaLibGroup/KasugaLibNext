package lib.kasuga.rendering.models.uml.backend.gpu;

import lib.kasuga.rendering.models.uml.framework.buffer.UploadBuffer;
import lib.kasuga.rendering.models.uml.framework.buffer.UploadDevice;
import lib.kasuga.rendering.models.uml.framework.buffer.UploadRing;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.BitSet;

/**
 * OpenGL adapter for the backend-independent upload ring. Keeps the historic
 * constructors, stats and native FloatBuffer view available to existing callers.
 */
public final class GpuUploadRing implements UploadBuffer {
    public interface Device extends UploadDevice {}

    public record Stats(long uploads, long bytesUploaded, long storageAllocations,
                        long busyOrphans, long fencePolls, long fencesCreated, long policyOrphans) {}

    private final UploadRing ring;

    public GpuUploadRing() { this(3, new GlDevice()); }

    public GpuUploadRing(int capacity, UploadDevice device) {
        ring = new UploadRing(capacity, device);
    }

    @Override public int bufferId() { return ring.bufferId(); }
    @Override public int capacityBytes() { return ring.capacityBytes(); }
    public String strategy() { return ring.strategy(); }
    public Stats stats() {
        UploadRing.Stats stats = ring.stats();
        return new Stats(stats.uploads(), stats.bytesUploaded(), stats.storageAllocations(),
                stats.busyOrphans(), stats.fencePolls(), stats.fencesCreated(), stats.policyOrphans());
    }

    @Override public int upload(ByteBuffer snapshot) { return ring.upload(snapshot); }

    public int upload(FloatBuffer snapshot) {
        if (!snapshot.isDirect()) throw new IllegalArgumentException("Direct snapshot required");
        return upload(MemoryUtil.memByteBuffer(MemoryUtil.memAddress(snapshot),
                Math.multiplyExact(snapshot.remaining(), Float.BYTES)));
    }

    @Override public int upload(ByteBuffer snapshot, BitSet dirty, int stride, int mergeGap, boolean force) {
        return ring.upload(snapshot, dirty, stride, mergeGap, force);
    }

    @Override public void markSubmitted() { ring.markSubmitted(); }
    @Override public void close() { ring.close(); }

    /** COPY_WRITE avoids VAO changes and Minecraft's cached ARRAY_BUFFER binding. */
    public static class GlDevice implements Device {
        @Override public int createBuffer() { return GL15.glGenBuffers(); }
        @Override public void deleteBuffer(int buffer) { GL15.glDeleteBuffers(buffer); }
        @Override public long fence() { return GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0); }
        @Override public boolean ready(long fence) {
            int result = GL32.glClientWaitSync(fence, 0, 0L);
            if (result == GL32.GL_WAIT_FAILED) throw new IllegalStateException("Upload fence poll failed");
            return result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED;
        }
        @Override public void deleteFence(long fence) { GL32.glDeleteSync(fence); }
        @Override public void allocate(int buffer, int bytes) {
            int previous = GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);
            try {
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
                GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, bytes, GL15.GL_STREAM_DRAW);
            } finally { GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, previous); }
        }
        @Override public void write(int buffer, ByteBuffer snapshot, BitSet elements, int stride, int gap) {
            if (elements.isEmpty()) return;
            int previous = GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);
            try {
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
                int first = elements.nextSetBit(0) * stride;
                int length = elements.length() * stride - first;
                // The ring has already retired or orphaned this store. Let the
                // CPU write it without a second, implicit driver synchronization.
                ByteBuffer mapped = GL30.glMapBufferRange(GL31.GL_COPY_WRITE_BUFFER, first, length,
                        GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT);
                if (mapped == null) throw new IllegalStateException("Cannot map upload buffer");
                try {
                    for (int start = elements.nextSetBit(0); start >= 0;) {
                        int end = elements.nextClearBit(start), next = elements.nextSetBit(end);
                        while (next >= 0 && next - end <= gap) { end = elements.nextClearBit(next); next = elements.nextSetBit(end); }
                        MemoryUtil.memCopy(MemoryUtil.memAddress(snapshot) + (long) start * stride,
                                MemoryUtil.memAddress(mapped) + (long) start * stride - first, (long) (end - start) * stride);
                        start = next;
                    }
                } finally {
                    if (!GL15.glUnmapBuffer(GL31.GL_COPY_WRITE_BUFFER))
                        throw new IllegalStateException("Upload buffer contents lost while unmapping");
                }
            } finally { GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, previous); }
        }
    }
}
