package lib.kasuga.rendering.models.mc.backend;

import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;

/** One reusable depth-peeling framebuffer; no global RenderSystem state cache writes. */
final class PeelTarget implements AutoCloseable {
    int framebuffer, color, depth, width, height;
    private OitDepthFormat format;
    private int colorFormat;

    void ensure(int w, int h, boolean withColor, OitDepthFormat depthFormat) {
        ensure(w, h, withColor ? GL30.GL_RGBA16F : 0, depthFormat);
    }

    void ensure(int w, int h, int targetColorFormat, OitDepthFormat depthFormat) {
        if (framebuffer != 0 && width == w && height == h
                && colorFormat == targetColorFormat
                && java.util.Objects.equals(format, depthFormat)) return;
        close();
        width = w;
        height = h;
        format = depthFormat;
        colorFormat = targetColorFormat;
        boolean withColor = colorFormat != 0;
        framebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        if (withColor) {
            color = texture(colorFormat, colorFormat == GL30.GL_R8 ? GL11.GL_RED : GL11.GL_RGBA,
                    colorFormat == GL30.GL_R8 ? GL11.GL_UNSIGNED_BYTE : GL11.GL_FLOAT);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, color, 0);
        }
        if (depthFormat != null) {
            depth = texture(depthFormat.internalFormat(), depthFormat.pixelFormat(), depthFormat.pixelType());
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, depth, 0);
        }
        GL11.glDrawBuffer(withColor ? GL30.GL_COLOR_ATTACHMENT0 : GL11.GL_NONE);
        GL11.glReadBuffer(withColor ? GL30.GL_COLOR_ATTACHMENT0 : GL11.GL_NONE);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            close();
            throw new IllegalStateException("Peel framebuffer incomplete: " + status);
        }
    }

    void bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL11.glViewport(0, 0, width, height);
    }

    boolean isRedMask() {
        return colorFormat == GL30.GL_R8;
    }

    void clear(double clearDepth) {
        bind();
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClearDepth(clearDepth);
        GL11.glClear((color != 0 ? GL11.GL_COLOR_BUFFER_BIT : 0)
                | (depth != 0 ? GL11.GL_DEPTH_BUFFER_BIT : 0));
    }

    static OitDepthFormat sceneFormat(int framebuffer) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
        return OitDepthFormat.matching(
                attachment(GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE),
                attachment(GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE),
                attachment(GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE));
    }

    private static int attachment(int parameter) {
        return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, parameter);
    }

    void copyDepth(int source) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        checkError("scene depth copy");
    }

    static void checkError(String operation) {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            throw new IllegalStateException(operation + ": GL error 0x" + Integer.toHexString(error));
        }
    }

    private int texture(int internal, int pixels, int type) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, width, height, 0, pixels, type, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        return id;
    }

    @Override
    public void close() {
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        if (color != 0) GL11.glDeleteTextures(color);
        if (depth != 0) GL11.glDeleteTextures(depth);
        framebuffer = color = depth = width = height = 0;
        format = null;
        colorFormat = 0;
    }
}
