package lib.kasuga.rendering.models.uml.backend.gpu.buf.ssbo;

import lib.kasuga.rendering.models.uml.backend.gpu.GpuContext;
import org.lwjgl.opengl.*;

public class SSBOContext implements GpuContext<SSBOBuffer> {

    int savedBinding = -1;

    @Override
    public void enter(SSBOBuffer buffer) {
        if (!isEmpty() && savedBinding != buffer.getId()) {
            throw new IllegalStateException("A different buffer is already entered in the context.");
        }
        savedBinding = GL15.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
    }

    @Override
    public void exit(SSBOBuffer buffer) {
        if (isEmpty()) {
            throw new IllegalStateException("No buffer was entered in the context.");
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, savedBinding);
        savedBinding = -1;
    }

    @Override
    public boolean isEmpty() {
        return savedBinding == -1;
    }
}
