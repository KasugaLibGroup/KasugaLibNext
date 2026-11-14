package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockDimensionsTest {
    @Test
    void reportsLengthWidthHeightAndLongestAxis() {
        var dimensions = new BlockDimensions(18, 5, 4);

        assertEquals(18, dimensions.length());
        assertEquals(5, dimensions.width());
        assertEquals(4, dimensions.height());
        assertEquals(18, dimensions.longestAxis());
    }

    @Test
    void measuresTheTightBlockExtentRatherThanTheBlockCount() {
        var dimensions = BlockDimensions.fromBlocks(List.of(
                new BlockPos(0, 2, -3),
                new BlockPos(4, 4, 1))).orElseThrow();

        assertEquals(5, dimensions.length());
        assertEquals(5, dimensions.width());
        assertEquals(3, dimensions.height());
    }

    @Test
    void translationDoesNotChangeDimensions() {
        var source = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 2, 4));
        var translated = List.of(new BlockPos(100, -20, 30), new BlockPos(101, -18, 34));

        assertEquals(BlockDimensions.fromBlocks(source), BlockDimensions.fromBlocks(translated));
    }

    @Test
    void emptyBlocksHaveNoDimensions() {
        assertEquals(Optional.empty(), BlockDimensions.fromBlocks(List.of()));
    }

    @Test
    void rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new BlockDimensions(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BlockDimensions(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BlockDimensions(1, 1, 0));
    }
}
