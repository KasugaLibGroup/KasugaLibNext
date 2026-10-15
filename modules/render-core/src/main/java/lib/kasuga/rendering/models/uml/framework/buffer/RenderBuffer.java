package lib.kasuga.rendering.models.uml.framework.buffer;

import org.joml.Matrix4f;

import java.util.BitSet;

/**
 * Thread-owned geometry upload/draw capability; host shader and geometry types
 * are explicit type parameters. GPU handles and binding APIs are not exposed.
 * An initial upload precedes updates/draws; null dirty bits mean no known subset,
 * and forceUploadAll requests a complete refresh. Each draw, including a replay,
 * must notify markSubmitted so implementations can fence in-flight storage.
 */
public interface RenderBuffer<D, S> extends AutoCloseable {
    D getModelData();
    void uploadGpuBuffer();
    void updateGpuBuffer(BitSet dirtyVertices, boolean forceUploadAll);
    void draw(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, S shader);
    default void markSubmitted() {}
}
