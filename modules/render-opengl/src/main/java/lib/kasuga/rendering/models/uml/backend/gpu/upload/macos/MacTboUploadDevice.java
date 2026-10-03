package lib.kasuga.rendering.models.uml.backend.gpu.upload.macos;

import lib.kasuga.rendering.models.uml.backend.gpu.GpuUploadRing;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;

/**
 * Independent macOS TBO path. Apple GL incurs costly texture-buffer validation
 * on subsequent draws after mapped/subdata updates; complete orphaned snapshots
 * retain its efficient upload behavior. VBOs keep the common fenced path.
 */
public final class MacTboUploadDevice extends GpuUploadRing.GlDevice {
    @Override public boolean orphanEveryUpload() { return true; }
    @Override public String strategy() { return "macos-tbo-orphan"; }
    @Override public void uploadOrphaned(int buffer, ByteBuffer snapshot) {
        int previous = GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);
        try {
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
            if (snapshot.hasRemaining()) GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, snapshot, GL15.GL_STREAM_DRAW);
            else GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, 16, GL15.GL_STREAM_DRAW);
        } finally { GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, previous); }
    }
}
