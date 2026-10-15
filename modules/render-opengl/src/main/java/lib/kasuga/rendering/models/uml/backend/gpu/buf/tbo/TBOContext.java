package lib.kasuga.rendering.models.uml.backend.gpu.buf.tbo;

import lib.kasuga.rendering.models.uml.backend.gpu.GpuContext;
import org.lwjgl.opengl.*;

public class TBOContext implements GpuContext<TBOBuffer> {

    int savedBinding = -1, savedTextureBinding = -1, savedActiveTexture = -1;

    @Override
    public void enter(TBOBuffer buffer) {
        if (!isEmpty() && (savedBinding != buffer.getBufferId() || savedTextureBinding != buffer.getTextureId())) {
            throw new IllegalStateException("A different buffer is already entered in the context.");
        }
        savedBinding = GL15.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
        savedActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        savedTextureBinding = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
    }

    @Override
    public void exit(TBOBuffer buffer) {
        if (isEmpty()) {
            throw new IllegalStateException("No buffer was entered in the context.");
        }
        GL13.glActiveTexture(savedActiveTexture);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, savedTextureBinding);
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, savedBinding);
        savedBinding = -1;
        savedTextureBinding = -1;
        savedActiveTexture = -1;
    }

    @Override
    public boolean isEmpty() {
        return savedBinding == -1 || savedActiveTexture == -1 || savedTextureBinding == -1;
    }
}
