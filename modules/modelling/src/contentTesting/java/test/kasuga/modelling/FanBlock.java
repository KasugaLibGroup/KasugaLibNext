package test.kasuga.modelling;

import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationBlockEntity;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * v2.0 fan test block: {@code useWithoutItem} (server) raises the {@code cycle} FSM trigger, so the gear
 * layer cycles {@code off→g1→g2→g3→off}; the FSM sync channel broadcasts the resulting snapshot — no
 * {@code getUpdateTag} ramp broadcast, no {@code sendBlockUpdated}. Hosts an
 * {@link AnimationBlockEntity} and drives it every tick through {@link AnimationBlockEntity#TICKER}.
 */
public class FanBlock extends Block implements EntityBlock {

    private final Supplier<BlockEntityType<?>> blockEntityType;

    public FanBlock(BlockBehaviour.Properties properties, Supplier<BlockEntityType<?>> blockEntityType) {
        super(properties);
        this.blockEntityType = blockEntityType;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FanBlockEntity(blockEntityType.get(), pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof FanBlockEntity fan) {
            StateMachine<?> machine = fan.machine();
            if (machine != null) {
                machine.trigger(FanBlockEntity.CYCLE);
                // The gear ring is locked off→g1→g2→g3→off: the click's destination is (gear+1)%4.
                // Feedback mirrors the 1.0 catenary-wire item ({@code ComponentTranslationTool}):
                // an action-bar message ({@code displayClientMessage(..., true)} — shows above the
                // hotbar WITHOUT the accessibility "show subtitles" option) + the vanilla button click.
                int gear = machine.layer("gear").activeStateIndex();
                int next = (gear + 1) % 4;
                player.displayClientMessage(
                        Component.translatable("msg.kasuga_lib.fan_gear_" + next).withStyle(ChatFormatting.GREEN),
                        true);
                level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 1.0f, 1.0f);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    @SuppressWarnings("unchecked")
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == blockEntityType.get() ? (BlockEntityTicker<T>) AnimationBlockEntity.TICKER : null;
    }

    /**
     * The fan is a thin panel / animated custom-render block — it must not be treated as a full opaque
     * cube, or adjacent blocks would wrongly cull their faces against it and players would be blocked.
     */
    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }
}