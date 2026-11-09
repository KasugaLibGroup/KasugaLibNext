package test.kasuga.create.bogey;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import com.simibubi.create.content.trains.track.TrackMaterial;
import lib.kasuga.rendering.models.mc.proxies.ProxyTickers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.function.Supplier;

public final class ExampleBogeyBlock extends AbstractBogeyBlock<ExampleBogeyBlockEntity> {
    private final Supplier<BogeyStyle> defaultStyle;
    private final Supplier<BlockEntityType<ExampleBogeyBlockEntity>> blockEntityType;
    /**
     * The one track type this bogey rides. Create compares it against the material of the edge the
     * carriage stands on ({@code AbstractBogeyBlock#isOnIncompatibleTrack}); a mismatch does not
     * derail the train but marks the carriage {@code blocked}, which zeroes its speed and reports
     * "A Carriage has reached the end of its Track". It must therefore be a single, deterministic
     * value — a set from which an arbitrary element is picked makes the train randomly immobile.
     */
    private final TrackMaterial.TrackType trackType;
    /**
     * Distance between this bogey's two wheel points, as an arc length on the track. Create derives
     * stress from {@code spacing - distance(travelling points)}, so it has to match the model's
     * wheelset spacing (±z of the wheel parts); declaring it larger than the geometry makes every
     * carriage accumulate stress until the train derails.
     */
    private final double wheelPointSpacing;

    public ExampleBogeyBlock(
            Properties properties,
            BogeySizes.BogeySize size,
            Supplier<BogeyStyle> defaultStyle,
            Supplier<BlockEntityType<ExampleBogeyBlockEntity>> blockEntityType,
            TrackMaterial.TrackType trackType,
            double wheelPointSpacing
    ) {
        super(properties, size);
        this.defaultStyle = defaultStyle;
        this.blockEntityType = blockEntityType;
        this.trackType = trackType;
        this.wheelPointSpacing = wheelPointSpacing;
    }

    @Override
    public TrackMaterial.TrackType getTrackType(BogeyStyle style) {
        return trackType;
    }

    @Override
    public Set<TrackMaterial.TrackType> getValidPathfindingTypes(BogeyStyle style) {
        return Set.of(trackType);
    }

    @Override
    public double getWheelPointSpacing() {
        return wheelPointSpacing;
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

    @Override
    public <S extends BlockEntity> BlockEntityTicker<S> getTicker(Level level, BlockState state, BlockEntityType<S> type) {
        // The demo spin has to run before the proxy reads the angle, so it goes in as the wrapped ticker.
        return ProxyTickers.forBlockEntity(type, (lvl, pos, st, blockEntity) -> {
            if (blockEntity instanceof ExampleBogeyBlockEntity bogey) {
                bogey.demoSpin(lvl, pos);
            }
        });
    }
}
