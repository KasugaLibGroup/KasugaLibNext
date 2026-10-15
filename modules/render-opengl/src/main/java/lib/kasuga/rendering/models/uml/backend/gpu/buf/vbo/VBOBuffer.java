package lib.kasuga.rendering.models.uml.backend.gpu.buf.vbo;

import lib.kasuga.rendering.models.uml.backend.gpu.buf.GpuBuffer;
import lombok.Getter;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class VBOBuffer extends GpuBuffer<VBOBuffer> {

    public static final String
            TYPE = "VBO",
            CLOSE_MSG = "Cannot operate on a closed VBO Buffer.";

    @Getter
    private final int id;

    public VBOBuffer(long size, int usage) {
        super(size, usage, TYPE, new VBOContext());
        this.id = GL15.glGenBuffers();

        context.enter(this);
        try {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, size, usage);
        } finally {
            context.exit(this);
        }
    }

    public VBOBuffer(ByteBuffer byteData, int usage) {
        super(byteData.remaining(), usage, TYPE, new VBOContext());
        id = GL15.glGenBuffers();

        context.enter(this);
        try {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, byteData, usage);
        } finally {
            context.exit(this);
        }
    }

    @Override
    public void resize(long newSize, boolean copyDataToNewBuffer) {
        checkOpen(CLOSE_MSG);
        if (newSize == size) return;
        if (newSize <= 0) {
            throw new IllegalArgumentException("Buffer size must be greater than zero.");
        }

        final long oldSize = size;
        if (!copyDataToNewBuffer || oldSize == 0) {
            context.enter(this);

            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, newSize, usage);
            } finally {
                context.exit(this);
            }
        } else {
            int bytesToRead = (int) Math.min(oldSize, Integer.MAX_VALUE);
            ByteBuffer tempBuffer = ByteBuffer.allocateDirect(bytesToRead);

            context.enter(this);
            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
                GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0, tempBuffer);
                tempBuffer.flip();

                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, newSize, usage);
                int copySize = (int) Math.min(newSize, oldSize);

                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, tempBuffer.limit(copySize));
                MemoryUtil.memFree(tempBuffer);
            } finally {
                context.exit(this);
            }
        }
    }

    @Override
    public void bind(int index) {
        checkOpen(CLOSE_MSG);
        context.enter(this);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
    }

    @Override
    public void unbind() {
        checkOpen(CLOSE_MSG);
        context.exit(this);
    }

    @Override
    public void updateAll(ByteBuffer data) {
        checkOpen(CLOSE_MSG);
        if (data.remaining() > size) {
            throw new IllegalArgumentException("Data size exceeds buffer capacity.");
        }

        context.enter(this);
        try {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, usage);
        } finally {
            context.exit(this);
        }
    }

    @Override
    public void updateRange(ByteBuffer data, long offset) {
        checkOpen(CLOSE_MSG);
        if (data.remaining() + offset > size) {
            throw new IllegalArgumentException("Data range exceeds buffer capacity.");
        }
        context.enter(this);
        try {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset, data);
        } finally {
            context.exit(this);
        }
    }

    @Override
    public void close() throws Exception {
        if (isClosed) return;
        if (!context.isEmpty()) context.exit(this);
        GL15.glDeleteBuffers(id);
        super.close();
    }
}
