package lib.kasuga.rendering.effect;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.rendering.effect.pipeline.CompiledRenderPipeline;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;

/** Immutable view of the state exposed to one world render pipeline invocation. */
public record WorldRenderPipelineContext(
        RenderPipelineDescriptor descriptor,
        CompiledRenderPipeline pipeline,
        RenderLevelStageEvent.Stage stage,
        Minecraft minecraft,
        ClientLevel level,
        LevelRenderer levelRenderer,
        PoseStack poseStack,
        Matrix4f modelViewMatrix,
        Matrix4f projectionMatrix,
        int renderTick,
        DeltaTracker partialTick,
        Camera camera,
        Frustum frustum,
        RenderBuffers renderBuffers,
        MultiBufferSource.BufferSource bufferSource,
        RenderTarget mainRenderTarget,
        String viewId
) {

    /** Compatibility constructor; snapshot the current view rather than consulting it later. */
    public WorldRenderPipelineContext(RenderPipelineDescriptor descriptor, CompiledRenderPipeline pipeline,
            RenderLevelStageEvent.Stage stage, Minecraft minecraft, ClientLevel level, LevelRenderer levelRenderer,
            PoseStack poseStack, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, int renderTick,
            DeltaTracker partialTick, Camera camera, Frustum frustum, RenderBuffers renderBuffers,
            MultiBufferSource.BufferSource bufferSource, RenderTarget mainRenderTarget) {
        this(descriptor, pipeline, stage, minecraft, level, levelRenderer, poseStack, modelViewMatrix,
                projectionMatrix, renderTick, partialTick, camera, frustum, renderBuffers, bufferSource,
                mainRenderTarget, MinecraftWorldViews.currentViewId());
    }

    static WorldRenderPipelineContext from(RenderLevelStageEvent event, Minecraft minecraft,
                                           RenderPipelineDescriptor descriptor,
                                           CompiledRenderPipeline pipeline) {
        return new WorldRenderPipelineContext(
                descriptor,
                pipeline,
                event.getStage(),
                minecraft,
                minecraft.level,
                event.getLevelRenderer(),
                event.getPoseStack(),
                event.getModelViewMatrix(),
                event.getProjectionMatrix(),
                event.getRenderTick(),
                event.getPartialTick(),
                event.getCamera(),
                event.getFrustum(),
                minecraft.renderBuffers(),
                minecraft.renderBuffers().bufferSource(),
                minecraft.getMainRenderTarget()
        );
    }
}
