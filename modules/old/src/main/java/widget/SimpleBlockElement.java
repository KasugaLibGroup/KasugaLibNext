package lib.kasuga.testing.element;

import lib.kasuga.rendering.RenderContext;
import lib.kasuga.widget.dom.DomAttributeType;
import lib.kasuga.widget.dom.DomSchema;
import lib.kasuga.widget.renderer.model.Element3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

public class SimpleBlockElement extends Element3D {
    public static DomAttributeType<BlockState> BLOCK_STATE = DomAttributeType.createDefault();
    public SimpleBlockElement(DomSchema schema) {
        super(schema);
    }
    @Override
    public void renderSelf(RenderContext context) {
        context.pose().pushPose();
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                Blocks.GRASS_BLOCK.defaultBlockState(),
                context.pose(),
                context.buffer(),
                context.light(),
                context.overlay(),
                ModelData.EMPTY,
                (RenderType) null
        );
        context.pose().popPose();
    }
}
