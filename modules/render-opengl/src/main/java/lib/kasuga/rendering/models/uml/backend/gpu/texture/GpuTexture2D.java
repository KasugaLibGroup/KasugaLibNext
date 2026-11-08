package lib.kasuga.rendering.models.uml.backend.gpu.texture;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** Render-thread-owned 2D texture with Kasuga's shared upload and cleanup policy. */
public final class GpuTexture2D implements AutoCloseable {
    private int id;

    private GpuTexture2D(int id) { this.id = id; }

    public static GpuTexture2D rgba8(int width, int height, int wrap, ByteBuffer pixels) {
        requireSize(width, height);
        return allocate(width, height, wrap, GL11.GL_RGBA8, GL11.GL_UNSIGNED_BYTE, pixels, false);
    }

    public static GpuTexture2D rgba32f(int width, int height, ByteBuffer pixels) {
        return allocate(width, height, GL12.GL_CLAMP_TO_EDGE, GL30.GL_RGBA32F, GL11.GL_FLOAT, pixels, false);
    }

    private static GpuTexture2D allocate(int width, int height, int wrap, int internalFormat,
                                         int pixelType, ByteBuffer pixels, boolean mipmaps) {
        requireSize(width, height);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int id = GL11.glGenTextures();
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            configure(wrap, pixelType == GL11.GL_FLOAT ? GL11.GL_NEAREST : GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            if (!mipmaps) GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
                    GL11.GL_RGBA, pixelType, pixels);
            if (mipmaps) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            }
            return new GpuTexture2D(id);
        } catch (RuntimeException | Error failure) {
            GL11.glDeleteTextures(id);
            throw failure;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    public static GpuTexture2D png(InputStream input, boolean srgb) throws IOException {
        BufferedImage image = ImageIO.read(input);
        if (image == null) throw new IOException("Unsupported image");
        ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(image.getWidth() * image.getHeight() * 4);
        for (int y = image.getHeight() - 1; y >= 0; y--) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgba = image.getRGB(x, y);
                pixels.put((byte) (rgba >>> 16)).put((byte) (rgba >>> 8))
                        .put((byte) rgba).put((byte) (rgba >>> 24));
            }
        }
        pixels.flip();
        return allocate(image.getWidth(), image.getHeight(), srgb ? GL14.GL_MIRRORED_REPEAT : GL11.GL_REPEAT,
                srgb ? GL30.GL_SRGB8_ALPHA8 : GL11.GL_RGBA8, GL11.GL_UNSIGNED_BYTE, pixels, true);
    }

    public int id() {
        if (id == 0) throw new IllegalStateException("Texture is closed");
        return id;
    }

    public boolean isOpen() { return id != 0; }

    public static void updateRgba32f(int textureId, int width, int height, ByteBuffer pixels) {
        requireSize(width, height);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    private static void requireSize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Texture dimensions must be positive");
    }

    private static void configure(int wrap, int filter) {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
    }

    @Override
    public void close() {
        if (id != 0) {
            GL11.glDeleteTextures(id);
            id = 0;
        }
    }
}
