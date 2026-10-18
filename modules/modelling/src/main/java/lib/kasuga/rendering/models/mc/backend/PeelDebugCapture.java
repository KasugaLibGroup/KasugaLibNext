package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.io.*;
import java.nio.FloatBuffer;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

/** Opt-in, one-frame readback. Never used by the normal performance path. */
final class PeelDebugCapture {
    private final Path directory;
    private final StringBuilder report = new StringBuilder();
    private float[] sceneDepth;

    private PeelDebugCapture(Path directory) { this.directory = directory; }

    static PeelDebugCapture create() {
        try {
            Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/transparency")
                    .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
            Files.createDirectories(directory);
            LogUtils.getLogger().info("Capturing transparency intermediates to {}", directory.toAbsolutePath());
            return new PeelDebugCapture(directory);
        } catch (IOException failure) {
            LogUtils.getLogger().warn("Cannot create transparency capture", failure);
            return null;
        }
    }

    void target(String name, PeelTarget target) {
        capture(name, target.framebuffer, target.width, target.height,
                target.color != 0, target.depth != 0, target.isRedMask());
    }

    void capture(String name, int framebuffer, int width, int height, boolean color, boolean depth) {
        capture(name, framebuffer, width, height, color, depth, false);
    }

    private void capture(String name, int framebuffer, int width, int height,
                         boolean color, boolean depth, boolean redMask) {
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int row = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            if (color) readColor(name, width, height, redMask);
            if (depth) readDepth(name, width, height);
        } catch (Exception failure) {
            LogUtils.getLogger().warn("Transparency capture failed for {}", name, failure);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, row);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
        }
    }

    private void readColor(String name, int width, int height, boolean redMask) throws IOException {
        FloatBuffer buffer = MemoryUtil.memAllocFloat(width * height * 4);
        int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        try (NativeImage rgb = new NativeImage(width, height, false);
             NativeImage alpha = new NativeImage(width, height, false)) {
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_FLOAT, buffer);
            int occupied = 0;
            float maxAlpha = 0;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                // R8 has an implicit alpha of one even at uncovered pixels.
                float a = buffer.get(i + (redMask ? 0 : 3));
                if (a > 0) occupied++;
                maxAlpha = Math.max(maxAlpha, a);
                rgb.setPixelRGBA(x, height - 1 - y, abgr(buffer.get(i), buffer.get(i + 1), buffer.get(i + 2)));
                alpha.setPixelRGBA(x, height - 1 - y, abgr(a, a, a));
            }
            rgb.writeToFile(directory.resolve(name + "-rgb.png"));
            alpha.writeToFile(directory.resolve(name + "-alpha.png"));
            report.append(name).append(": occupied=").append(occupied).append(" maxAlpha=").append(maxAlpha).append('\n');
        } finally {
            GL11.glReadBuffer(readBuffer);
            MemoryUtil.memFree(buffer);
        }
    }

    private void readDepth(String name, int width, int height) throws IOException {
        FloatBuffer buffer = MemoryUtil.memAllocFloat(width * height);
        try {
            GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buffer);
            float[] values = new float[width * height];
            buffer.get(values);
            if (name.equals("scene")) sceneDepth = values;
            int occupied = 0, behindScene = 0;
            float min = 1, max = 0;
            try (DataOutputStream raw = new DataOutputStream(new GZIPOutputStream(
                    Files.newOutputStream(directory.resolve(name + "-depth.f32.gz"))))) {
                raw.writeInt(width); raw.writeInt(height); // Big-endian floats, bottom row first.
                for (int i = 0; i < values.length; i++) {
                    float z = values[i];
                    raw.writeFloat(z);
                    if (z >= 1) continue;
                    occupied++; min = Math.min(min, z); max = Math.max(max, z);
                    if (sceneDepth != null && z > sceneDepth[i]) behindScene++;
                }
            }
            report.append(name).append(": depthPixels=").append(occupied).append(" min=").append(min)
                    .append(" max=").append(max).append(" behindScene=").append(behindScene).append('\n');
        } finally { MemoryUtil.memFree(buffer); }
    }

    void finish() {
        try { Files.writeString(directory.resolve("report.txt"), report); }
        catch (IOException failure) { LogUtils.getLogger().warn("Cannot save transparency report", failure); }
        LogUtils.getLogger().info("Transparency capture complete: {}\n{}", directory.toAbsolutePath(), report);
        if (Boolean.getBoolean("kasuga.debugTransparency.stopAfterCapture")) Minecraft.getInstance().stop();
    }

    private static int abgr(float r, float g, float b) {
        return 0xff000000 | channel(b) << 16 | channel(g) << 8 | channel(r);
    }
    private static int channel(float value) { return Math.clamp(Math.round(value * 255), 0, 255); }
}
