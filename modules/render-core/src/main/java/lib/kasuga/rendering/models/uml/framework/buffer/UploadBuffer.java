package lib.kasuga.rendering.models.uml.framework.buffer;

import java.nio.ByteBuffer;
import java.util.BitSet;

/**
 * Render-thread snapshot storage with in-flight reuse protection. Uploads take a
 * complete direct snapshot, preserving its position/limit. Dirty bits identify
 * elements, stride counts bytes, mergeGap counts elements; null dirty bits or
 * force request a full refresh. Empty dirty bits may reuse unchanged storage.
 * The returned integer is an opaque device handle. Every consumer/replay must
 * call markSubmitted before another upload can replace its snapshot. Busy slots
 * must not be overwritten; implementations may orphan storage without waiting.
 * close is idempotent and retires all storage and fences.
 */
public interface UploadBuffer extends AutoCloseable {
    int bufferId();
    int capacityBytes();
    int upload(ByteBuffer snapshot);
    int upload(ByteBuffer snapshot, BitSet dirty, int elementStride, int mergeGap, boolean force);
    void markSubmitted();
}
