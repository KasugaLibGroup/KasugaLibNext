package test.kasuga.modelling;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

/**
 * Data-driven sibling of {@link FanBlock}: identical interaction, ticker and render behaviour (all
 * inherited), but the hosted {@link FanBlockEntity} is produced by the <em>block entity type's</em>
 * supplier rather than built here.
 *
 * <p>Why a subclass instead of reusing {@link FanBlock} directly: Minecraft creates a block entity
 * through {@code ((EntityBlock) block).newBlockEntity(pos, state)} (see {@code LevelChunk}), so the
 * machine id / model that the {@code fan_be} factory captured from the block entity's {@code params}
 * only reach the instance if the block delegates to {@link BlockEntityType#create}. The base
 * {@code FanBlock} hard-codes {@code new FanBlockEntity(type, pos, state)} and therefore always binds
 * the original {@code kasuga_lib:fan_machine}; overriding here keeps {@code FanBlock} untouched (its
 * two programmatic registrations must not change) while making the data-driven block carry the
 * params-selected machine.
 */
public class FanDataDrivenBlock extends FanBlock {

    private final Supplier<BlockEntityType<?>> blockEntityType;

    public FanDataDrivenBlock(BlockBehaviour.Properties properties, Supplier<BlockEntityType<?>> blockEntityType) {
        super(properties, blockEntityType);
        this.blockEntityType = blockEntityType;
    }

    /**
     * Delegates to the block entity type's supplier so the machine / model read from the JSON
     * {@code params} by the {@code fan_be} factory take effect (the same delegation {@code FsmBlock}
     * uses for its data-driven blocks).
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType<?> type = blockEntityType.get();
        return type == null ? null : type.create(pos, state);
    }
}
