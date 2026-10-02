package lib.kasuga.rendering.models.mc.backend.vbuffer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import lib.kasuga.rendering.models.mc.backend.FlatModelData;
import lombok.Getter;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.BitSet;

public class VanillaVertexBuffer implements IVertexBuffer {

    @Getter
    private final FlatModelData modelData;

    private final FencedVertexBuffer uploadRing;

    @Getter
    private final int vertexSize, vertexCount;

    @Getter
    private final VertexFormat format;

    @Getter
    private final VertexFormat.Mode meshMode;

    @Getter
    private final int maxMergeGap;

    public VanillaVertexBuffer(FlatModelData modelData, VertexFormat format, int maxMergeGap) {
        this.modelData = modelData;
        this.uploadRing = new FencedVertexBuffer(format, modelData.getMcMeshMode(), maxMergeGap);
        this.vertexSize =  format.getVertexSize();
        this.vertexCount = modelData.getVertexCount();
        this.meshMode = modelData.getMcMeshMode();
        this.format = format;
        this.maxMergeGap = maxMergeGap;
    }

    @Override
    public VertexBuffer getVertexBuffer() {
        if (uploadRing.current() == null) uploadGpuBuffer();
        return uploadRing.current();
    }

    @Override
    public void uploadGpuBuffer() {
        RenderSystem.assertOnRenderThread();
        uploadRing.upload(modelData.getBuffer().duplicate().clear(), null, true);
    }

    @Override
    public void updateGpuBuffer(@Nullable BitSet dirtyVertices, boolean forceUploadAll) {
        RenderSystem.assertOnRenderThread();
        if (forceUploadAll || uploadRing.current() == null || dirtyVertices == null) {
            uploadGpuBuffer();
        } else if (!dirtyVertices.isEmpty()) {
            uploadRing.upload(modelData.getBuffer().duplicate().clear(), dirtyVertices,
                    (long) dirtyVertices.cardinality() * 4 >= (long) vertexCount * 3);
        }
    }

    @Override
    public void draw(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, ShaderInstance shader) {
        VertexBuffer buffer = uploadRing.current();
        if (buffer == null) return;
        try { buffer.drawWithShader(modelViewMatrix, projectionMatrix, shader); }
        finally { markSubmitted(); }
    }

    @Override public void markSubmitted() { uploadRing.markSubmitted(); }

    @Override public void close() { uploadRing.close(); }
}
