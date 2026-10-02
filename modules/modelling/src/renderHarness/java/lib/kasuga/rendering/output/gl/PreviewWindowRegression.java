package lib.kasuga.rendering.output.gl;

import lib.kasuga.rendering.output.FrameTexture;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Actual shared-context reads, cross-context fences and restoration, with two native windows. */
public final class PreviewWindowRegression {
    public static void run() {
        long parent = GLFW.glfwGetCurrentContext();
        var capabilities = GL.getCapabilities();
        int texture = GL11.glGenTextures(), source = GL30.glGenFramebuffers();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 16, 16, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, source);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glViewport(3, 4, 5, 6);
        var frame = new FrameTexture(source, texture, 16, 16);
        try (var first = new FramePreviewWindow(parent, new FramePreviewWindow.Options("Preview test A", 64, 64, false), 3, 2);
             var second = new FramePreviewWindow(parent, new FramePreviewWindow.Options("Preview test B", 80, 48, false), 3, 2)) {
            for (int index = 0; index < 20; index++) {
                boolean red = index % 2 == 0;
                GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glColorMask(true, true, true, true);
                GL11.glClearColor(red ? 1 : 0, red ? 0 : 1, 0, 1); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                first.present(frame, () -> inspect(first, red));
                second.present(frame, () -> inspect(second, red));
                if (GLFW.glfwGetCurrentContext() != parent || GL.getCapabilities() != capabilities)
                    throw new AssertionError("Parent context/capabilities not restored");
                if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != source
                        || GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) != source)
                    throw new AssertionError("Parent framebuffer bindings changed");
                int[] viewport = new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
                if (viewport[0] != 3 || viewport[1] != 4 || viewport[2] != 5 || viewport[3] != 6)
                    throw new AssertionError("Parent viewport changed");
            }
            first.resize(72, 64); GLFW.glfwPollEvents();
            first.present(frame, () -> inspect(first, false));
            try {
                first.present(frame, () -> { throw new IllegalStateException("Intentional preview failure"); });
                throw new AssertionError("Inspection failure swallowed");
            } catch (IllegalStateException expected) {
                if (GLFW.glfwGetCurrentContext() != parent || GL.getCapabilities() != capabilities)
                    throw new AssertionError("Exception left secondary context current");
            }
            first.requestClose(); first.close(); first.close();
            if (!first.isClosed()) throw new AssertionError("Window did not close");
            second.present(frame, () -> inspect(second, false));
            if (!GL11.glIsTexture(texture)) throw new AssertionError("Preview deleted borrowed texture");
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glDeleteFramebuffers(source); GL11.glDeleteTextures(texture);
        }
        if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("Shared preview GL error");
    }
    private static void inspect(FramePreviewWindow window, boolean red) {
        var size = window.framebufferSize();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0); GL11.glReadBuffer(GL11.GL_BACK);
        try (var stack = MemoryStack.stackPush()) {
            var pixel = stack.malloc(4);
            GL11.glReadPixels(size[0] / 2, size[1] / 2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            if (Byte.toUnsignedInt(pixel.get(red ? 0 : 1)) != 255 || Byte.toUnsignedInt(pixel.get(red ? 1 : 0)) != 0)
                throw new AssertionError("Shared window pixels are stale or wrong");
        }
        if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("Preview context GL error");
    }
}
