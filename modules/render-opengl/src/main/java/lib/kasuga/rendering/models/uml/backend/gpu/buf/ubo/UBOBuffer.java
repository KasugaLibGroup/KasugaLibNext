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
        allocateStorage(this, GL31.GL_UNIFORM_BUFFER, id, null);
    }

    public UBOBuffer(ByteBuffer byteData, int usage) {
        super(byteData.remaining(), usage, TYPE, new UBOContext());
        id = GL15.glGenBuffers();
        allocateStorage(this, GL31.GL_UNIFORM_BUFFER, id, byteData);
    }

    @Override
    public void resize(long newSize, boolean copyDataToNewBuffer) {
        checkOpen(CLOSE_MSG);
        resizeStorage(this, GL31.GL_UNIFORM_BUFFER, id, newSize, copyDataToNewBuffer,
                "Buffer size must be greater than zero.");
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
        updateStorage(this, GL31.GL_UNIFORM_BUFFER, id, 0, data,
                "Data size exceeds buffer capacity.");
    }

    @Override
    public void updateRange(ByteBuffer data, long offset) {
        checkOpen(CLOSE_MSG);
        updateStorage(this, GL31.GL_UNIFORM_BUFFER, id, offset, data,
                "Data size with offset exceeds buffer capacity.");
    }

    @Override
    public void close() throws Exception {
        if (isClosed) return;
        if (!context.isEmpty()) context.exit(this);
        GL15.glDeleteBuffers(id);
        super.close();
    }
}
