package lib.kasuga.rendering.models.uml.backend.gpu.buf.ubo;

import lib.kasuga.rendering.models.uml.backend.gpu.buf.GpuBuffer;
import lombok.Getter;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class UBOBuffer extends GpuBuffer<UBOBuffer> {

    @Getter
    private final int id;

    public static final String
            TYPE = "UBO",
            CLOSE_MSG = "Cannot operate on a closed UBO Buffer.";

    public UBOBuffer(long size, int usage) {
        super(size, usage, TYPE, new UBOContext());
        id = GL15.glGenBuffers();
        context.enter(this);
        try {
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, id);
            GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, size, usage);
        } finally {
            context.exit(this);
        }
    }

    public UBOBuffer(ByteBuffer byteData, int usage) {
        super(byteData.remaining(), usage, TYPE, new UBOContext());
        id = GL15.glGenBuffers();
        context.enter(this);
        try {
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, id);
            GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, byteData, usage);
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
                GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, id);
                GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, newSize, usage);
            } finally {
                context.exit(this);
            }
        } else {
            // Create a temporary buffer to hold the existing data
            ByteBuffer tempBuffer = ByteBuffer.allocateDirect((int) oldSize);
            context.enter(this);
            try {
                GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, id);
                GL15.glGetBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, tempBuffer);
                tempBuffer.flip();

                // Resize the buffer
                GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, newSize, usage);

                // Restore the data to the resized buffer
                GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, tempBuffer);

                MemoryUtil.memFree(tempBuffer);
            } finally {
                context.exit(this);
            }
        }
    }

    @Override
    public void bind(int index) {
        checkOpen(CLOSE_MSG);
        if (index < 0) return;
        context.enter(this);
        GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, id);
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
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, id);
            GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, data, usage);
        } finally {
            context.exit(this);
        }
    }

    @Override
    public void updateRange(ByteBuffer data, long offset) {
        checkOpen(CLOSE_MSG);
        if (data.remaining() + offset > size) {
            throw new IllegalArgumentException("Data size with offset exceeds buffer capacity.");
        }
        context.enter(this);
        try {
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, id);
            GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, offset, data);
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
