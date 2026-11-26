package lib.kasuga.testing.element;

import io.micronaut.context.annotation.Context;
import lib.kasuga.KasugaLib;
import lib.kasuga.KasugaLibApplication;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.block_entity.BlockEntityReg;
import lib.kasuga.registration.minecraft.block_entity.renderer.BlockEntityRendererReg;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;

@Context()
public class ElementModule {
    public static BlockReg<TestBlock> TEST_BLOCK = BlockReg.of("ui_test", TestBlock::new)
            .withDefaultBlockItem("ui_test")
            .setParent(KasugaLibApplication.REGISTRY);

    public static BlockEntityReg<TestBlockEntity> TEST_BLOCK_ENTITY = BlockEntityReg.of("ui_test_entity", TestBlockEntity::new)
            .validBlocks(TEST_BLOCK::getEntry)
            .setParent(KasugaLibApplication.REGISTRY);

    public static BlockEntityRendererReg<TestBlockEntity> TEST_BLOCK_ENTITY_RENDERER =
            new BlockEntityRendererReg<TestBlockEntity>(()->TestBlockEntityRenderer::new)
            .withBlockEntity(TEST_BLOCK_ENTITY)
            .setParent(KasugaLibApplication.REGISTRY);
}
