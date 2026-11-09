package lib.kasuga.scene;

import lib.kasuga.KasugaLibApplication;
import lib.kasuga.registration.minecraft.block.BlockReg;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.material.PushReaction;

/** Invisible, state-shaped collision for temporary terrain composed through MinecraftStage. */
public final class SceneGround extends Block {
    public static final IntegerProperty HEIGHT = IntegerProperty.create("height", 1, 16);
    private static final VoxelShape[] SHAPES = new VoxelShape[16];
    private static volatile BlockReg<SceneGround> registration;

    static {
        for (int layers = 1; layers <= 16; layers++)
            SHAPES[layers - 1] = Block.box(0, 0, 0, 16, layers, 16);
    }

    public SceneGround(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(HEIGHT, 16));
    }

    static synchronized void register() {
        if (registration != null) return;
        registration = BlockReg.of("scene_ground", SceneGround::new)
                .mapColor(net.minecraft.world.level.material.MapColor.NONE)
                .noOcclusion().noLootTable().sound(SoundType.SAND)
                .pushReaction(PushReaction.BLOCK)
                .isSuffocating((state, level, pos) -> false)
                .isViewBlocking((state, level, pos) -> false)
                .setParent(KasugaLibApplication.REGISTRY);
    }

    public static Block block() {
        var current = registration;
        if (current == null) throw new IllegalStateException("Scene ground registration is not ready");
        return current.getEntry();
    }

    public static BlockState stateForLayers(int layers) {
        if (layers < 1 || layers > 16) throw new IllegalArgumentException("Ground layers must be in [1, 16]");
        return block().defaultBlockState().setValue(HEIGHT, layers);
    }

    public static int layers(float fractionalHeight) {
        if (!Float.isFinite(fractionalHeight) || fractionalHeight <= 0 || fractionalHeight > 1)
            throw new IllegalArgumentException("Fractional ground height must be finite and in (0, 1]");
        return Math.clamp(Math.round(fractionalHeight * 16), 1, 16);
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HEIGHT);
    }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(HEIGHT) - 1];
    }

    @Override public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}
