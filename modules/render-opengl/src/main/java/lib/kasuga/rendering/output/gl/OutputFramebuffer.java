package lib.kasuga.rendering.output.gl;

import lib.kasuga.rendering.output.FrameBlitter;
import lib.kasuga.rendering.output.FrameOutputTarget;
import lib.kasuga.rendering.output.FrameTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** RGBA8 completed-frame destination; allocation and lifetime are render-thread-owned. */
public final class OutputFramebuffer implements FrameOutputTarget<FrameTexture> {
    private final FramebufferScope.Restorer restorer;
    private int framebuffer, texture, width, height;
    private boolean closed;

    public OutputFramebuffer() { this(FramebufferScope.OPENGL); }
    public OutputFramebuffer(FramebufferScope.Restorer restorer) { this.restorer = Objects.requireNonNull(restorer); }

    @Override
    public FrameTexture capture(FrameBlitter blitter, int width, int height) {
        if (closed) throw new IllegalStateException("Output framebuffer is closed");
        Objects.requireNonNull(blitter, "blitter");
        try (var ignored = new FramebufferScope(restorer)) {
            ensureSize(width, height);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            clearOpaque(false);
            // Reuse the host's FINAL blit, including its shader/compositing, rather
            // than copying an intermediate world target or rerendering the scene.
            blitter.draw(width, height);
            // Sodium may implement the final blit with glBlitFramebuffer, copying
            // source alpha despite Minecraft's RGB-only screen contract.
            clearOpaque(true);
            return new FrameTexture(framebuffer, texture, width, height);
        }
    }

    private void ensureSize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height && framebuffer != 0) return;
        int maximum = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        if (newWidth <= 0 || newHeight <= 0 || newWidth > maximum || newHeight > maximum) {
            throw new IllegalArgumentException("Output size is outside texture limits");
        }
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int nextTexture = 0, nextFramebuffer = 0;
        try {
            nextTexture = GL11.glGenTextures();
            if (nextTexture == 0) throw new IllegalStateException("Cannot allocate output texture");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, nextTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, newWidth, newHeight,
                    0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            nextFramebuffer = GL30.glGenFramebuffers();
            if (nextFramebuffer == 0) throw new IllegalStateException("Cannot allocate output framebuffer");
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, nextFramebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, nextTexture, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete frame output framebuffer");
            }
        } catch (RuntimeException | Error failure) {
            if (nextFramebuffer != 0) GL30.glDeleteFramebuffers(nextFramebuffer);
            if (nextTexture != 0) GL11.glDeleteTextures(nextTexture);
            throw failure;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        if (texture != 0) GL11.glDeleteTextures(texture);
        framebuffer = nextFramebuffer;
        texture = nextTexture;
        width = newWidth;
        height = newHeight;
    }

    /** The native screen blit writes RGB only; initialize output alpha to opaque. */
    private void clearOpaque(boolean alphaOnly) {
        try (var stack = MemoryStack.stackPush()) {
            long clear = stack.nmalloc(4, 16);
            long mask = stack.nmalloc(1, 4);
            GL11.nglGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clear);
            GL11.nglGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            try {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glColorMask(!alphaOnly, !alphaOnly, !alphaOnly, true);
                GL11.glClearColor(0, 0, 0, 1);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            } finally {
                GL11.glClearColor(MemoryUtil.memGetFloat(clear), MemoryUtil.memGetFloat(clear + 4),
                        MemoryUtil.memGetFloat(clear + 8), MemoryUtil.memGetFloat(clear + 12));
                GL11.glColorMask(MemoryUtil.memGetByte(mask) != 0, MemoryUtil.memGetByte(mask + 1) != 0,
                        MemoryUtil.memGetByte(mask + 2) != 0, MemoryUtil.memGetByte(mask + 3) != 0);
                if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        if (texture != 0) GL11.glDeleteTextures(texture);
        framebuffer = texture = 0;
    }
}
