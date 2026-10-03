package lib.kasuga.rendering.output.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Preserves distinct read/draw bindings and viewport, including exceptional exits. */
public final class FramebufferScope implements AutoCloseable {
    @FunctionalInterface
    public interface Restorer {
        void restore(int readFramebuffer, int drawFramebuffer, int[] viewport);
    }

    public static final Restorer OPENGL = (read, draw, viewport) -> {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    };

    private final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int[] viewport = new int[4];
    private final Restorer restorer;

    public FramebufferScope(Restorer restorer) {
        this.restorer = java.util.Objects.requireNonNull(restorer);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
    }

    @Override
    public void close() { restorer.restore(read, draw, viewport); }
}
