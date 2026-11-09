package lib.kasuga.rendering.models.mc.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchematicPackingTest {
    @Test void decodesBitEntriesAcrossWordBoundaries() {
        // Explicit 5-bit fixture: palette index 23 starts at bit 60 and straddles two longs.
        long[] packed = {7L | (23L << 60), (23L >>> 4) | (11L << 1)};
        assertEquals(7, SchematicModelData.unpack(packed, 0, 5));
        assertEquals(23, SchematicModelData.unpack(packed, 12, 5));
        assertEquals(11, SchematicModelData.unpack(packed, 13, 5));
    }

    @Test void unsignedLongAndMinimumPaletteWidthArePreserved() {
        assertEquals(3, SchematicModelData.unpack(new long[]{-1L}, 31, 2));
        assertEquals(0, SchematicModelData.unpack(new long[]{0}, 0, 2));
        assertEquals(1, SchematicModelData.unpack(new long[]{4}, 1, 2));
    }

    @Test void truncatedAndInvalidArraysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SchematicModelData.unpack(new long[]{0}, 12, 5));
        assertThrows(IllegalArgumentException.class, () -> SchematicModelData.unpack(new long[0], 0, 2));
        assertThrows(IllegalArgumentException.class, () -> SchematicModelData.unpack(new long[]{0}, -1, 2));
        assertThrows(IllegalArgumentException.class, () -> SchematicModelData.unpack(new long[]{0}, 0, 1));
    }

    @Test void negativeRegionDimensionsStartAtTheInclusiveMinimumCorner() {
        assertEquals(-4, SchematicModelData.negativeOffset(-5));
        assertEquals(0, SchematicModelData.negativeOffset(-1));
        assertEquals(0, SchematicModelData.negativeOffset(5));
    }
}
