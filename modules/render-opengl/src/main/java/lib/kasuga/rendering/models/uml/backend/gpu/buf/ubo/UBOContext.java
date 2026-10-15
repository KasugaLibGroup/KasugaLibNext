package lib.kasuga.rendering.models.uml.backend.gpu.buf.ubo;

import lib.kasuga.rendering.models.uml.backend.gpu.GpuContext;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;

public class UBOContext implements GpuContext<UBOBuffer> {

    private int savedBinding = -1;

    @Override
    public void enter(UBOBuffer buffer) {
        if (!isEmpty() && savedBinding != buffer.getId()) {
            throw new IllegalStateException("A different buffer is already entered in the context.");
        }
        savedBinding = GL15.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
    }

    @Override
    public void exit(UBOBuffer buffer) {
        if (isEmpty()) {
            throw new IllegalStateException("No buffer was entered in the context.");
        }
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, savedBinding);
        savedBinding = -1;
    }

    @Override
    public boolean isEmpty() {
        return savedBinding < 0;
    }
}
