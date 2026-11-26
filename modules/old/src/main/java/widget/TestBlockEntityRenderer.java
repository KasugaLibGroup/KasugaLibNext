package lib.kasuga.testing.element;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.rendering.RenderContext;
import lib.kasuga.rendering.buffer.StencilMultiBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public class TestBlockEntityRenderer implements BlockEntityRenderer<TestBlockEntity> {
    ComponentTest componentTest = new ComponentTest();
    StencilMultiBuffer buffer = new StencilMultiBuffer();

    public TestBlockEntityRenderer(BlockEntityRendererProvider.Context context) {

    }

    @Override
    public void render(TestBlockEntity testBlockEntity, float v, PoseStack poseStack, MultiBufferSource multiBufferSource, int i, int i1) {
        RenderContext context = new RenderContext(buffer);
        context.pose().last().pose().set(poseStack.last().pose());
        context.pose().last().normal().set(poseStack.last().normal());
        componentTest.render(context);
        context.endBatch();
        buffer.render();
        buffer.discard();
    }
}
