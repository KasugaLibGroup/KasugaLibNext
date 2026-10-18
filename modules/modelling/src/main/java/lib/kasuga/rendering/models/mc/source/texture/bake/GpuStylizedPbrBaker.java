package lib.kasuga.rendering.models.mc.source.texture.bake;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;

/** Render-thread, off-screen implementation of the stylized PBR baker. */
public final class GpuStylizedPbrBaker implements PbrBaker, AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TRACE_PROPERTY = "kasuga.pbr.traceGpuBake";
    private static final int UPLOAD_STRIPE_ROWS = Math.max(0, Integer.getInteger(
            "kasuga.pbr.gpuUploadStripeRows", Minecraft.ON_OSX ? 256 : 0
    ));
    private static final String VERTEX_SHADER_RESOURCE =
            "/assets/kasuga_lib/shaders/pbr/stylized_bake.vsh";
    private static final String FRAGMENT_SHADER_RESOURCE =
            "/assets/kasuga_lib/shaders/pbr/stylized_bake.fsh";

    private int program;
    private int vertexArray;

    @Override
    public PbrBakeResult bake(BufferedImage source, PbrBakeProfile profile) {
        RenderSystem.assertOnRenderThread();
        ensureProgram();

        int width = source.getWidth();
        int height = source.getHeight();
        int maxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        if (width <= 0 || height <= 0 || width > maxTextureSize || height > maxTextureSize) {
            throw new IllegalArgumentException("PBR texture dimensions " + width + "x" + height
                    + " exceed the current OpenGL limit " + maxTextureSize);
        }
        int framebuffer = 0;
        int sourceTexture = 0;
        int normalTexture = 0;
        int specularTexture = 0;
        ByteBuffer sourcePixels = null;
        ByteBuffer outputPixels = null;

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int previousPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int previousUnpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int previousPackRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int previousPackSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int previousPackSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int previousUnpackRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int previousUnpackSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        int previousUnpackSkipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        int previousPixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousPixelUnpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);

        if (previousPixelPackBuffer != 0 || previousPixelUnpackBuffer != 0) {
            LOGGER.warn("Isolating inherited pixel-buffer bindings for a {}x{} PBR bake: pack={}, unpack={}",
                    width, height, previousPixelPackBuffer, previousPixelUnpackBuffer);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            // Client-memory pointers passed to glTexImage2D/glReadPixels are
            // interpreted as byte offsets whenever a pixel buffer is bound.
            // Resource reloads may run between other render systems, so never
            // inherit their PBO state.
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            resetPixelStoreLayout();
            int previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            try {
                sourcePixels = imageToRgba(source);
                long expectedBytes = requiredBytes(width, height);
                if (sourcePixels.remaining() != expectedBytes) {
                    throw new IllegalStateException("PBR upload buffer has " + sourcePixels.remaining()
                            + " bytes, expected " + expectedBytes);
                }
                if (Boolean.getBoolean(TRACE_PROPERTY)) {
                    LOGGER.info("[pbr-gpu-trace] upload size={}x{} bytes={} address=0x{} "
                                    + "maxTextureSize={} stripeRows={} packPbo={} unpackPbo={} "
                                    + "packLayout={}/{}/{} unpackLayout={}/{}/{} "
                                    + "activeTexture={} texture2D={} framebuffer={}",
                            width, height, sourcePixels.remaining(),
                            Long.toUnsignedString(MemoryUtil.memAddress(sourcePixels), 16),
                            maxTextureSize, UPLOAD_STRIPE_ROWS,
                            previousPixelPackBuffer, previousPixelUnpackBuffer,
                            previousPackRowLength, previousPackSkipRows, previousPackSkipPixels,
                            previousUnpackRowLength, previousUnpackSkipRows, previousUnpackSkipPixels,
                            previousActiveTexture, previousTexture0, previousFramebuffer);
                }
                sourceTexture = createTexture(width, height, sourcePixels, true);
                normalTexture = createTexture(width, height, null, false);
                specularTexture = createTexture(width, height, null, false);

                framebuffer = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, normalTexture, 0);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1,
                        GL11.GL_TEXTURE_2D, specularTexture, 0);
                GL20.glDrawBuffers(stack.ints(GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1));
                int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
                if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("PBR bake framebuffer is incomplete: " + status);
                }

                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glViewport(0, 0, width, height);
                GL20.glUseProgram(program);
                GL30.glBindVertexArray(vertexArray);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, sourceTexture);

                GL20.glUniform1i(GL20.glGetUniformLocation(program, "sourceTexture"), 0);
                GL20.glUniform1f(GL20.glGetUniformLocation(program, "smoothness"), profile.smoothness());
                GL20.glUniform1f(GL20.glGetUniformLocation(program, "f0Code"), profile.f0Code() / 255.0f);
                float sssCode = profile.sssStrength() <= 0.0f
                        ? 0.0f
                        : Math.clamp((65.0f + profile.sssStrength() * 190.0f) / 255.0f, 65.0f / 255.0f, 1.0f);
                GL20.glUniform1f(GL20.glGetUniformLocation(program, "sssCode"), sssCode);
                GL20.glUniform1f(GL20.glGetUniformLocation(program, "normalStrength"), profile.normalStrength());
                GL20.glUniform1f(GL20.glGetUniformLocation(program, "emission"), profile.emissionStrength());
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                outputPixels = MemoryUtil.memAlloc(width * height * 4);
                BufferedImage normal = readAttachment(GL30.GL_COLOR_ATTACHMENT0, width, height, outputPixels);
                BufferedImage specular = readAttachment(GL30.GL_COLOR_ATTACHMENT1, width, height, outputPixels);
                return new PbrBakeResult(normal, specular);
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture0);
                GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            }
        } finally {
            if (outputPixels != null) MemoryUtil.memFree(outputPixels);
            if (sourcePixels != null) MemoryUtil.memFree(sourcePixels);
            if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
            if (sourceTexture != 0) GL11.glDeleteTextures(sourceTexture);
            if (normalTexture != 0) GL11.glDeleteTextures(normalTexture);
            if (specularTexture != 0) GL11.glDeleteTextures(specularTexture);
            GL20.glUseProgram(previousProgram);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL30.glBindVertexArray(previousVertexArray);
            GL13.glActiveTexture(previousActiveTexture);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousPackAlignment);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, previousUnpackAlignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, previousPackRowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, previousPackSkipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, previousPackSkipPixels);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, previousUnpackRowLength);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, previousUnpackSkipRows);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, previousUnpackSkipPixels);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, previousPixelUnpackBuffer);
            restoreEnabled(GL11.GL_BLEND, blend);
            restoreEnabled(GL11.GL_DEPTH_TEST, depth);
            restoreEnabled(GL11.GL_CULL_FACE, cull);
            restoreEnabled(GL11.GL_SCISSOR_TEST, scissor);
        }
    }

    private void ensureProgram() {
        if (program != 0) return;
        int vertex = 0;
        int fragment = 0;
        try {
            vertex = compile(GL20.GL_VERTEX_SHADER, loadShader(VERTEX_SHADER_RESOURCE));
            fragment = compile(GL20.GL_FRAGMENT_SHADER, loadShader(FRAGMENT_SHADER_RESOURCE));
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL30.glBindFragDataLocation(program, 0, "normalOut");
            GL30.glBindFragDataLocation(program, 1, "specularOut");
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Failed to link PBR bake shader: " + GL20.glGetProgramInfoLog(program));
            }
            vertexArray = GL30.glGenVertexArrays();
        } catch (RuntimeException exception) {
            if (program != 0) GL20.glDeleteProgram(program);
            program = 0;
            throw exception;
        } finally {
            if (vertex != 0) GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    private static String loadShader(String resourcePath) {
        try (InputStream stream = GpuStylizedPbrBaker.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing PBR shader resource: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read PBR shader resource: " + resourcePath, exception);
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile PBR bake shader: " + log);
        }
        return shader;
    }

    private static int createTexture(int width, int height, ByteBuffer pixels, boolean source) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        if (pixels != null && UPLOAD_STRIPE_ROWS > 0) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            int bytesPerRow = Math.toIntExact(requiredBytes(width, 1));
            for (int y = 0; y < height; y += UPLOAD_STRIPE_ROWS) {
                int rows = Math.min(UPLOAD_STRIPE_ROWS, height - y);
                int offset = Math.multiplyExact(y, bytesPerRow);
                int byteCount = Math.multiplyExact(rows, bytesPerRow);
                ByteBuffer stripe = pixels.slice(offset, byteCount);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, y, width, rows,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, stripe);
            }
        } else {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, source ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, source ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE);
        return texture;
    }

    private static ByteBuffer imageToRgba(BufferedImage image) {
        long byteCount = requiredBytes(image.getWidth(), image.getHeight());
        if (byteCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("PBR source is too large for a direct upload: "
                    + image.getWidth() + "x" + image.getHeight());
        }
        ByteBuffer pixels = MemoryUtil.memAlloc((int) byteCount);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                pixels.put((byte) (argb >>> 16));
                pixels.put((byte) (argb >>> 8));
                pixels.put((byte) argb);
                pixels.put((byte) (argb >>> 24));
            }
        }
        return pixels.flip();
    }

    private static long requiredBytes(int width, int height) {
        return Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
    }

    private static void resetPixelStoreLayout() {
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
    }

    private static BufferedImage readAttachment(int attachment, int width, int height, ByteBuffer pixels) {
        pixels.clear();
        GL11.glReadBuffer(attachment);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = (y * width + x) * 4;
                int r = pixels.get(index) & 0xff;
                int g = pixels.get(index + 1) & 0xff;
                int b = pixels.get(index + 2) & 0xff;
                int a = pixels.get(index + 3) & 0xff;
                image.setRGB(x, y, a << 24 | r << 16 | g << 8 | b);
            }
        }
        return image;
    }

    private static void restoreEnabled(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (program != 0) GL20.glDeleteProgram(program);
        if (vertexArray != 0) GL30.glDeleteVertexArrays(vertexArray);
        program = 0;
        vertexArray = 0;
    }
}
