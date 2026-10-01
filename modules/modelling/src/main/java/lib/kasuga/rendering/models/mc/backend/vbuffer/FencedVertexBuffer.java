package lib.kasuga.rendering.models.mc.backend.vbuffer;

import com.mojang.blaze3d.vertex.*;
import lib.kasuga.mixins.client.AccessorByteBufferBuilder;
import lib.kasuga.mixins.client.AccessorVertexBuffer;
import lib.kasuga.rendering.models.mc.backend.FlatModelData;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.uml.backend.gpu.GpuUploadRing;
import lib.kasuga.rendering.models.uml.backend.gpu.SkinningAttributes;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Keeps each ring VBO's VAO and Minecraft index metadata together. */
public final class FencedVertexBuffer implements AutoCloseable {
    private final VertexFormat format;
    private final VertexFormat.Mode mode;
    private final int mergeGap;
    private final Map<Integer, VertexBuffer> buffers = new HashMap<>();
    private GpuUploadRing ring;
    private ByteBuffer snapshot;
    private int vertices = -1;
    private boolean closed;

    public FencedVertexBuffer(VertexFormat format, VertexFormat.Mode mode, int mergeGap) {
        this.format = format; this.mode = mode; this.mergeGap = mergeGap;
    }

    public VertexBuffer current() {
        VertexBuffer buffer = ring == null ? null : buffers.get(ring.bufferId());
        // Preserve safety for callers that obtain the Minecraft buffer and draw
        // directly, without using IVertexBuffer.draw/markSubmitted.
        if (buffer != null) ring.markSubmitted();
        return buffer;
    }

    public void upload(ByteBuffer data, BitSet dirty, boolean force) {
        if (closed) throw new IllegalStateException("Vertex upload ring is closed");
        int count = data.remaining() / format.getVertexSize();
        if (data.remaining() % format.getVertexSize() != 0) throw new IllegalArgumentException("Incomplete vertex");
        if (count != vertices) {
            if (ring != null) ring.close();
            vertices = count;
            ring = new GpuUploadRing(3, new GpuUploadRing.GlDevice() {
                @Override public int createBuffer() {
                    VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
                    try (ByteBufferBuilder builder = new ByteBufferBuilder(snapshot.remaining())) {
                        int length = snapshot.remaining();
                        long target = builder.reserve(length);
                        MemoryUtil.memCopy(MemoryUtil.memAddress(snapshot), target, length);
                        ((AccessorByteBufferBuilder) builder).setWriteOffset(length);
                        MeshData mesh = new MeshData(Objects.requireNonNull(builder.build()), new MeshData.DrawState(
                                format, vertices, mode.indexCount(vertices), mode, VertexFormat.IndexType.least(vertices)));
                        BufferUploader.reset();
                        buffer.bind();
                        try {
                            buffer.upload(mesh);
                            int binding = format.getElements().indexOf(RenderState.BONE_BINDING_TYPE);
                            if (binding >= 0) SkinningAttributes.bindingType(binding, format.getVertexSize(),
                                    FlatModelData.genVertexFormat(format).get(RenderState.BONE_BINDING_TYPE));
                        } finally { VertexBuffer.unbind(); BufferUploader.reset(); }
                        int id = ((AccessorVertexBuffer) buffer).getVertexBufferId();
                        buffers.put(id, buffer);
                        return id;
                    } catch (RuntimeException | Error error) { buffer.close(); throw error; }
                }
                @Override public void deleteBuffer(int id) {
                    VertexBuffer buffer = buffers.remove(id);
                    if (buffer != null) buffer.close();
                }
            });
            force = true;
        }
        snapshot = data;
        try { ring.upload(data, dirty, format.getVertexSize(), mergeGap, force); }
        finally { snapshot = null; }
    }

    public void markSubmitted() { if (ring != null) ring.markSubmitted(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (ring != null) ring.close();
        buffers.clear();
    }
}
