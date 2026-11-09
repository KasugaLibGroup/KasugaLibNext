package lib.kasuga.rendering.models.mc.backend.ui;

import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.gl.FramebufferScope;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.util.Objects;

/** Transparent RGBA8 GUI canvas with its own 24-bit depth buffer; owned by one world panel. */
public final class WorldUiFramebuffer implements AutoCloseable {
    public static final float GUI_VIEW_Z = -11000;
    private final FramebufferScope.Restorer restorer;
    private int framebuffer, texture, depth, width, height;
    private boolean closed;

    public WorldUiFramebuffer() { this(FramebufferScope.OPENGL); }
    public WorldUiFramebuffer(FramebufferScope.Restorer restorer) { this.restorer = Objects.requireNonNull(restorer); }

    /** GUI depth stays local; one unit of padding preserves shadows at MIN/MAX_GUI_Z. */
    public static Matrix4f guiProjection(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid GUI dimensions");
        return new Matrix4f().setOrtho(0, width, height, 0, 999, 21001);
    }

    public FrameTexture render(int width, int height, Runnable draw) {
        if (closed) throw new IllegalStateException("World UI canvas is closed");
        Objects.requireNonNull(draw);
        try (var ignored = new FramebufferScope(restorer)) {
            ensureSize(width, height);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL11.glViewport(0, 0, width, height);
            clear();
            draw.run();
            return new FrameTexture(framebuffer, texture, width, height);
        }
    }

    private void ensureSize(int nextWidth, int nextHeight) {
        if (framebuffer != 0 && width == nextWidth && height == nextHeight) return;
        int maximum = Math.min(GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE), GL11.glGetInteger(GL30.GL_MAX_RENDERBUFFER_SIZE));
        if (nextWidth <= 0 || nextHeight <= 0 || nextWidth > maximum || nextHeight > maximum) {
            throw new IllegalArgumentException("World UI canvas exceeds GPU texture limits");
        }
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousRenderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        int nextTexture = 0, nextDepth = 0, nextFramebuffer = 0;
        try {
            nextTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, nextTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, nextWidth, nextHeight,
                    0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            nextDepth = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, nextDepth);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, nextWidth, nextHeight);
            nextFramebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, nextFramebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, nextTexture, 0);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, nextDepth);
            if (nextTexture == 0 || nextDepth == 0 || nextFramebuffer == 0
                    || GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Cannot allocate complete world UI canvas");
            }
        } catch (RuntimeException | Error failure) {
            if (nextFramebuffer != 0) GL30.glDeleteFramebuffers(nextFramebuffer);
            if (nextDepth != 0) GL30.glDeleteRenderbuffers(nextDepth);
            if (nextTexture != 0) GL11.glDeleteTextures(nextTexture);
            throw failure;
        } finally {
            // Allocation does not change the texture/renderbuffer bindings observed by the draw callback.
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previousRenderbuffer);
        }
        deleteAttachments();
        framebuffer = nextFramebuffer;
        texture = nextTexture;
        depth = nextDepth;
        width = nextWidth;
        height = nextHeight;
    }

    private static void clear() {
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try (var stack = MemoryStack.stackPush()) {
            var colorMask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
            try {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDepthMask(true);
                GL11.glColorMask(true, true, true, true);
                GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[]{0, 0, 0, 0});
                GL30.glClearBufferfv(GL11.GL_DEPTH, 0, new float[]{1});
            } finally {
                GL11.glDepthMask(depthMask);
                GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
                if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
        }
    }

    private void deleteAttachments() {
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        if (texture != 0) GL11.glDeleteTextures(texture);
        if (depth != 0) GL30.glDeleteRenderbuffers(depth);
        framebuffer = texture = depth = 0;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        deleteAttachments();
    }
}
