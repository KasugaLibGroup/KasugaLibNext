package lib.kasuga.rendering.models.uml.backend.gpu.buf.ubo;

import lib.kasuga.rendering.models.uml.backend.gpu.buf.GpuBuffer;
import lombok.Getter;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;

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

        context.enter(this);
        try {
            resizeStorage(GL31.GL_UNIFORM_BUFFER, id, newSize, copyDataToNewBuffer);
        } finally {
            context.exit(this);
        }
    }

    @Override
    public void bind(int index) {
        checkOpen(CLOSE_MSG);
        if (index < 0) return;
        ((UBOContext) context).enter(this, index);
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
            GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, data);
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
