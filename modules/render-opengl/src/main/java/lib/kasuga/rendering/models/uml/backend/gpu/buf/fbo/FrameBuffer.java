package lib.kasuga.rendering.models.uml.backend.gpu.buf.fbo;

import lib.kasuga.rendering.models.uml.backend.gpu.buf.GpuBuffer;
import lombok.Getter;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class FrameBuffer extends GpuBuffer<FrameBuffer> {

    public static final String
            TYPE = "FrameBuffer",
            CLOSE_MSG = "Cannot operate on a closed FrameBuffer.";

    @Getter
    private int fboId, width, height;

    private final int[] colorAttachments;

    private final int[] internalFormats;

    private final int[] formats;

    private final int[] types;

    @Getter
    private int depthAttachment;

    @Getter
    private final boolean hasDepth;

    public FrameBuffer(int width, int height, int numColorAttach, int[] formats, int[] internalFormats, int[] type, boolean hasDepth) {
        super(0, 0, TYPE, new FrameBufferContext());
        this.width = width;
        this.height = height;
        if (formats.length != numColorAttach || internalFormats.length != numColorAttach || type.length != numColorAttach) {
            throw new IllegalArgumentException("Length of formats, internalFormats, and types arrays must match numColorAttach.");
        }
        this.formats = formats;
        this.internalFormats = internalFormats;
        this.types = type;
        this.hasDepth = hasDepth;

        this.fboId = GL30.glGenFramebuffers();
        this.depthAttachment = -1;
        bind(0);

        colorAttachments = new int[numColorAttach];
        for (int i = 0; i < numColorAttach; i++) {
            colorAttachments[i] = createColorTexture(width, height, i);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0 + i,
                    GL11.GL_TEXTURE_2D,
                    colorAttachments[i], 0);
        }

        if (hasDepth) {
            depthAttachment = createDepthRendererBuffer(width, height);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT,
                    GL30.GL_RENDERBUFFER,
                    depthAttachment);
        }

        setDrawBuffers();
        checkComplete();

        unbind();
    }

    public void resize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height) return;
        this.width = newWidth;
        this.height = newHeight;

        bind(0);
        for (int i = 0; i < colorAttachments.length; i++) {
            if (colorAttachments[i] != 0) GL11.glDeleteTextures(colorAttachments[i]);
            colorAttachments[i] = createColorTexture(newWidth, newHeight, i);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0 + i,
                    GL11.GL_TEXTURE_2D,
                    colorAttachments[i], 0);
        }

        if (hasDepth && depthAttachment >= 0) {
            GL30.glDeleteRenderbuffers(depthAttachment);
        }
        depthAttachment = createDepthRendererBuffer(newWidth, newHeight);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_RENDERBUFFER,
                depthAttachment);

        setDrawBuffers();
        checkComplete();
        unbind();
    }

    protected int createColorTexture(int w, int h, int index) {
        int texId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormats[index], w, h, 0, formats[index], types[index], (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        return texId;
    }

    protected int createDepthRendererBuffer(int w, int h) {
        int rboId = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rboId);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, w, h);
        return rboId;
    }

    protected void setDrawBuffers() {
        GL30.glDrawBuffers(colorAttachments);
    }

    protected void checkComplete() {
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Framebuffer is incomplete: " + status);
        }
    }

    @Override
    public void bind(int index) {
        checkOpen(CLOSE_MSG);
        context.enter(this);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
    }

    @Override
    public void unbind() {
        checkOpen(CLOSE_MSG);
        context.exit(this);
    }

    public int getColorTextureId(int index) {
        if (index < 0 || index >= colorAttachments.length) {
            throw new IllegalArgumentException("Invalid color attachment index.");
        }
        return colorAttachments[index];
    }

    public int getColorTextureCount() {
        return colorAttachments.length;
    }

    @Override
    @Deprecated
    public int getUsage() {
        throw new UnsupportedOperationException("FrameBuffer does not have a usage pattern.");
    }

    @Override
    @Deprecated
    public long getSize() {
        throw new UnsupportedOperationException("FrameBuffer does not have a defined size.");
    }

    @Override
    @Deprecated
    public void updateAll(ByteBuffer data) {
        throw new UnsupportedOperationException("FrameBuffer does not support data updates.");
    }

    @Override
    @Deprecated
    public void updateRange(ByteBuffer data, long offset) {
        throw new UnsupportedOperationException("FrameBuffer does not support data updates.");
    }

    @Override
    public void resize(long newSize, boolean copyDataToNewBuffer) {
        throw new UnsupportedOperationException("FrameBuffer does not support resizing by size. Use resize(int newWidth, int newHeight) instead.");
    }

    public static class Builder {

        private final int width, height;

        private final List<Integer> formats, internalFormats, textureTypes;

        private boolean hasDepth;

        public Builder(int width, int height) {
            this.hasDepth = false;
            this.width = width;
            this.height = height;
            this.formats = new ArrayList<>();
            this.internalFormats = new ArrayList<>();
            this.textureTypes = new ArrayList<>();
        }

        public Builder addColorAttachment(int format, int internalFormat, int textureType) {
            formats.add(format);
            internalFormats.add(internalFormat);
            textureTypes.add(textureType);
            return this;
        }

        public Builder setHasDepth(boolean hasDepth) {
            this.hasDepth = hasDepth;
            return this;
        }

        public FrameBuffer build() {
            if (formats.isEmpty()) {
                throw new IllegalStateException("At least one color attachment must be added.");
            }

            int numColorAttach = formats.size();
            int[] formatArray = formats.stream().mapToInt(Integer::intValue).toArray();
            int[] internalFormatArray = internalFormats.stream().mapToInt(Integer::intValue).toArray();
            int[] textureTypeArray = textureTypes.stream().mapToInt(Integer::intValue).toArray();

            formats.clear();
            internalFormats.clear();
            textureTypes.clear();

            return new FrameBuffer(width, height, numColorAttach, formatArray, internalFormatArray, textureTypeArray, hasDepth);
        }
    }
}
