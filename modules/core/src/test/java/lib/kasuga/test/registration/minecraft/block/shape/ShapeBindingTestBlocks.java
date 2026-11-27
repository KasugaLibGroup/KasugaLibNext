package lib.kasuga.test.registration.minecraft.block.shape;

import lib.kasuga.KasugaLib;
import lib.kasuga.KasugaLibRegistry;
import lib.kasuga.geometry.Box;
import lib.kasuga.geometry.Shape;
import lib.kasuga.registration.Registry;
import lib.kasuga.registration.minecraft.block.BlockReg;
import lib.kasuga.registration.minecraft.block.shape.BlockShapePlan;
import lib.kasuga.registration.minecraft.block.shape.BlockStateKey;
import lib.kasuga.registration.minecraft.block.shape.ShapeSelection;
import lib.kasuga.registration.minecraft.block.shape.ShapeSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

public final class ShapeBindingTestBlocks {
    public static final Registry REGISTRY = KasugaLibRegistry.getRegistryOf(KasugaLib.MODID);

    public static final Shape FRAME = Shape.of(Box.fromPixels(0, 0, 0, 16, 16, 2))
            .difference(Shape.of(Box.fromPixels(4, 0, 0, 12, 12, 2)));
    public static final Shape CUBE = Shape.of(Box.fromPixels(0, 0, 0, 16, 16, 16));
    public static final Shape SLAB = Shape.of(Box.fromPixels(0, 0, 0, 16, 8, 16));

    public static final BlockReg<NativeBlock> STATIC = BlockReg.of(
                    "shape_binding_static", NativeBlock::new)
            .withShapePlan(plan(Map.of(
                    ShapeSlot.SHAPE, ShapeSelection.geometry(FRAME),
                    ShapeSlot.COLLISION, ShapeSelection.alias(ShapeSlot.SHAPE),
                    ShapeSlot.SUPPORT, ShapeSelection.MISSING,
                    ShapeSlot.OCCLUSION, ShapeSelection.alias(ShapeSlot.SHAPE),
                    ShapeSlot.VISUAL, ShapeSelection.empty(),
                    ShapeSlot.INTERACTION, ShapeSelection.nativeBinding()
            )))
            .setParent(REGISTRY);

    public static final BlockReg<NativeBlock> NATIVE = BlockReg.of(
                    "shape_binding_native", NativeBlock::new)
            .withShapePlan(plan(Map.of(
                    ShapeSlot.SHAPE, ShapeSelection.MISSING,
                    ShapeSlot.COLLISION, ShapeSelection.nativeBinding(),
                    ShapeSlot.VISUAL, ShapeSelection.empty(),
                    ShapeSlot.INTERACTION, ShapeSelection.nativeBinding()
            )))
            .setParent(REGISTRY);

    public static final BlockReg<NativeBlock> NATIVE_OVERRIDE = BlockReg.of(
                    "shape_binding_native_override", NativeBlock::new)
            .withShapePlan(plan(Map.of(
                    ShapeSlot.SHAPE, ShapeSelection.geometry(SLAB),
                    ShapeSlot.COLLISION, ShapeSelection.nativeBinding(),
                    ShapeSlot.OCCLUSION, ShapeSelection.nativeBinding()
            )))
            .setParent(REGISTRY);

    public static final BlockReg<Block> NO_COLLISION = BlockReg.of(
                    "shape_binding_no_collision",
                    Block::new)
            .configure(props -> props.noCollission())
            .withShapePlan(plan(Map.of(
                    ShapeSlot.COLLISION, ShapeSelection.geometry(CUBE)
            )))
            .setParent(REGISTRY);

    public static final BlockReg<Block> NO_OCCLUSION = BlockReg.of(
                    "shape_binding_no_occlusion",
                    Block::new)
            .configure(props -> props.noOcclusion())
            .withShapePlan(plan(Map.of(
                    ShapeSlot.OCCLUSION, ShapeSelection.geometry(CUBE)
            )))
            .setParent(REGISTRY);

    public static final BlockReg<Block> FULL = BlockReg.of(
                    "shape_binding_full", Block::new)
            .withShapePlan(plan(Map.of(
                    ShapeSlot.SHAPE, ShapeSelection.geometry(CUBE),
                    ShapeSlot.COLLISION, ShapeSelection.alias(ShapeSlot.SHAPE)
            )))
            .setParent(REGISTRY);

    public static final BlockReg<Block> FACTORY = BlockReg.of(
                    "shape_binding_factory", Block::new)
            .withShapePlan(plan(Map.of(
                    ShapeSlot.SHAPE, ShapeSelection.geometry(SLAB)
            )))
            .setParent(REGISTRY);

    public static final BlockReg<NativeBlock> UNBOUND = BlockReg.of(
                    "shape_binding_unbound", NativeBlock::new)
            .setParent(REGISTRY);

    private ShapeBindingTestBlocks() {
    }

    private static BlockShapePlan plan(Map<ShapeSlot, ShapeSelection> selections) {
        return BlockShapePlan.builder()
                .state(new BlockStateKey(Map.of()), selections)
                .build();
    }

    public static final class NativeBlock extends Block {
        public VoxelShape nativeShapeVoxel = Shapes.box(0.0, 0.0, 0.0, 0.5, 0.5, 0.5);
        public VoxelShape nativeCollision = Shapes.box(0.0, 0.0, 0.0, 0.25, 1.0, 1.0);
        public VoxelShape nativeInteraction = Shapes.box(0.0, 0.0, 0.0, 0.75, 0.75, 1.0);
        public VoxelShape nativeOcclusion = Shapes.empty();
        public int nativeCalls;
        public BlockGetter nativeLevel;
        public BlockPos nativePos;
        public CollisionContext nativeContext;

        public NativeBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected VoxelShape getShape(
                BlockState state,
                BlockGetter level,
                BlockPos pos,
                CollisionContext context
        ) {
            record(level, pos, context);
            return nativeShapeVoxel;
        }

        @Override
        protected VoxelShape getCollisionShape(
                BlockState state,
                BlockGetter level,
                BlockPos pos,
                CollisionContext context
        ) {
            record(level, pos, context);
            return nativeCollision;
        }

        @Override
        protected VoxelShape getInteractionShape(
                BlockState state,
                BlockGetter level,
                BlockPos pos
        ) {
            record(level, pos, null);
            return nativeInteraction;
        }

        @Override
        protected VoxelShape getOcclusionShape(
                BlockState state,
                BlockGetter level,
                BlockPos pos
        ) {
            record(level, pos, null);
            return nativeOcclusion;
        }

        private void record(BlockGetter level, BlockPos pos, CollisionContext context) {
            nativeCalls++;
            nativeLevel = level;
            nativePos = pos;
            if (context != null) nativeContext = context;
        }
    }
}
