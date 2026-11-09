package test.kasuga.data_driven.block;

import lib.kasuga.registration.data_driven.state.StatePropertyContext;
import lib.kasuga.registration.data_driven.state.StateProperty;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Example of a custom factory retaining a native property while opting in to JSON properties. */
public class StatefulTestBlock extends Block {
    public StatefulTestBlock(Properties properties) {
        super(properties);
        registerDefaultState(StatePropertyContext.applyDefaults(
                defaultBlockState().setValue(BlockStateProperties.POWERED, false)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(BlockStateProperties.POWERED);
        StatePropertyContext.addTo(builder);
    }

    @Override
    public void onBlockStateChange(LevelReader level, BlockPos pos, BlockState oldState, BlockState newState) {
        super.onBlockStateChange(level, pos, oldState, newState);
        StateProperty.notifyChanges(level, pos, oldState, newState);
    }
}
