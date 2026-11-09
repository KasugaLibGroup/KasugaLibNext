package lib.kasuga.rendering.models.mc.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LayerLayoutTest {
    @Test void explosionPreservesMissingLayersAndNegativeCoordinates() {
        var layout = LayerLayout.ASSEMBLED.separation(2.5);
        assertEquals(0, layout.offset(-4, -4));
        assertEquals(5, layout.offset(-2, -4));
        assertEquals(20, layout.offset(4, -4));
    }

    @Test void selectingLayersDoesNotMoveTheRemainingGeometry() {
        var exploded = LayerLayout.ASSEMBLED.separation(3);
        assertEquals(exploded.offset(7, 2), exploded.range(7, 7).offset(7, 2));
        assertTrue(exploded.range(7, 7).includes(7));
        assertFalse(exploded.range(7, 7).includes(6));
        assertFalse(exploded.range(7, 7).includes(8));
    }

    @Test void assembledGeometryHasNoDisplacementOnAnyAxis() {
        for (var axis : LayerLayout.Axis.values()) {
            assertEquals(0, LayerLayout.ASSEMBLED.axis(axis).offset(27, -12));
        }
        assertEquals(-8, LayerLayout.Axis.X.coordinate(-8, 3, 12));
        assertEquals(3, LayerLayout.Axis.Y.coordinate(-8, 3, 12));
        assertEquals(12, LayerLayout.Axis.Z.coordinate(-8, 3, 12));
    }

    @Test void extremeCoordinatesDoNotOverflowIntegerSubtraction() {
        assertEquals(4294967295d, LayerLayout.ASSEMBLED.separation(1)
                .offset(Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    @Test void rejectsInvalidParametersAndNonFiniteResults() {
        assertThrows(IllegalArgumentException.class, () -> LayerLayout.ASSEMBLED.separation(-1));
        assertThrows(IllegalArgumentException.class, () -> LayerLayout.ASSEMBLED.separation(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> LayerLayout.ASSEMBLED.separation(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> LayerLayout.ASSEMBLED.range(2, 1));
        assertThrows(IllegalArgumentException.class, () -> LayerLayout.ASSEMBLED
                .separation(Double.MAX_VALUE).offset(2, 0));
    }
}
