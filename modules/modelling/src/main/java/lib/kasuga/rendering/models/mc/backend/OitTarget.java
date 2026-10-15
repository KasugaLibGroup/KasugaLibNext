package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * The bounded intermediate storage used by weighted-blended OIT.
 *
 * <p>The two color attachments are rendered in separate passes.  That keeps
 * the implementation on the OpenGL 3.0 feature set available to the
 * Minecraft 1.21.1 client and avoids requiring per-attachment blend state
 * from OpenGL 4.0.</p>
 */
final class OitTarget implements AutoCloseable {

    private int framebufferId;
    private int accumulationTextureId;
    private int revealageTextureId;
    private int depthTextureId;
    private int width;
    private int height;
    private OitDepthFormat depthFormat;

    private void ensureSize(int targetWidth, int targetHeight, OitDepthFormat targetDepthFormat) {
        RenderSystem.assertOnRenderThread();
        if (targetWidth <= 0 || targetHeight <= 0) {
            throw new IllegalArgumentException("OIT target dimensions must be positive");
        }
        if (targetWidth == width && targetHeight == height && framebufferId != 0
                && targetDepthFormat.equals(depthFormat)) {
            return;
        }

        destroyBuffers();
        width = targetWidth;
        height = targetHeight;
        depthFormat = targetDepthFormat;

        framebufferId = GL30.glGenFramebuffers();
        accumulationTextureId = createColorTexture(GL30.GL_RGBA16F);
        revealageTextureId = createColorTexture(GL30.GL_R16F, GL11.GL_RED);
        depthTextureId = createDepthTexture();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, accumulationTextureId, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1,
                GL11.GL_TEXTURE_2D, revealageTextureId, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTextureId, 0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glDrawBuffers(stack.ints(GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1));
        }
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            destroyBuffers();
            throw new IllegalStateException("OIT framebuffer is incomplete: " + status);
        }

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    void prepare(RenderTarget mainTarget) {
        prepare(mainTarget.frameBufferId, mainTarget.width, mainTarget.height);
    }

    void prepare(int sourceFramebufferId, int sourceWidth, int sourceHeight) {
        RenderSystem.assertOnRenderThread();
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFramebufferId);
        // Query the actual attachment, not a guessed format: on Apple GL an
        // unsized Minecraft depth texture is float32, while DEPTH_COMPONENT24
        // becomes fixed32. Blitting between them silently fails without an
        // explicit glGetError check, leaving OIT with unrelated scene depth.
        int bits = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE);
        int stencil = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE);
        ensureSize(sourceWidth, sourceHeight, OitDepthFormat.matching(bits, type, stencil));
        copyDepth(sourceFramebufferId, sourceWidth, sourceHeight);
        clearColorAttachments();
    }

    void bindAccumulation() {
        bindColorAttachment(GL30.GL_COLOR_ATTACHMENT0);
    }

    void bindRevealage() {
        bindColorAttachment(GL30.GL_COLOR_ATTACHMENT1);
    }

    int accumulationTextureId() {
        return accumulationTextureId;
    }

    int revealageTextureId() {
        return revealageTextureId;
    }

    int depthTextureId() {
        return depthTextureId;
    }

    private void copyDepth(int sourceFramebufferId, int sourceWidth, int sourceHeight) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFramebufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebufferId);
        GlStateManager._glBlitFrameBuffer(
                0, 0, sourceWidth, sourceHeight,
                0, 0, width, height,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST
        );
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            throw new IllegalStateException("OIT scene depth copy failed (GL error 0x"
                    + Integer.toHexString(error) + ", depth format " + depthFormat + ")");
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
    }

    private void clearColorAttachments() {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Clear one draw buffer at a time.  Passing draw-buffer index 1 to
            // glClearBufferfv is legal after glDrawBuffers, but it is not
            // reliable on every OpenGL 3.x implementation (notably Apple's
            // compatibility profile when the second attachment is R16F).
            // Binding each attachment as draw buffer 0 makes the clear
            // unambiguous and keeps revealage at one outside transparent
            // geometry.
            GL30.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, stack.floats(0f, 0f, 0f, 0f));
            GL30.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT1);
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, stack.floats(1f, 0f, 0f, 0f));
        }
    }

    private void bindColorAttachment(int attachment) {
        RenderSystem.assertOnRenderThread();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferId);
        GL30.glDrawBuffer(attachment);
        GlStateManager._viewport(0, 0, width, height);
    }

    private int createColorTexture(int internalFormat) {
        return createColorTexture(internalFormat, GL11.GL_RGBA);
    }

    private int createColorTexture(int internalFormat, int format) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height,
                0, format, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    private int createDepthTexture() {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12_COMPARE_MODE, GL11.GL_NONE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, depthFormat.internalFormat(), width, height,
                0, depthFormat.pixelFormat(), depthFormat.pixelType(), (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return texture;
    }

    private void destroyBuffers() {
        if (framebufferId != 0) {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
        if (accumulationTextureId != 0) GL11.glDeleteTextures(accumulationTextureId);
        if (revealageTextureId != 0) GL11.glDeleteTextures(revealageTextureId);
        if (depthTextureId != 0) GL11.glDeleteTextures(depthTextureId);
        if (framebufferId != 0) GL30.glDeleteFramebuffers(framebufferId);
        framebufferId = accumulationTextureId = revealageTextureId = depthTextureId = 0;
        width = height = 0;
        depthFormat = null;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        destroyBuffers();
    }

    // Kept local so this class does not depend on the GL12 facade for two
    // constants that are also accepted by GL11.glTexParameteri.
    private static final int GL12_CLAMP_TO_EDGE = 0x812F;
    private static final int GL12_COMPARE_MODE = 0x884C;
}
