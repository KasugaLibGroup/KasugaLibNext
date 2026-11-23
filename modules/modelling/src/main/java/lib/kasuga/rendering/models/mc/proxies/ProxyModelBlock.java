package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.registration.data_driven.state.StateProperty;
import lib.kasuga.registration.data_driven.state.StatePropertyContext;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyComposition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Data-driven block host whose geometry is rendered by {@link ProxyModelBlockEntity}.
 *
 * <p>State properties come from the normal {@code state_properties} field. A vanilla
 * {@code facing} declaration is installed into placement, rotation and mirroring automatically.
 * The block always uses the entity render shape, so a missing baked block model cannot leak the
 * purple/black fallback texture into the world. Content-specific interactions and state writes
 * belong to a dedicated block subclass and factory in the consuming mod.
 */
public class ProxyModelBlock extends Block implements EntityBlock {
    @Nullable
    private volatile BlockEntityType<?> blockEntityType;

    public ProxyModelBlock(Properties properties) {
        super(properties);
        registerDefaultState(StatePropertyContext.applyDefaults(defaultBlockState()));
    }

    /**
     * Extension seam for a consuming mod's block-specific controller and playback composition.
     * The default composition only renders the selected model.
     */
    public ProxyComposition<ProxyModelBlockEntity, ProxyModelInput, Void, Void, Void>
    createProxyComposition(ProxyModelBlockEntity element) {
        return ProxyModelProxy.defaultComposition(element);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        StatePropertyContext.addTo(builder);
    }

    @Override
    public void onBlockStateChange(
            LevelReader level,
            BlockPos pos,
            BlockState oldState,
            BlockState newState
    ) {
        super.onBlockStateChange(level, pos, oldState, newState);
        StateProperty.notifyChanges(level, pos, oldState, newState);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(
                    BlockStateProperties.HORIZONTAL_FACING,
                    context.getHorizontalDirection().getOpposite()
            );
        }
        if (state.hasProperty(BlockStateProperties.DOOR_HINGE)) {
            state = state.setValue(BlockStateProperties.DOOR_HINGE, placementHinge(context));
        }
        return state;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.setValue(
                    BlockStateProperties.HORIZONTAL_FACING,
                    rotation.rotate(state.getValue(BlockStateProperties.HORIZONTAL_FACING))
            );
        }
        return super.rotate(state, rotation);
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(
                    BlockStateProperties.HORIZONTAL_FACING,
                    mirror.mirror(state.getValue(BlockStateProperties.HORIZONTAL_FACING))
            );
        }
        if (mirror != Mirror.NONE && state.hasProperty(BlockStateProperties.DOOR_HINGE)) {
            DoorHingeSide hinge = state.getValue(BlockStateProperties.DOOR_HINGE);
            state = state.setValue(
                    BlockStateProperties.DOOR_HINGE,
                    hinge == DoorHingeSide.LEFT ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT
            );
        }
        return state;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType<?> type = blockEntityType;
        if (type != null && type.isValid(state)) {
            return type.create(pos, state);
        }
        for (BlockEntityType<?> candidate : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            if (!candidate.isValid(state)) {
                continue;
            }
            BlockEntity entity = candidate.create(pos, state);
            if (entity instanceof ProxyModelBlockEntity) {
                blockEntityType = candidate;
                return entity;
            }
        }
        return null;
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        return ProxyTickers.forClientBlockEntity(type);
    }

    /**
     * Horizontal model yaw for the conventional north-facing Blockbench bind pose.
     * Blocks without a vanilla {@code facing} property keep the identity orientation.
     */
    public static float facingYaw(BlockState state) {
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return 0f;
        }
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return switch (facing) {
            case EAST -> 90f;
            case SOUTH -> 180f;
            case WEST -> 270f;
            default -> 0f;
        };
    }

    private static DoorHingeSide placementHinge(BlockPlaceContext context) {
        Vec3 location = context.getClickLocation();
        Direction direction = context.getHorizontalDirection();
        BlockPos pos = context.getClickedPos();
        BlockPos leftPos = left(pos, direction, 1);
        BlockPos rightPos = right(pos, direction, 1);
        Vec3 leftCenter = new Vec3(
                leftPos.getX() + 0.5,
                location.y,
                leftPos.getZ() + 0.5
        );
        Vec3 rightCenter = new Vec3(
                rightPos.getX() + 0.5,
                location.y,
                rightPos.getZ() + 0.5
        );
        return location.distanceToSqr(leftCenter) > location.distanceToSqr(rightCenter)
                ? DoorHingeSide.LEFT
                : DoorHingeSide.RIGHT;
    }

    private static BlockPos left(BlockPos pos, Direction direction, int distance) {
        return switch (direction) {
            case EAST -> pos.south(distance);
            case NORTH -> pos.east(distance);
            case WEST -> pos.north(distance);
            case SOUTH -> pos.west(distance);
            default -> pos;
        };
    }

    private static BlockPos right(BlockPos pos, Direction direction, int distance) {
        return switch (direction) {
            case EAST -> pos.north(distance);
            case NORTH -> pos.west(distance);
            case WEST -> pos.south(distance);
            case SOUTH -> pos.east(distance);
            default -> pos;
        };
    }
}
