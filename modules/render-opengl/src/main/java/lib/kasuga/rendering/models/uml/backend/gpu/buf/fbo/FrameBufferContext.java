package lib.kasuga.rendering.models.uml.backend.gpu.buf.fbo;

import lib.kasuga.rendering.models.uml.backend.gpu.GpuContext;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public class FrameBufferContext implements GpuContext<FrameBuffer> {

    private int savedDrawFbo = -1, savedReadFbo = -1;

    public void enter(FrameBuffer buffer) {
        if (!isEmpty()) {
            throw new IllegalStateException("Already in a framebuffer context");
        }
        savedDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        savedReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

    }

    public void exit(FrameBuffer buffer) {
        if (isEmpty()) {
            throw new IllegalStateException("Not currently in a framebuffer context");
        }
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDrawFbo);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedReadFbo);
        savedDrawFbo = savedReadFbo = -1;
    }

    @Override
    public boolean isEmpty() {
        return savedDrawFbo < 0;
    }
}
