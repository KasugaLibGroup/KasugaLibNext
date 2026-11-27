package lib.kasuga.test.registration.minecraft.block.shape;

import lib.kasuga.geometry.Box;
import lib.kasuga.geometry.Shape;
import lib.kasuga.registration.minecraft.block.shape.BlockShapeBindings;
import lib.kasuga.registration.minecraft.block.shape.BlockShapePlan;
import lib.kasuga.registration.minecraft.block.shape.BlockStateKey;
import lib.kasuga.registration.minecraft.block.shape.ShapeSelection;
import lib.kasuga.registration.minecraft.block.shape.ShapeSlot;
import lib.kasuga.test.registration.minecraft.block.shape.ShapeBindingTestBlocks.NativeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EphemeralTestServerProvider.class)
class BlockShapeBindingsTest {
    private static final BlockPos POS = new BlockPos(12, 5, -8);
    private static final CollisionContext CONTEXT = CollisionContext.empty();

    @Test
    void staticSlotsDriveOrdinaryQueriesAndInitializedCacheWithoutReconversion() {
        Shape frame = ShapeBindingTestBlocks.FRAME;
        NativeBlock block = ShapeBindingTestBlocks.STATIC.getEntry();
        BlockState state = block.defaultBlockState();
        block.nativeCalls = 0;

        VoxelShape ordinary = state.getShape(EmptyBlockGetter.INSTANCE, POS, CONTEXT);
        VoxelShape contextualCollision = state.getCollisionShape(
                EmptyBlockGetter.INSTANCE, POS, CONTEXT);
        VoxelShape cachedCollision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, POS);
        VoxelShape support = state.getBlockSupportShape(EmptyBlockGetter.INSTANCE, POS);
        VoxelShape occlusion = state.getOcclusionShape(EmptyBlockGetter.INSTANCE, POS);

        assertFalse(ordinary.isEmpty());
        assertSame(ordinary, contextualCollision);
        assertSame(ordinary, cachedCollision);
        assertSame(ordinary, support);
        assertSame(ordinary, occlusion);
        assertTrue(state.getVisualShape(EmptyBlockGetter.INSTANCE, POS, CONTEXT).isEmpty());
        assertEquals(block.nativeInteraction,
                state.getInteractionShape(EmptyBlockGetter.INSTANCE, POS));
        assertEquals(1, block.nativeCalls);
        assertSame(EmptyBlockGetter.INSTANCE, block.nativeLevel);
        assertEquals(POS, block.nativePos);

