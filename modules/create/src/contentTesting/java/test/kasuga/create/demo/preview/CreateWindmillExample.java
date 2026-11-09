package test.kasuga.create.demo.preview;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import com.simibubi.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import lib.kasuga.scene.MinecraftStage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/** Server-owned native windmill. Create assembles, synchronizes and ticks its real entity. */
final class CreateWindmillExample implements AutoCloseable {
    // Keep the physical source above the gallery so its ordinary renderer cannot overlay the exploded proxy.
    static final BlockPos BEARING = new BlockPos(0, 150, 0);
    private final MinecraftStage stage;
    private final SuperGlueEntity glue;
    private final long installed;
    private boolean assembled, closed;
    volatile int entityId = -1;
    volatile String failure;
    private CreateWindmillExample(MinecraftStage stage) {
        this.stage = stage;
        installed = stage.level().getGameTime();
        stage.block(BEARING, AllBlocks.WINDMILL_BEARING.getDefaultState().setValue(BearingBlock.FACING, Direction.SOUTH));
        var anchor = BEARING.south();
        stage.block(anchor, AllBlocks.ANDESITE_CASING.getDefaultState());
        for (int arm = 0; arm < 4; arm++) for (int r = 1; r <= 8; r++) {
            int dx = arm == 0 ? r : arm == 2 ? -r : 0;
            int dy = arm == 1 ? r : arm == 3 ? -r : 0;
            stage.block(anchor.offset(dx, dy, 0), Blocks.SPRUCE_PLANKS.defaultBlockState());
            if (r < 3) continue;
            for (int w = 1; w <= 2; w++) {
                int tx = arm == 1 ? -w : arm == 3 ? w : 0;
                int ty = arm == 0 ? w : arm == 2 ? -w : 0;
                stage.block(anchor.offset(dx + tx, dy + ty, 0), AllBlocks.SAIL.getDefaultState()
                        .setValue(BlockStateProperties.FACING, Direction.SOUTH));
            }
        }
        glue = new SuperGlueEntity(stage.level(), new AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(anchor.offset(-9, -9, 0)),
                net.minecraft.world.phys.Vec3.atLowerCornerOf(anchor.offset(10, 10, 1))));
        stage.level().addFreshEntity(glue);
    }
    static CreateWindmillExample install(MinecraftStage stage) { return new CreateWindmillExample(stage); }
    void tick() {
        if (closed || assembled || failure != null || stage.level().getGameTime() - installed < 6) return;
        try {
            var bearing = (WindmillBearingBlockEntity) stage.level().getBlockEntity(BEARING);
            if (bearing == null) throw new IllegalStateException("Native windmill bearing is missing");
            bearing.assemble();
            ControlledContraptionEntity rotor = bearing.getMovedContraption();
            if (rotor == null || rotor.getContraption().getBlocks().size() < 60)
                throw new IllegalStateException("Create failed to assemble the native windmill");
            if (rotor.getContraption().entity != rotor) throw new IllegalStateException("Create entity backlink disagrees");
            entityId = rotor.getId(); assembled = true;
            com.mojang.logging.LogUtils.getLogger().info("CREATE_WINDMILL_ENTITY_READY entity={} blocks={}", entityId, rotor.getContraption().getBlocks().size());
        } catch (Exception error) { failure = error.toString(); com.mojang.logging.LogUtils.getLogger().error("Cannot assemble native windmill", error); }
    }
    @Override public void close() {
        if (closed) return; closed = true;
        var bearing = stage.level().getBlockEntity(BEARING);
        if (bearing instanceof WindmillBearingBlockEntity windmill) windmill.disassemble();
        if (entityId >= 0 && stage.level().getEntity(entityId) != null) stage.level().getEntity(entityId).discard();
        glue.discard();
    }
}
