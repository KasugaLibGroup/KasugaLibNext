package lib.kasuga.rendering.models.mc.dynamic.fsm;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Block that hosts an {@link AnimationBlockEntity}. The block entity type is discovered by
 * scanning {@link BuiltInRegistries#BLOCK_ENTITY_TYPE} for a type valid for the block's state,
 * keeping this block decoupled from any specific registration.
 *
 * <p>{@link #getTicker} returns {@link AnimationBlockEntity#TICKER} only when the block entity
 * type actually produces an {@link AnimationBlockEntity}: a cached type-identity check with an
 * instanceof fallback (covers multiple fsm blocks with distinct BE types).
 */
public class FsmBlock extends Block implements EntityBlock {

    /** Cached BE type verified to produce {@link AnimationBlockEntity}s (identity fast path). */
    @Nullable
    private static volatile BlockEntityType<?> fsmBeType;

    public FsmBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType<?> cached = fsmBeType;
        if (cached != null && cached.isValid(state)) {
            return cached.create(pos, state);
        }
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            if (type.isValid(state)) {
                BlockEntity be = type.create(pos, state);
                // cache the resolved type so later creates (chunk load) skip the full scan
                if (be instanceof AnimationBlockEntity) {
                    fsmBeType = type;
                }
                return be;
            }
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        BlockEntityType<?> cached = fsmBeType;
        if (cached != null && type == cached) {
            return (BlockEntityTicker<T>) AnimationBlockEntity.TICKER;
        }
        // isValid fast path: reject BE types unrelated to this block (no construction).
        // instanceof fallback: only attach the ticker when the created BE is an AnimationBlockEntity.
        if (type.isValid(state)) {
            BlockEntity probe = type.create(BlockPos.ZERO, state);
            if (probe instanceof AnimationBlockEntity) {
                fsmBeType = type;
                return (BlockEntityTicker<T>) AnimationBlockEntity.TICKER;
            }
        }
        return null;
    }
}
