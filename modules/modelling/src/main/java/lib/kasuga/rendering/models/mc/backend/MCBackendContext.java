package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lombok.Getter;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;

@Getter
public class MCBackendContext {

    private final VertexConsumer vertexConsumer;
    private final PoseStack poseStack;
    private final RenderBuffers buffers;
    private final MultiBufferSource bufferSource;
    private final Camera camera;
    private final Frustum frustum;
    private final Matrix4f modelViewMatrix;
    private final Matrix4f projectionMatrix;
    private final int renderTick;
    private final DeltaTracker partialTick;
    private final ClientLevel level;

    public MCBackendContext(
            VertexConsumer vertexConsumer, PoseStack poseStack,
            RenderBuffers buffers, MultiBufferSource bufferSource, Camera camera,
            Frustum frustum, Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
            int renderTick, DeltaTracker partialTick, ClientLevel level) {
        this.buffers = buffers;
        this.bufferSource = bufferSource;
        this.vertexConsumer = vertexConsumer;
        this.poseStack = poseStack;
        this.camera = camera;
        this.frustum = frustum;
        this.modelViewMatrix = modelViewMatrix;
        this.projectionMatrix = projectionMatrix;
        this.renderTick = renderTick;
        this.partialTick = partialTick;
        this.level = level;
    }

    /**
     * The render partial-tick fraction in {@code [0,1)} — how far between the last and next game tick this frame
     * is. Animation drivers sample at frame rate using this fraction so motion stays smooth between game ticks.
     */
    public float getPartialTickFraction() {
        return partialTick.getGameTimeDeltaPartialTick(true);
    }
}
