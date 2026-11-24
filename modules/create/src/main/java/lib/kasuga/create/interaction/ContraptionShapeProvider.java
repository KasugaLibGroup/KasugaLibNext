package lib.kasuga.create.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Opt-in selection shape that needs the captured blocks and BEs at a real local position. */
public interface ContraptionShapeProvider {
    VoxelShape getContraptionShape(BlockState state, BlockGetter localBlocks, BlockPos localPos);
}
