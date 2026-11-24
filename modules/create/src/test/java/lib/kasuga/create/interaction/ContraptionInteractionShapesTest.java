package lib.kasuga.create.interaction;

import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ContraptionInteractionShapesTest {
    @Test
    void localViewReadsCurrentCapturedStateAndNeverQueriesWorldOwnership() {
        var contraption = new TestContraption();
        var pos = new BlockPos(-2, 3, 4);
        var lever = Blocks.LEVER.defaultBlockState();
        var captured = new CapturedBlockEntity(pos, lever);
        contraption.getBlocks().put(pos, new StructureBlockInfo(pos, lever, null));
        contraption.presentBlockEntities.put(pos, captured);
        var dimensions = new WorldDimensions();
        var view = new ContraptionBlockView(contraption, dimensions);

        assertSame(lever, view.getBlockState(pos));
        assertSame(captured, view.getBlockEntity(pos));
        assertTrue(view.getBlockState(pos.above()).isAir());
        assertNull(view.getBlockEntity(pos.above()));
        assertEquals(128, view.getHeight());
        assertEquals(-32, view.getMinBuildHeight());

        var powered = lever.setValue(BlockStateProperties.POWERED, true);
        contraption.getBlocks().put(pos, new StructureBlockInfo(pos, powered, null));
        assertSame(powered, view.getBlockState(pos), "The view must remain live across Create packets");
        contraption.getBlocks().put(pos, new StructureBlockInfo(pos, Blocks.WATER.defaultBlockState(), null));
        assertSame(Blocks.WATER.defaultBlockState().getFluidState(), view.getFluidState(pos));
        contraption.getBlocks().remove(pos);
        assertNull(view.getBlockEntity(pos), "A stale captured BE is not an occupied local cell");
    }

    @Test
    void ordinaryBlocksKeepTheirOriginalShapeQuery() {
        var calls = new AtomicInteger();
        var original = Shapes.box(0.2, 0.3, 0.4, 0.5, 0.6, 0.7);
        var result = ContraptionInteractionShapes.shape(
                Blocks.STONE.defaultBlockState(), new TestContraption(), new WorldDimensions(),
                new BlockPos(8, 4, -2), () -> {
                    calls.incrementAndGet();
                    return original;
                });
        assertSame(original, result);
        assertEquals(1, calls.get());
    }

    @Test
    void extendedPickingKeepsAnOrdinaryNativeHitWhenThereAreNoOptedInShapes() {
        var contraption = new TestContraption();
        var pos = new BlockPos(2, 3, 4);
        contraption.getBlocks().put(pos, new StructureBlockInfo(pos, Blocks.STONE.defaultBlockState(), null));
        var hit = new BlockHitResult(new Vec3(2, 3.5, 4.5), Direction.WEST, pos, false);
        assertSame(hit, ContraptionInteractionShapes.pick(contraption, new WorldDimensions(),
                new Vec3(0, 3.5, 4.5), new Vec3(8, 3.5, 4.5), hit));
        assertNull(ContraptionInteractionShapes.pick(contraption, new WorldDimensions(),
                new Vec3(0, 3.5, 4.5), new Vec3(8, 3.5, 4.5), null));
    }

    private static final class TestContraption extends Contraption {
        @Override public boolean assemble(Level level, BlockPos pos) { return false; }
        @Override public boolean canBeStabilized(Direction facing, BlockPos pos) { return false; }
        @Override public ContraptionType getType() { return null; }
    }

    private static final class CapturedBlockEntity extends BlockEntity {
        CapturedBlockEntity(BlockPos pos, BlockState state) { super(null, pos, state); }
        @Override public boolean isValidBlockState(BlockState state) { return true; }
    }

    private static final class WorldDimensions implements BlockGetter {
        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            throw new AssertionError("Local ownership must not query a real-world BE");
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            throw new AssertionError("Local state must not query a real-world block");
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            throw new AssertionError("Local fluid must come from the captured block");
        }
        @Override public int getHeight() { return 128; }
        @Override public int getMinBuildHeight() { return -32; }
    }
}
