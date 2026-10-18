package lib.kasuga.rendering.models.uml.backend.gpu;

import lib.kasuga.rendering.models.uml.backend.gpu.buf.GpuBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.fbo.FrameBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.ssbo.SSBOBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.tbo.TBOBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.ubo.UBOBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.vbo.VBOBuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Supplier;

public class GpuHelper {

    public static boolean isSSBOSupported() {
        GLCapabilities caps = GL.getCapabilities();
        return caps.OpenGL43;
    }

    public static boolean isGLEnabled() {
        try {
            GL.getCapabilities();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static GpuBuffer createGpuBuffer(ByteBuffer buffer, BufferType type, int usage,
                                            @Nullable Supplier<Integer> textureUnitSupplier) {
        return switch (type) {
            case SSBO -> new SSBOBuffer(buffer, usage);
            case TBO  -> {
                Objects.requireNonNull(textureUnitSupplier, "Texture unit supplier must be provided for TBO buffers.");
                yield new TBOBuffer(textureUnitSupplier, buffer, usage);
            }
            case VBO -> new VBOBuffer(buffer, usage);
            case UBO -> new UBOBuffer(buffer, usage);
        };
    }

    public static FrameBuffer.Builder createFrameBuffer(int width, int height) {
        return new FrameBuffer.Builder(width, height);
    }
}
