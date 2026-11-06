package lib.kasuga.registration.data_driven.state;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;

/** Plain block with a state definition supplied by the data-driven registration pipeline. */
public class DataDrivenBlock extends Block {
    public DataDrivenBlock(Properties properties) {
        super(properties);
        registerDefaultState(StatePropertyContext.applyDefaults(defaultBlockState()));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        StatePropertyContext.addTo(builder);
    }

    @Override
    public void onBlockStateChange(LevelReader level, BlockPos pos, BlockState oldState, BlockState newState) {
        super.onBlockStateChange(level, pos, oldState, newState);
        StateProperty.notifyChanges(level, pos, oldState, newState);
    }
}
