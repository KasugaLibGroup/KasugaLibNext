package lib.kasuga.testing.element;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class TestBlockEntity extends BlockEntity {
    public TestBlockEntity(BlockPos pos, BlockState blockState) {
        super(ElementModule.TEST_BLOCK_ENTITY.getEntry(), pos, blockState);
    }
}
