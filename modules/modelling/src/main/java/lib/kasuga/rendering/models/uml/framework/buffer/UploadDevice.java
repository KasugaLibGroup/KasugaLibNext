package lib.kasuga.rendering.models.uml.framework.buffer;

import java.nio.ByteBuffer;
import java.util.BitSet;

/**
 * Device operations used by upload rings. Handles are opaque; zero means absent.
 * ready must poll without waiting. allocate replaces storage while queued reads
 * retain the old allocation; write updates only the requested element ranges.
 * Upload operations preserve the caller's snapshot position and limit. The ring
 * owns handles/fences; the device owns host API calls and binding restoration.
 */
public interface UploadDevice {
    default boolean orphanEveryUpload() { return false; }
    default String strategy() { return "common-fenced-mapping"; }
    default void uploadOrphaned(int buffer, ByteBuffer snapshot) { throw new UnsupportedOperationException(); }
    int createBuffer();
    void deleteBuffer(int buffer);
    long fence();
    boolean ready(long fence);
    void deleteFence(long fence);
    void allocate(int buffer, int bytes);
    void write(int buffer, ByteBuffer snapshot, BitSet elements, int stride, int mergeGap);
}
