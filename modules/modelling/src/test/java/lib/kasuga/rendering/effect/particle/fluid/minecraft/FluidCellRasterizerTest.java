package lib.kasuga.rendering.effect.particle.fluid.minecraft;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidCellRasterizerTest {

    @Test
    void alignsAWorldBlockToCellCentersInsteadOfExpandingEveryIntersection() {
        BitSet cells = new BitSet();

        FluidCellRasterizer.markBox(
                cells, 18,
                0.5, 0.5, 0.5,
                7.0 / 12.0, 0.625, 7.0 / 12.0
        );

        assertTrue(cells.get(index(9, 9, 9, 18)));
        assertTrue(cells.get(index(9, 10, 9, 18)));
        assertEquals(2, cells.cardinality());
    }

    @Test
    void thinCollisionShapeFallsBackToNearestCell() {
        BitSet cells = new BitSet();

        FluidCellRasterizer.markBox(
                cells, 18,
                0.5, 0.5, 0.5,
                0.51, 0.51, 0.51
        );

        assertEquals(1, cells.cardinality());
        assertTrue(cells.get(index(9, 9, 9, 18)));
    }

    @Test
    void boxOutsideSimulationVolumeDoesNotMarkCells() {
        BitSet cells = new BitSet();

        FluidCellRasterizer.markBox(cells, 18, 1.1, 0, 0, 1.2, 1, 1);

        assertTrue(cells.isEmpty());
    }

    private static int index(int x, int y, int z, int resolution) {
        return x + resolution * (y + resolution * z);
    }
}
