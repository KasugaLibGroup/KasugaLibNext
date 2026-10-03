package lib.kasuga.rendering.output.gl;

import lib.kasuga.rendering.output.FrameTexture;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.util.Objects;

/** A render-thread-owned GLFW window. Textures are shared; framebuffer objects stay context-local. */
public final class FramePreviewWindow implements AutoCloseable {
    public record Options(String title, int width, int height, boolean visible) {
        public Options {
            Objects.requireNonNull(title);
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Window dimensions must be positive");
        }
        public Options(String title, int width, int height) { this(title, width, height, true); }
    }
    private final Thread owner = Thread.currentThread();
    private final long parent;
    private long window;
    private GLCapabilities capabilities;
    private int readFramebuffer;
    private final boolean visible;
    private boolean shown;
    private long presentations;

    public FramePreviewWindow(long parent, Options options) {
        this(parent, options, GLFW.glfwGetWindowAttrib(parent, GLFW.GLFW_CONTEXT_VERSION_MAJOR),
                GLFW.glfwGetWindowAttrib(parent, GLFW.GLFW_CONTEXT_VERSION_MINOR));
    }

    /** Supply the parent's requested profile version when a driver reports a newer actual version (NSGL). */
    public FramePreviewWindow(long parent, Options options, int contextMajor, int contextMinor) {
        Objects.requireNonNull(options);
        if (parent == 0 || GLFW.glfwGetCurrentContext() != parent)
            throw new IllegalStateException("Create preview windows with the source GL context current");
        this.parent = parent; visible = options.visible();
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, contextMajor);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, contextMinor);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        try { window = GLFW.glfwCreateWindow(options.width(), options.height(), options.title(), 0, parent); }
        finally { GLFW.glfwDefaultWindowHints(); }
        if (window == 0) throw new IllegalStateException("Cannot create shared-context preview window");
        var previous = GL.getCapabilities();
        try {
            GLFW.glfwMakeContextCurrent(window);
            capabilities = GL.createCapabilities();
            GLFW.glfwSwapInterval(0);
            readFramebuffer = GL30.glGenFramebuffers();
        } catch (RuntimeException failure) {
            GLFW.glfwDestroyWindow(window); window = 0; throw failure;
        } finally { GLFW.glfwMakeContextCurrent(parent); GL.setCapabilities(previous); }
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Preview window belongs to its render thread");
    }
    public long handle() { checkThread(); return window; }
    public boolean isClosed() { checkThread(); return window == 0; }
    public boolean closeRequested() { checkThread(); return window == 0 || GLFW.glfwWindowShouldClose(window); }
    public long presentations() { checkThread(); return presentations; }
    public int[] framebufferSize() {
        checkThread();
        if (window == 0) return new int[]{0, 0};
        try (var stack = MemoryStack.stackPush()) {
            var width = stack.mallocInt(1); var height = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, width, height);
            return new int[]{width.get(0), height.get(0)};
        }
    }
    public void resize(int width, int height) {
        checkThread();
        if (window == 0) throw new IllegalStateException("Preview window closed");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid window dimensions");
        GLFW.glfwSetWindowSize(window, width, height);
    }
    public void requestClose() { checkThread(); if (window != 0) GLFW.glfwSetWindowShouldClose(window, true); }

    /** Consume a borrowed texture synchronously. GPU fences order the two contexts without a CPU glFinish. */
    public void present(FrameTexture frame) { present(frame, null); }
    // Harness inspection happens before swap; backbuffer contents after swap are undefined.
    void present(FrameTexture frame, Runnable inspectBeforeSwap) {
        checkThread(); Objects.requireNonNull(frame);
        if (closeRequested()) return;
        if (GLFW.glfwGetCurrentContext() != parent) throw new IllegalStateException("Source context is not current");
        var size = framebufferSize();
        if (size[0] == 0 || size[1] == 0) return;
        var previous = GL.getCapabilities();
        long ready = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0), consumed = 0;
        GL11.glFlush();
        try {
            GLFW.glfwMakeContextCurrent(window); GL.setCapabilities(capabilities);
            GL32.glWaitSync(ready, 0, GL32.GL_TIMEOUT_IGNORED); GL32.glDeleteSync(ready); ready = 0;
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, frame.textureId(), 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Shared preview texture is not framebuffer-complete");
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0); GL11.glDrawBuffer(GL11.GL_BACK);
            GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glColorMask(true, true, true, true);
            GL11.glClearColor(0, 0, 0, 1); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            double scale = Math.min((double) size[0] / frame.width(), (double) size[1] / frame.height());
            int width = Math.max(1, (int) Math.round(frame.width() * scale));
            int height = Math.max(1, (int) Math.round(frame.height() * scale));
            int x = (size[0] - width) / 2, y = (size[1] - height) / 2;
            GL30.glBlitFramebuffer(0, 0, frame.width(), frame.height(), x, y, x + width, y + height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            if (inspectBeforeSwap != null) inspectBeforeSwap.run();
            presentations++;
        } finally {
            // Even when inspection/drawing fails, source reuse must wait for outstanding reads.
            try {
                if (GLFW.glfwGetCurrentContext() == window) {
                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
                    GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
                    consumed = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0); GL11.glFlush();
                    if (visible && !shown) { GLFW.glfwShowWindow(window); shown = true; }
                    GLFW.glfwSwapBuffers(window);
                }
            } finally {
                GLFW.glfwMakeContextCurrent(parent); GL.setCapabilities(previous);
                if (ready != 0) GL32.glDeleteSync(ready);
                if (consumed != 0) { GL32.glWaitSync(consumed, 0, GL32.GL_TIMEOUT_IGNORED); GL32.glDeleteSync(consumed); }
            }
        }
    }
    @Override public void close() {
        checkThread();
        if (window == 0) return;
        if (GLFW.glfwGetCurrentContext() != parent) throw new IllegalStateException("Source context is not current");
        long previousWindow = GLFW.glfwGetCurrentContext();
        var previous = GL.getCapabilities();
        try {
            GLFW.glfwMakeContextCurrent(window); GL.setCapabilities(capabilities);
            GL30.glDeleteFramebuffers(readFramebuffer);
        } finally {
            GLFW.glfwMakeContextCurrent(previousWindow);
            GL.setCapabilities(previous);
            GLFW.glfwDestroyWindow(window); window = 0;
        }
    }
}
