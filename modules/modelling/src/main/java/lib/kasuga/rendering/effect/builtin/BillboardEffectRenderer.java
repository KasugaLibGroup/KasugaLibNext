package lib.kasuga.rendering.effect.builtin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import lib.kasuga.rendering.effect.EffectRenderer;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.Set;

final class BillboardEffectRenderer implements EffectRenderer<BillboardEffect> {
    private final Set<RenderType> usedRenderTypes = new HashSet<>();
    private float partialTick;
    private Vec3 cameraPosition = Vec3.ZERO;

    @Override
    public void begin(WorldRenderPipelineContext context) {
        usedRenderTypes.clear();
        partialTick = context.partialTick().getGameTimeDeltaPartialTick(false);
        cameraPosition = context.camera().getPosition();
    }

    @Override
    public void render(BillboardEffect effect, WorldRenderPipelineContext context) {
        RenderType renderType = context.pipeline().renderType(effect.texture(), false, false);
        usedRenderTypes.add(renderType);
        VertexConsumer consumer = context.bufferSource().getBuffer(renderType);
        Vec3 position = effect.position(partialTick);
        float halfSize = effect.size(partialTick) * 0.5f;
        BillboardEffect.Color color = effect.color(partialTick);

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        try {
            poseStack.translate(
                    position.x - cameraPosition.x,
                    position.y - cameraPosition.y,
                    position.z - cameraPosition.z
            );
            poseStack.mulPose(context.camera().rotation());
            poseStack.mulPose(Axis.ZP.rotation(effect.rotation(partialTick)));
            Matrix4f pose = poseStack.last().pose();
            vertex(consumer, pose, -halfSize, -halfSize, 0, 1, color);
            vertex(consumer, pose, halfSize, -halfSize, 1, 1, color);
            vertex(consumer, pose, halfSize, halfSize, 1, 0, color);
            vertex(consumer, pose, -halfSize, halfSize, 0, 0, color);
        } finally {
            poseStack.popPose();
        }
    }

    @Override
    public void end(WorldRenderPipelineContext context) {
        for (RenderType renderType : usedRenderTypes) {
            context.bufferSource().endBatch(renderType);
        }
        usedRenderTypes.clear();
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, float x, float y,
                               float u, float v, BillboardEffect.Color color) {
        consumer.addVertex(pose, x, y, 0)
                .setColor(color.red(), color.green(), color.blue(), color.alpha())
                .setUv(u, v)
                .setLight(LightTexture.FULL_BRIGHT);
    }
}
