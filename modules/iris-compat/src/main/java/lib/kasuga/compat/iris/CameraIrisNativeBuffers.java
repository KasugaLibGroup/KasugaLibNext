package lib.kasuga.compat.iris;
import net.minecraft.client.renderer.MultiBufferSource;
import net.irisshaders.batchedentityrendering.impl.MemoryTrackingBuffer;
final class CameraIrisNativeBuffers {
    static void close(MultiBufferSource.BufferSource source) {
        if (source instanceof MemoryTrackingBuffer buffer) buffer.freeAndDeleteBuffer();
    }
}
