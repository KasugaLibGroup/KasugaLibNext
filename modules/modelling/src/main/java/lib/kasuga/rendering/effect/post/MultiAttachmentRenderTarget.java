package lib.kasuga.rendering.effect.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.fbo.FrameBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/** Minecraft target view over Kasuga's owned MRT framebuffer. The pool owns its lifetime. */
public final class MultiAttachmentRenderTarget extends RenderTarget {
    private final PostProcessTargetDescriptor descriptor;
    private FrameBuffer framebuffer;

    MultiAttachmentRenderTarget(PostProcessTargetDescriptor descriptor, int width, int height) {
        super(descriptor.useDepth());
        this.descriptor = descriptor;
        createBuffers(width, height, false);
    }

    @Override
    public void createBuffers(int width, int height, boolean checkError) {
        RenderSystem.assertOnRenderThreadOrInit();
        if (framebuffer != null) destroyBuffers();
        var builder = new FrameBuffer.Builder(width, height).setDepthTexture(useDepth);
        for (var format : descriptor.colorAttachments()) {
            builder.addColorAttachment(GL11.GL_RGBA, format.internalFormat(), format.pixelType());
        }
        framebuffer = builder.build();
        this.width = this.viewWidth = width;
        this.height = this.viewHeight = height;
        frameBufferId = framebuffer.getFboId();
        colorTextureId = framebuffer.getColorTextureId(0);
        depthBufferId = useDepth ? framebuffer.getDepthAttachment() : -1;
        setFilterMode(descriptor.filter().glConstant());
    }

    public int colorTexture(int attachment) {
        if (framebuffer == null) throw new IllegalStateException("Target is closed");
        return framebuffer.getColorTextureId(attachment);
    }

    public int colorAttachmentCount() { return descriptor.colorAttachments().size(); }

    @Override
    public void setFilterMode(int filter) {
        RenderSystem.assertOnRenderThreadOrInit();
        filterMode = filter;
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            for (int i = 0; i < colorAttachmentCount(); i++) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture(i));
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    @Override
    public void destroyBuffers() {
        RenderSystem.assertOnRenderThreadOrInit();
        if (framebuffer != null) {
            try { framebuffer.close(); }
            catch (Exception failure) { throw new IllegalStateException("Cannot close MRT target", failure); }
            framebuffer = null;
        }
        frameBufferId = colorTextureId = depthBufferId = -1;
    }
}
