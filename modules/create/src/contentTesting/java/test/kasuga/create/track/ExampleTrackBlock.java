package test.kasuga.create.track;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.function.Supplier;

public final class ExampleTrackBlock extends TrackBlock {
    private final Supplier<? extends AbstractBogeyBlock<?>> defaultBogey;

    public ExampleTrackBlock(
            Properties properties,
            TrackMaterial material,
            Supplier<? extends AbstractBogeyBlock<?>> defaultBogey
    ) {
        super(properties, material);
        this.defaultBogey = defaultBogey;
    }

    @Override
    public BlockState getBogeyAnchor(BlockGetter world, BlockPos pos, BlockState state) {
        Direction.Axis axis = state.getValue(SHAPE) == TrackShape.XO
                ? Direction.Axis.X
                : Direction.Axis.Z;
        return defaultBogey.get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_AXIS, axis);
    }
}