        assertSame(ordinary, state.getShape(EmptyBlockGetter.INSTANCE, POS, CONTEXT));
        assertSame(ordinary, state.getCollisionShape(EmptyBlockGetter.INSTANCE, POS, CONTEXT));
        assertEquals(1, block.nativeCalls);
    }

    @Test
    void missingAndNativePreserveOriginalContextWhileEmptyDoesNotDelegate() {
        NativeBlock block = ShapeBindingTestBlocks.NATIVE.getEntry();
        BlockState state = block.defaultBlockState();
        block.nativeCalls = 0;

        VoxelShape nativeShape = state.getShape(EmptyBlockGetter.INSTANCE, POS, CONTEXT);
        VoxelShape nativeCollision = state.getCollisionShape(
                EmptyBlockGetter.INSTANCE, POS, CONTEXT);
        VoxelShape nativeInteraction = state.getInteractionShape(EmptyBlockGetter.INSTANCE, POS);
        assertTrue(state.getVisualShape(EmptyBlockGetter.INSTANCE, POS, CONTEXT).isEmpty());

        assertEquals(block.nativeShapeVoxel, nativeShape);
        assertEquals(block.nativeCollision, nativeCollision);
        assertEquals(block.nativeInteraction, nativeInteraction);
        assertEquals(3, block.nativeCalls);
        assertSame(EmptyBlockGetter.INSTANCE, block.nativeLevel);
        assertEquals(POS, block.nativePos);
        assertSame(CONTEXT, block.nativeContext);
    }

    @Test
    void explicitNativeOcclusionDoesNotInheritTheBoundShapeSlot() {
        NativeBlock block = ShapeBindingTestBlocks.NATIVE_OVERRIDE.getEntry();
        BlockState state = block.defaultBlockState();
        block.nativeCalls = 0;

        assertFalse(state.getShape(EmptyBlockGetter.INSTANCE, POS, CONTEXT).isEmpty());
        assertTrue(state.getOcclusionShape(EmptyBlockGetter.INSTANCE, POS).isEmpty());
        assertEquals(1, block.nativeCalls);
    }

    @Test
    void noBindingRetainsNativeBehaviorAndNoCacheWasFabricated() {
        NativeBlock block = ShapeBindingTestBlocks.UNBOUND.getEntry();
        BlockState state = block.defaultBlockState();
        block.nativeCalls = 0;

        VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, POS, CONTEXT);
        VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, POS, CONTEXT);
        state.initCache();
        VoxelShape cached = state.getCollisionShape(EmptyBlockGetter.INSTANCE, POS);

        assertEquals(block.nativeShapeVoxel, shape);
        assertEquals(block.nativeCollision, collision);
        assertEquals(block.nativeCollision, cached);
        assertTrue(block.nativeCalls >= 3);
        assertFalse(BlockShapeBindings.isInstalled(block));
    }

    @Test
    void collisionAndOcclusionFlagsStillWinOverBoundGeometry() {
        Block noCollision = ShapeBindingTestBlocks.NO_COLLISION.getEntry();
        assertTrue(noCollision.defaultBlockState()
                .getCollisionShape(EmptyBlockGetter.INSTANCE, POS).isEmpty());

        Block noOcclusion = ShapeBindingTestBlocks.NO_OCCLUSION.getEntry();
        assertTrue(noOcclusion.defaultBlockState()
                .getOcclusionShape(EmptyBlockGetter.INSTANCE, POS).isEmpty());
    }

    @Test
    void cacheUsesEffectiveFullBlockAndFaceSupportMetadata() {
        Block block = ShapeBindingTestBlocks.FULL.getEntry();
        BlockState state = block.defaultBlockState();

        assertSame(Shapes.block(), state.getCollisionShape(EmptyBlockGetter.INSTANCE, POS));
        assertFalse(state.hasLargeCollisionShape());
        assertTrue(state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, POS));
        assertTrue(state.isFaceSturdy(
                EmptyBlockGetter.INSTANCE, POS, Direction.UP, SupportType.FULL));
    }

    @Test
    void cachedCollisionPreservesHoleAndStopsMovementAtItsBoundary() {
        BlockState state = ShapeBindingTestBlocks.STATIC.getEntry().defaultBlockState();
        VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, POS);

        assertEquals(null, collision.clip(
                new Vec3(0.5, 0.5, -1.0),
                new Vec3(0.5, 0.5, 1.0),
                BlockPos.ZERO
        ));
        double moved = Shapes.collide(
                Direction.Axis.X,
                new AABB(-1.0, 0.5, 0.0, 0.0, 0.75, 0.125),
                List.of(collision),
                1.0
        );
        assertEquals(0.0, moved, 0.0);
    }

    @Test
    void blockFactoryInstallsPlanBeforeStateCacheInitialization() {
        Block block = ShapeBindingTestBlocks.FACTORY.getEntry();

        assertTrue(BlockShapeBindings.isInstalled(block));
        assertFalse(block.defaultBlockState()
                .getShape(EmptyBlockGetter.INSTANCE, POS, CONTEXT).isEmpty());
        assertSame(block.defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, POS, CONTEXT),
                block.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, POS));
    }

    @Test
    void planRejectsUnknownStatesAndCyclicAliasesBeforeInstallation() {
        Block block = ShapeBindingTestBlocks.UNBOUND.getEntry();
        BlockStateKey unknown = new BlockStateKey(Map.of("missing", "true"));
        BlockShapePlan unknownPlan = BlockShapePlan.builder()
                .state(unknown, Map.of(
                        ShapeSlot.SHAPE,
                        ShapeSelection.geometry(Shape.of(Box.fromPixels(0, 0, 0, 16, 16, 16)))
                ))
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> BlockShapeBindings.install(block, unknownPlan));
        assertFalse(BlockShapeBindings.isInstalled(block));

        BlockShapePlan cyclicPlan = planFor(block.defaultBlockState(), Map.of(
                ShapeSlot.SHAPE, ShapeSelection.alias(ShapeSlot.COLLISION),
                ShapeSlot.COLLISION, ShapeSelection.alias(ShapeSlot.SHAPE)
        ));
        assertThrows(IllegalArgumentException.class,
                () -> BlockShapeBindings.install(block, cyclicPlan));
        assertFalse(BlockShapeBindings.isInstalled(block));
    }

    private static BlockShapePlan planFor(
            BlockState state,
            Map<ShapeSlot, ShapeSelection> selections
    ) {
        return BlockShapePlan.builder()
                .state(BlockStateKey.of(state), selections)
                .build();
    }

}
