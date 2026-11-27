package lib.kasuga.create.interaction;

import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.Contraption;
import lib.kasuga.geometry.Box;
import lib.kasuga.geometry.Shape;
import lib.kasuga.geometry.Transform;
import lib.kasuga.registration.minecraft.block.shape.BlockShapeBindings;
import lib.kasuga.registration.minecraft.block.shape.BlockShapePlan;
import lib.kasuga.registration.minecraft.block.shape.BlockStateKey;
import lib.kasuga.registration.minecraft.block.shape.ShapeSelection;
import lib.kasuga.registration.minecraft.block.shape.ShapeSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContraptionDataDrivenShapesTest {
    private final List<Block> installed = new ArrayList<>();

    @AfterEach
    void uninstallPlans() {
        installed.forEach(BlockShapeBindings::uninstall);
    }

    @Test
    void compiledTransformStaysInCapturedLocalCoordinatesAndIsConvertedOnce() {
        Shape authored = Shape.of(Box.fromPixels(0, 0, 0, 16, 16, 16));
        Shape transformed = Transform.fromPixels(
                2, 1, 1,
                0,
                8, 0, 8,
                16, 0, 0
        ).apply(authored);
        var block = install(transformed);
        BlockState state = block.defaultBlockState();
        BlockPos localPos = new BlockPos(10, -4, 20);
        AtomicInteger nativeQueries = new AtomicInteger();

        VoxelShape shape = ContraptionInteractionShapes.shape(
                state,
                new TestContraption(),
                new WorldDimensions(),
                localPos,
                () -> {
                    nativeQueries.incrementAndGet();
                    return net.minecraft.world.phys.shapes.Shapes.empty();
                }
        );
        BlockHitResult hit = shape.clip(
                new Vec3(localPos.getX() - 1, localPos.getY() + 0.5, localPos.getZ() + 0.5),
                new Vec3(localPos.getX() + 4, localPos.getY() + 0.5, localPos.getZ() + 0.5),
                localPos
        );
        assertNotNull(hit);

        var box = shape.toAabbs().getFirst();
        assertEquals(0.5, box.minX, 0.0);
        assertEquals(2.5, box.maxX, 0.0);
        assertEquals(1.0, box.maxY, 0.0);
        assertEquals(localPos.getX() + 0.5, hit.getLocation().x, 0.0);
        assertEquals(0, nativeQueries.get());
    }

    @Test
    void extendedPickUsesTheOwnerAnchorForHolesAndDisconnectedLocalGeometry() {
        Shape frame = Shape.of(Box.fromPixels(0, 0, 0, 32, 16, 2))
                .difference(Shape.of(Box.fromPixels(4, 0, 0, 12, 12, 2)))
                .union(Shape.of(Box.fromPixels(18, 0, 20, 20, 16, 22)));
        var block = install(frame);
        BlockPos localPos = new BlockPos(10, 0, 0);
        TestContraption contraption = new TestContraption();
        contraption.getBlocks().put(
                localPos,
                new StructureBlockInfo(localPos, block.defaultBlockState(), null)
        );

        assertNull(ContraptionInteractionShapes.pick(
                contraption,
                new WorldDimensions(),
                new Vec3(10.5, 0.5, -1),
                new Vec3(10.5, 0.5, 1),
                null
        ), "The selected output hole must remain open");

        BlockHitResult frameHit = ContraptionInteractionShapes.pick(
                contraption,
                new WorldDimensions(),
                new Vec3(11, 0.5, -1),
                new Vec3(11, 0.5, 1),
                null
        );
        assertNotNull(frameHit);
        assertEquals(localPos, frameHit.getBlockPos());

        BlockHitResult islandHit = ContraptionInteractionShapes.pick(
                contraption,
                new WorldDimensions(),
                new Vec3(11.1875, 0.5, 1),
                new Vec3(11.1875, 0.5, 2),
                null
        );
        assertNotNull(islandHit);
        assertEquals(new Vec3(11.1875, 0.5, 1.25), islandHit.getLocation());
        assertEquals(localPos, islandHit.getBlockPos());
    }

    private Block install(Shape shape) {
        var block = Blocks.STONE;
        BlockShapeBindings.install(block, BlockShapePlan.builder()
                .state(
                        BlockStateKey.of(block.defaultBlockState()),
                        Map.of(ShapeSlot.SHAPE, ShapeSelection.geometry(shape))
                )
                .build());
        installed.add(block);
        assertTrue(BlockShapeBindings.isInstalled(block));
        return block;
    }

    private static final class TestContraption extends Contraption {
        @Override
        public boolean assemble(Level level, BlockPos pos) {
            return false;
        }

        @Override
        public boolean canBeStabilized(Direction facing, BlockPos pos) {
            return false;
        }

        @Override
        public ContraptionType getType() {
            return null;
        }
    }

    private static final class WorldDimensions implements BlockGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            throw new AssertionError("Local queries must not use the real world");
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            throw new AssertionError("Local queries must not use the real world");
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            throw new AssertionError("Local queries must not use the real world");
        }

        @Override
        public int getHeight() {
            return 128;
        }

        @Override
        public int getMinBuildHeight() {
            return -32;
        }
    }
}
