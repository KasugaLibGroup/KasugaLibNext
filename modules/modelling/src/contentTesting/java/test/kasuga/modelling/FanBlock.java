package test.kasuga.modelling;

import lib.kasuga.rendering.models.mc.proxies.ProxyTickers;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The fan example block: right-click raises the {@code cycle} trigger on the hosted state machine
 * ({@code off→g1→g2→g3→off}); the model system takes care of the rest.
 *
 * <p>Used by both registrations — the code-registered {@code example_fan} and the data-driven
 * {@code example_fan_data_driven}. {@link #newBlockEntity} delegates to the block entity type, which is
 * what lets the data-driven type inject its {@code params}-selected machine/model without a subclass.
 */
public class FanBlock extends Block implements EntityBlock {

    /** Mirrors the machine state "spinning" into the world; written by the proxy's push direction. */
    public static final BooleanProperty RUNNING = BooleanProperty.create("running");

    private final Supplier<BlockEntityType<?>> blockEntityType;

    public FanBlock(BlockBehaviour.Properties properties, Supplier<BlockEntityType<?>> blockEntityType) {
        super(properties);
        this.blockEntityType = blockEntityType;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType<?> type = blockEntityType.get();
        return type == null ? null : type.create(pos, state);
    }

    /**
     * Both sides drive the proxy; the block may combine a previous ticker if it ever has one.
     *
     * <p>Do not use {@code forClientBlockEntity} here: the fan's state machine is authoritative on the
     * server, so the server must materialize a proxy instance too.
     */
    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return ProxyTickers.forBlockEntity(type);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(RUNNING);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        return cycle(level, pos, player);
    }

    /** Cycles the gear through the element-facing session command, never through StateMachine. */
    static InteractionResult cycle(Level level, BlockPos pos, Player player) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof FanBlockEntity fan) {
            fan.requestCycle();
            int next = fan.nextGearIndex();
            player.displayClientMessage(
                    Component.translatable("msg.kasuga_lib.fan_gear_" + next).withStyle(ChatFormatting.GREEN),
                    true);
            level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 1.0f, 1.0f);
        }
        return InteractionResult.SUCCESS;
    }

    /** Thin panel: keep adjacent blocks from culling against it and players from being blocked. */
    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return Shapes.empty();
    }

    /** Lets a comparator observe the machine → world direction. */
    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return state.getValue(RUNNING) ? 15 : 0;
    }
}
