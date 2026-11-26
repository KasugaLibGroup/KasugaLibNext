package lib.kasuga.rendering.models.uml.backend.gpu.buf.vbo;

import lib.kasuga.rendering.models.uml.backend.gpu.buf.GpuBuffer;
import lombok.Getter;
import org.lwjgl.opengl.GL15;

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
        allocateStorage(this, GL15.GL_ARRAY_BUFFER, id, null);
    }

    public VBOBuffer(ByteBuffer byteData, int usage) {
        super(byteData.remaining(), usage, TYPE, new VBOContext());
        id = GL15.glGenBuffers();
        allocateStorage(this, GL15.GL_ARRAY_BUFFER, id, byteData);
    }

    @Override
    public void resize(long newSize, boolean copyDataToNewBuffer) {
        checkOpen(CLOSE_MSG);
        resizeStorage(this, GL15.GL_ARRAY_BUFFER, id, newSize, copyDataToNewBuffer,
                "Buffer size must be greater than zero.");
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
        updateStorage(this, GL15.GL_ARRAY_BUFFER, id, 0, data,
                "Data size exceeds buffer capacity.");
    }

    @Override
    public void updateRange(ByteBuffer data, long offset) {
        checkOpen(CLOSE_MSG);
        updateStorage(this, GL15.GL_ARRAY_BUFFER, id, offset, data,
                "Data range exceeds buffer capacity.");
    }

    @Override
    public void close() throws Exception {
        if (isClosed) return;
        if (!context.isEmpty()) context.exit(this);
        GL15.glDeleteBuffers(id);
        super.close();
    }
}
