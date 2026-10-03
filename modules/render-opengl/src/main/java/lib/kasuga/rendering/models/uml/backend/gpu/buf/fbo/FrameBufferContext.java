package lib.kasuga.rendering.models.uml.backend.gpu.buf.fbo;

import lib.kasuga.rendering.models.uml.backend.gpu.GpuContext;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public class FrameBufferContext implements GpuContext<FrameBuffer> {

    private int savedFbo = -1;

    public void enter(FrameBuffer buffer) {
        if (!isEmpty() && savedFbo != buffer.getFboId()) {
            throw new IllegalStateException("Already in a framebuffer context");
        }
        savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

    }

    public void exit(FrameBuffer buffer) {
        if (isEmpty()) {
            throw new IllegalStateException("Not currently in a framebuffer context");
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
        savedFbo = -1;
    }

    @Override
    public boolean isEmpty() {
        return savedFbo < 0;
    }
}
