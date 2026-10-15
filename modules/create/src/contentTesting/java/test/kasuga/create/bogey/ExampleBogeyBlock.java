package test.kasuga.create.bogey;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.function.Supplier;

public final class ExampleBogeyBlock extends AbstractBogeyBlock<ExampleBogeyBlockEntity> {
    private final Supplier<BogeyStyle> defaultStyle;
    private final Supplier<BlockEntityType<ExampleBogeyBlockEntity>> blockEntityType;
    private final Supplier<Set<TrackMaterial.TrackType>> validTrackTypes;

    public ExampleBogeyBlock(
            Properties properties,
            BogeySizes.BogeySize size,
            Supplier<BogeyStyle> defaultStyle,
            Supplier<BlockEntityType<ExampleBogeyBlockEntity>> blockEntityType,
            Supplier<Set<TrackMaterial.TrackType>> validTrackTypes
    ) {
        super(properties, size);
        this.defaultStyle = defaultStyle;
        this.blockEntityType = blockEntityType;
        this.validTrackTypes = validTrackTypes;
    }

    @Override
    public TrackMaterial.TrackType getTrackType(BogeyStyle style) {
        return validTrackTypes.get().iterator().next();
    }

    @Override
    public Set<TrackMaterial.TrackType> getValidPathfindingTypes(BogeyStyle style) {
        return validTrackTypes.get();
    }

    @Override
    public double getWheelPointSpacing() {
        return 2.5d;
    }

    @Override
    public double getWheelRadius() {
        return getSize().wheelRadius();
    }

    @Override
    protected Vec3 getConnectorAnchorOffset() {
        return new Vec3(0, 7 / 32f, 1);
    }

    @Override
    public BogeyStyle getDefaultStyle() {
        return defaultStyle.get();
    }

    @Override
    public ItemStack getCloneItemStack(
            BlockState state,
            HitResult target,
            LevelReader level,
            BlockPos pos,
            Player player
    ) {
        return AllBlocks.RAILWAY_CASING.asStack();
    }

    @Override
    public Class<ExampleBogeyBlockEntity> getBlockEntityClass() {
        return ExampleBogeyBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ExampleBogeyBlockEntity> getBlockEntityType() {
        return blockEntityType.get();
    }
}
