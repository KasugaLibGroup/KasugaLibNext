package lib.kasuga.rendering.models.uml.backend.gpu;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import lib.kasuga.rendering.models.uml.backend.gpu.upload.macos.MacTboUploadDevice;

/** A texture view per ring buffer avoids changing a view that queued draws still use. */
public final class TextureUploadRing implements AutoCloseable {
    private record View(int texture, int capacity) {}
    private final GpuUploadRing buffers;
    private final Map<Integer, View> views = new HashMap<>();
    private int texture;
    private boolean closed;

    public TextureUploadRing() { this(new GpuUploadRing(3, defaultDevice())); }
    public static GpuUploadRing.Device defaultDevice() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin") ? new MacTboUploadDevice() : new GpuUploadRing.GlDevice();
    }
    public TextureUploadRing(GpuUploadRing buffers) { this.buffers = buffers; }
    public int bufferId() { return buffers.bufferId(); }
    public int textureId() { return texture; }
    public GpuUploadRing.Stats stats() { return buffers.stats(); }
    public String strategy() { return buffers.strategy(); }

    /** Input is RGBA32F; binding state and staging position/limit are preserved. */
    public int upload(FloatBuffer data) {
        if (closed) throw new IllegalStateException("Texture upload ring is closed");
        if (data.remaining() % 4 != 0) throw new IllegalArgumentException("Incomplete RGBA texel");
        int buffer = buffers.upload(data);
        View view = views.get(buffer);
        int capacity = buffers.capacityBytes();
        if (view == null || view.capacity != capacity) {
            int previous = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
            int id = view == null ? GL11.glGenTextures() : view.texture;
            try {
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, id);
                GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer);
                views.put(buffer, new View(id, capacity));
            } catch (RuntimeException | Error error) {
                if (view == null) GL11.glDeleteTextures(id);
                throw error;
            } finally { GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, previous); }
            texture = id;
        } else texture = view.texture;
        return texture;
    }

    public void markSubmitted() { buffers.markSubmitted(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { buffers.close(); }
        finally {
            for (View view : views.values()) GL11.glDeleteTextures(view.texture);
            views.clear(); texture = 0;
        }
    }
}
