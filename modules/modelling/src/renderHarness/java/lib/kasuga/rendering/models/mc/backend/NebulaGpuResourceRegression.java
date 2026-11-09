package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.backend.gpu.buf.fbo.FrameBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.texture.GpuTexture2D;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static lib.kasuga.rendering.models.mc.backend.GlFixture.expect;

/** Actual GL readbacks for the reusable MRT and texture APIs consumed by Nebula. */
final class NebulaGpuResourceRegression {
    static void mrtDepthTexture() throws Exception {
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int sentinel = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sentinel);
        try (var fbo = new FrameBuffer.Builder(8, 8)
                .addColorAttachment(GL11.GL_RGBA, GL30.GL_RGBA16F, GL11.GL_FLOAT)
                .addColorAttachment(GL11.GL_RGBA, GL30.GL_RGBA16F, GL11.GL_FLOAT)
                .addColorAttachment(GL11.GL_RGBA, GL30.GL_RGBA16F, GL11.GL_FLOAT)
                .setDepthTexture(true).build(); var stack = MemoryStack.stackPush()) {
            expect(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), sentinel, 0, "MRT allocation restores texture");
            fbo.resize(16, 12);
            expect(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), sentinel, 0, "MRT resize restores texture");
            check(fbo.isDepthTexture(), "MRT depth must be a sampleable texture");
            int depth = fbo.getDepthAttachment();
            check(GL11.glIsTexture(depth), "MRT depth texture exists");
            fbo.bind(0);
            for (int i = 0; i < 3; i++) {
                GL30.glClearBufferfv(GL11.GL_COLOR, i, new float[]{.125f * (i + 1), .5f, .75f, 1f});
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0 + i);
                var pixel = stack.mallocFloat(4);
                GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
                expect(pixel.get(0), .125f * (i + 1), .001f, "MRT independent color " + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, fbo.getColorTextureId(i));
                expect(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT),
                        GL30.GL_RGBA16F, 0, "MRT half-float format");
                expect(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH), 16, 0, "MRT resized width");
            }
            GL30.glClearBufferfv(GL11.GL_DEPTH, 0, new float[]{.375f});
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
            var depths = stack.mallocFloat(16 * 12);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depths);
            expect(depths.get(0), .375f, .00001f, "MRT sampleable depth contents");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sentinel);
            int[] colors = {fbo.getColorTextureId(0), fbo.getColorTextureId(1), fbo.getColorTextureId(2)};
            int id = fbo.getFboId();
            fbo.close();
            check(!GL30.glIsFramebuffer(id) && !GL11.glIsTexture(depth), "MRT framebuffer and depth deleted");
            for (int color : colors) check(!GL11.glIsTexture(color), "MRT color deleted");
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL11.glDeleteTextures(sentinel);
        }
    }

    static void textureBinding() throws Exception {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int sentinel = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sentinel);
        try (var stack = MemoryStack.stackPush()) {
            try (var rgba = GpuTexture2D.rgba8(2, 2, GL12.GL_CLAMP_TO_EDGE, stack.calloc(16))) {
                expect(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), sentinel, 0, "RGBA8 upload restores texture");
                int id = rgba.id();
                rgba.close();
                check(!GL11.glIsTexture(id), "RGBA8 deletion is immediate");
            }
            var values = stack.malloc(16).putFloat(.25f).putFloat(.5f).putFloat(.75f).putFloat(1f).flip();
            try (var data = GpuTexture2D.rgba32f(1, 1, values)) {
                expect(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), sentinel, 0, "RGBA32F upload restores texture");
                values.putFloat(0, .625f);
                GpuTexture2D.updateRgba32f(data.id(), 1, 1, values);
                expect(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), sentinel, 0, "texture update restores binding");
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, data.id());
                var actual = stack.mallocFloat(4);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, actual);
                expect(actual.get(0), .625f, .00001f, "texture update contents");
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, sentinel);
            }
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "PNG", bytes);
            try (var png = GpuTexture2D.png(new ByteArrayInputStream(bytes.toByteArray()), true)) {
                expect(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), sentinel, 0, "PNG upload restores texture");
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, png.id());
                expect(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT),
                        GL30.GL_SRGB8_ALPHA8, 0, "PNG sRGB storage");
                expect(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 1, GL11.GL_TEXTURE_WIDTH), 2, 0, "PNG mipmap generated");
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, sentinel);
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            GL11.glDeleteTextures(sentinel);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
