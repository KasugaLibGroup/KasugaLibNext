package lib.kasuga.rendering.models.uml.backend.gpu.vao;

import lib.kasuga.rendering.models.uml.backend.gpu.buf.GpuBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.vbo.VBOBuffer;
import lombok.Getter;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

public class VertexArrayObject extends GpuBuffer<VertexArrayObject> {

    public static final String
            TYPE = "VAO",
            CLOSE_MSG = "Cannot operate on a closed VertexArrayObject.";

    @Getter
    private final int vaoId;

    public VertexArrayObject(long size, int usage) {
        super(size, usage, TYPE, new VertexArrayContext());
        vaoId = GL30.glGenVertexArrays();
    }

    public void bindVertexBuffer(VBOBuffer buffer) {
        checkOpen(CLOSE_MSG);
        if (context.isEmpty()) bind(0);
        buffer.bind(0);
    }

    public void attribute(int index, int size, int strideBytes, long offsetBytes) {
        checkOpen(CLOSE_MSG);
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, size, GL11.GL_FLOAT, false, strideBytes, offsetBytes);
    }

    public void drawArrays(int mode, int first, int count) {
        checkOpen(CLOSE_MSG);
        GL11.glDrawArrays(mode, first, count);
    }

    @Override
    public void bind(int index) {
        checkOpen(CLOSE_MSG);
        if (!context.isEmpty()) return;
        context.enter(this);
    }

    @Override
    public void unbind() {
        checkOpen(CLOSE_MSG);
        context.exit(this);
    }

    @Override
    public void updateAll(ByteBuffer data) {
        throw new UnsupportedOperationException("VAO has no buffer.");
    }

    @Override
    public void updateRange(ByteBuffer data, long offset) {
        throw new UnsupportedOperationException("VAO has no buffer.");
    }

    @Override
    public void resize(long newSize, boolean copyDataToNewBuffer) {
        throw new UnsupportedOperationException("VAO has no buffer.");
    }

    @Override
    public void close() throws Exception {
        if (isClosed) return;
        if (!context.isEmpty()) context.exit(this);
        GL30.glDeleteVertexArrays(vaoId);
        super.close();
    }
}
