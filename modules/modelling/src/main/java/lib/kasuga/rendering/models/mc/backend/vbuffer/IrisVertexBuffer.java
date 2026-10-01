package lib.kasuga.rendering.models.mc.backend.vbuffer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lib.kasuga.mixins.client.AccessorByteBufferBuilder;
import lib.kasuga.rendering.models.mc.backend.FlatModelData;
import lombok.Getter;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class IrisVertexBuffer implements IVertexBuffer {

    @Getter
    private final FlatModelData modelData;

    @Getter
    private final VertexFormat format;

    @Getter
    private final VertexFormat.Mode meshMode;

    @Nullable
    private ByteBufferBuilder[] multiBufferBuilders;

    @Nullable
    private CompletableFuture[] futures;

    private final int multiThreadedThreshold, maxMergeGap;

    @Getter
    private final int vertexCount, vertexSize;

    @Getter
    private final ExecutorService executor;

    private final FencedVertexBuffer uploadRing;
    private ByteBuffer packedVertices;

    private boolean valid;

    public IrisVertexBuffer(FlatModelData modelData, VertexFormat format,
                            int multiThreadedThreshold, int maxMergeGap,
                            ExecutorService executor) {
        this.modelData = modelData;
        this.vertexCount = modelData.getVertexCount();
        this.format = format;
        this.vertexSize = format.getVertexSize();
        this.meshMode = modelData.getMcMeshMode();
        this.maxMergeGap = maxMergeGap;
        this.executor = executor;
        this.multiThreadedThreshold = multiThreadedThreshold;
        this.multiBufferBuilders = null;
        this.futures = null;
        this.uploadRing = new FencedVertexBuffer(format, meshMode, maxMergeGap);
        this.valid = false;
    }

    public void uploadGpuBuffer() {
        ByteBufferBuilder byteBufferBuilder = null;
        try {
            if (vertexCount < multiThreadedThreshold) {
                byteBufferBuilder = fillGpuCache(null, 0, modelData.getVertexCount());
            } else {
                int taskCount = Math.ceilDiv(vertexCount, multiThreadedThreshold);
                if (multiBufferBuilders == null || multiBufferBuilders.length != taskCount) {
                    if (multiBufferBuilders != null) {
                        for (ByteBufferBuilder bbb : multiBufferBuilders) {
                            if (bbb != null) bbb.close();
                        }
                    }
                    multiBufferBuilders = new ByteBufferBuilder[taskCount];
                }
                for (int i = 0; i < taskCount; i++) {
                    if (i == taskCount - 1) {
                        multiBufferBuilders[i] = new ByteBufferBuilder((vertexCount - i * multiThreadedThreshold) * vertexSize);
                    } else {
                        multiBufferBuilders[i] = new ByteBufferBuilder(multiThreadedThreshold * vertexSize);
                    }
                }
                if (futures == null || futures.length != taskCount) {
                    futures = new CompletableFuture[taskCount];
                }
                for (int i = 0; i < taskCount; i++) {
                    final int index = i;
                    final int taskStart = i * multiThreadedThreshold;
                    final int taskEnd = Math.min(taskStart + multiThreadedThreshold, vertexCount);
                    futures[i] = (CompletableFuture.runAsync(() -> {
                        fillGpuCache(multiBufferBuilders[index], taskStart, taskEnd - taskStart);
                    }, executor));
                }
                byteBufferBuilder = new ByteBufferBuilder(vertexCount * vertexSize);
                long pointer = ((AccessorByteBufferBuilder) byteBufferBuilder).getPointer();
                CompletableFuture.allOf(futures).join();

                for (int i = 0; i < taskCount; i++) {
                    ByteBufferBuilder bbb = multiBufferBuilders[i];
                    int taskStart = i * multiThreadedThreshold;
                    int taskVertices = Math.min(multiThreadedThreshold, vertexCount - taskStart);
                    int byteCount = taskVertices * vertexSize;
                    long p = ((AccessorByteBufferBuilder) bbb).getPointer();
                    MemoryUtil.memCopy(p, pointer, byteCount);
                    bbb.close();
                    pointer += byteCount;
                    multiBufferBuilders[i] = null;
                }

                ((AccessorByteBufferBuilder) byteBufferBuilder).setWriteOffset(vertexCount * vertexSize);
            }
            try (ByteBufferBuilder.Result result = Objects.requireNonNull(byteBufferBuilder.build())) {
                ByteBuffer data = result.byteBuffer();
                if (packedVertices == null) packedVertices = MemoryUtil.memAlloc(vertexCount * vertexSize);
                MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(packedVertices), data.remaining());
                uploadRing.upload(packedVertices.duplicate().clear(), null, true);
            }
        } finally {
            // A worker can fail (or submission can be rejected). Never free staging
            // memory until every already-started worker has left it.
            if (futures != null) {
                for (CompletableFuture<?> future : futures) {
                    if (future != null) future.handle((value, failure) -> null).join();
                }
            }
            if (multiBufferBuilders != null) {
                for (int i = 0; i < multiBufferBuilders.length; i++) {
                    if (multiBufferBuilders[i] != null) multiBufferBuilders[i].close();
                    multiBufferBuilders[i] = null;
                }
            }
            if (byteBufferBuilder != null) byteBufferBuilder.close();
        }
        valid = true;
    }

    @Override
    public void draw(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, ShaderInstance shader) {
        VertexBuffer buffer = uploadRing.current();
        if (buffer == null) return;
        try { buffer.draw(); } finally { markSubmitted(); }
    }

    @Override
    public void updateGpuBuffer(@Nullable BitSet dirtyVertices, boolean forceUploadAll) {
        if (forceUploadAll || uploadRing.current() == null || dirtyVertices == null) {
            uploadGpuBuffer();
            return;
        }
        int count = dirtyVertices.cardinality();
        if (count == 0) return;
        if ((long) count * 4 >= (long) vertexCount * 3) {
            uploadGpuBuffer();
            return;
        }
        RenderSystem.assertOnRenderThread();
        int start = dirtyVertices.nextSetBit(0);
        while (start >= 0) {
            int end = dirtyVertices.nextClearBit(start);
            int next = dirtyVertices.nextSetBit(end);
            while (next >= 0 && next - end <= maxMergeGap) {
                end = dirtyVertices.nextClearBit(next);
                next = dirtyVertices.nextSetBit(end);
            }
            end = Math.min(end, vertexCount);
            try (ByteBufferBuilder builder = new ByteBufferBuilder((end - start) * vertexSize)) {
                fillGpuCache(builder, start, end - start);
                try (ByteBufferBuilder.Result result = Objects.requireNonNull(builder.build())) {
                    ByteBuffer data = result.byteBuffer();
                    MemoryUtil.memCopy(MemoryUtil.memAddress(data),
                            MemoryUtil.memAddress(packedVertices) + (long) start * vertexSize, data.remaining());
                }
            }
            start = next;
        }
        uploadRing.upload(packedVertices.duplicate().clear(), dirtyVertices, false);
    }

    @Override public void markSubmitted() { uploadRing.markSubmitted(); }

    protected ByteBufferBuilder fillGpuCache(ByteBufferBuilder byteBufferBuilder, int startIndex, int numVertices) {
        ByteBufferBuilder bbb;
        if (byteBufferBuilder == null) {
            int vSize = format.getVertexSize();
            bbb = new ByteBufferBuilder(numVertices * vSize);
        } else {
            bbb = byteBufferBuilder;
            ((AccessorByteBufferBuilder) bbb).setWriteOffset(0);
        }
        BufferBuilder bufferBuilder = new BufferBuilder(bbb, meshMode, format);

        int bufOffset, vOffset, color;
        ByteBuffer src = modelData.getBuffer();
        float x, y, z, nx, ny, nz, u, v;
        int srcVertexSize = modelData.getVertexSize();
        for (int i = startIndex; i < startIndex + numVertices; i++) {
            vOffset = i * srcVertexSize;

            bufOffset = vOffset + modelData.getColorOffset();
            color = src.getInt(bufOffset);
            if (!FlatModelData.IS_LITTLE_ENDIAN) {
                color = Integer.reverseBytes(color);
            }
            color = (color & 0xFF00FF00) | ((color & 0x00FF0000) >> 16) | ((color & 0x000000FF) << 16);
            bufOffset = vOffset + modelData.getPosOffset();
            x = src.getFloat(bufOffset);
            y = src.getFloat(bufOffset + 4);
            z = src.getFloat(bufOffset + 8);

            bufOffset = vOffset + modelData.getNormOffset();
            nx = ((float) src.get(bufOffset)) / 127f;
            ny = ((float) src.get(bufOffset + 1)) / 127f;
            nz = ((float) src.get(bufOffset + 2)) / 127f;

            bufOffset = vOffset + modelData.getUv0Offset();
            u = src.getFloat(bufOffset);
            v = src.getFloat(bufOffset + 4);

            bufferBuilder.addVertex(x, y, z, color, u, v,
                    modelData.getOverlay(), modelData.getLightmap(),
                    nx, ny, nz);
        }
        return bbb;
    }

    @Override
    public VertexBuffer getVertexBuffer() {
        if (uploadRing.current() == null) {
            uploadGpuBuffer();
        }
        return uploadRing.current();
    }

    @Override
    public void close() throws Exception {
        uploadRing.close();
        if (packedVertices != null) { MemoryUtil.memFree(packedVertices); packedVertices = null; }
        if (multiBufferBuilders != null) {
            for (ByteBufferBuilder builder : multiBufferBuilders) if (builder != null) builder.close();
            multiBufferBuilders = null;
        }
        valid = false;
    }
}
