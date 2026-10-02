package lib.kasuga.rendering.output.gl;

import lib.kasuga.rendering.output.FrameTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** Synchronous readback on the current GL/render thread; returned top-left RGBA bytes belong to the caller. */
public final class RgbaReadback {
    private RgbaReadback() {}
    public static byte[] copy(FrameTexture frame) {
        int stride = Math.multiplyExact(frame.width(), 4);
        int length = Math.multiplyExact(stride, frame.height());
        if (frame.width() <= 0 || frame.height() <= 0) throw new IllegalArgumentException("Invalid frame size");
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int rowLength = GL11.glGetInteger(GL12.GL_PACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL12.GL_PACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL12.GL_PACK_SKIP_PIXELS);
        var pixels = MemoryUtil.memAlloc(length);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, frame.framebufferId());
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL12.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL12.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL12.GL_PACK_SKIP_PIXELS, 0);
            GL11.glReadPixels(0, 0, frame.width(), frame.height(), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            byte[] result = new byte[length];
            for (int row = 0; row < frame.height(); row++)
                pixels.get((frame.height() - 1 - row) * stride, result, row * stride, stride);
            return result;
        } finally {
            MemoryUtil.memFree(pixels);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL12.GL_PACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL12.GL_PACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL12.GL_PACK_SKIP_PIXELS, skipPixels);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL11.glReadBuffer(readBuffer);
        }
    }
}
