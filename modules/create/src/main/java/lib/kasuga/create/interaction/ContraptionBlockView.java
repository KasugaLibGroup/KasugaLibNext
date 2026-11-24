package lib.kasuga.create.interaction;

import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Read-only local view; ownership lookups never fall through to the surrounding world. */
public final class ContraptionBlockView implements BlockGetter {
    private final Contraption contraption;
    private final BlockGetter dimensions;

    public ContraptionBlockView(Contraption contraption, BlockGetter dimensions) {
        this.contraption = Objects.requireNonNull(contraption, "contraption");
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions");
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        var info = contraption.getBlocks().get(pos);
        return info == null ? Blocks.AIR.defaultBlockState() : info.state();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return contraption.getBlocks().containsKey(pos)
                ? contraption.presentBlockEntities.get(pos) : null;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return dimensions.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return dimensions.getMinBuildHeight();
    }
}
