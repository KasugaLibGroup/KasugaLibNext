package lib.kasuga.rendering.models.uml.backend.gpu.buf.vbo;

import lib.kasuga.rendering.models.uml.backend.gpu.GpuContext;
import org.lwjgl.opengl.GL15;

public class VBOContext implements GpuContext<VBOBuffer> {

    private int savedBinding = -1;

    @Override
    public void enter(VBOBuffer buffer) {
        if (!isEmpty() && savedBinding != buffer.getId()) {
            throw new IllegalStateException("A different buffer is already entered in the context.");
        }
        savedBinding = GL15.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    }

    @Override
    public void exit(VBOBuffer buffer) {
        if (isEmpty()) {
            throw new IllegalStateException("No buffer was entered in the context.");
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedBinding);
    }

    @Override
    public boolean isEmpty() {
        return savedBinding == -1;
    }
}
