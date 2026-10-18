package lib.kasuga.rendering.models.uml.backend.gpu.buf;

import lib.kasuga.rendering.models.uml.backend.gpu.GpuContext;
import lib.kasuga.rendering.models.uml.backend.gpu.GpuObject;
import lombok.Getter;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

import java.nio.*;

public abstract class GpuBuffer<T extends GpuBuffer<T>> implements GpuObject<T> {

    @Getter
    protected long size;

    @Getter
    protected final int usage;

    @Getter
    protected final String bufferType;

    @Getter
    protected boolean isClosed = false;

    @Getter
    protected final GpuContext<T> context;

    /**
     * Creates a new GPU buffer with the specified size, usage, buffer type, and context.
     * @param size the size of the buffer in bytes
     * @param usage the usage pattern of the buffer (e.g., {@link org.lwjgl.opengl.GL15#GL_STATIC_DRAW}, {@link org.lwjgl.opengl.GL15#GL_DYNAMIC_DRAW}, {@link org.lwjgl.opengl.GL15#GL_STREAM_DRAW})
     * @param bufferType a string representing the type of buffer (e.g., "VBO", "UBO")
     * @param context the GPU context that manages this buffer
     */
    public GpuBuffer(long size, int usage, String bufferType, GpuContext<T> context) {
        this.size = size;
        this.usage = usage;
        this.bufferType = bufferType;
        this.context = context;
    }

    public abstract void bind(int index);

    public abstract void unbind();

    public abstract void updateAll(ByteBuffer data);

    public abstract void updateRange(ByteBuffer data, long offset);

    public abstract void resize(long newSize, boolean copyDataToNewBuffer);

    public void checkOpen(String operation) {
        if (isClosed) {
            throw new IllegalStateException(operation);
        }
    }

    /** Resizes bound storage, retaining only the surviving prefix with a matching native allocator/free. */
    protected void resizeStorage(int target, int id, long newSize, boolean preserve) {
        GL15.glBindBuffer(target, id);
        if (!preserve || size == 0) {
            GL15.glBufferData(target, newSize, usage);
        } else {
            ByteBuffer temporary = MemoryUtil.memAlloc(Math.toIntExact(Math.min(size, newSize)));
            try {
                // LWJGL readback does not advance the buffer's position; no flip is needed.
                GL15.glGetBufferSubData(target, 0, temporary);
                GL15.glBufferData(target, newSize, usage);
                GL15.glBufferSubData(target, 0, temporary);
            } finally {
                MemoryUtil.memFree(temporary);
            }
        }
        size = newSize;
    }

    @Override
    public void close() throws Exception {
        isClosed = true;
    }
}
