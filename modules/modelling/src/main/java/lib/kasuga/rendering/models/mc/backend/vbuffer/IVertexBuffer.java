package lib.kasuga.rendering.models.mc.backend.vbuffer;

import com.mojang.blaze3d.vertex.VertexBuffer;
import lib.kasuga.mixins.client.AccessorVertexBuffer;
import lib.kasuga.rendering.models.mc.backend.FlatModelData;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.BitSet;

public interface IVertexBuffer extends AutoCloseable {

    VertexBuffer getVertexBuffer();

    FlatModelData getModelData();

    void uploadGpuBuffer();

    void updateGpuBuffer(@Nullable BitSet dirtyVertices, boolean forceUploadAll);

    void draw(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, ShaderInstance shader);

    /** Called after every draw, including transparency replays of prepared geometry. */
    default void markSubmitted() {}

    default int getBufferId() {
        return ((AccessorVertexBuffer) getVertexBuffer()).getVertexBufferId();
    }
}
