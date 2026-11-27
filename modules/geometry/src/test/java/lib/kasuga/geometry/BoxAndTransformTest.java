package lib.kasuga.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoxAndTransformTest {
    @Test
    void pixelBoxesUseFloatOnceAndExposeExactBlockVolume() {
        Box box = Box.fromPixels(4.0, 0.0, 8.0, 252.0, 8.0, 248.0);
        Box fractional = Box.fromPixels(0.25, 0.0, 0.5, 15.75, 0.5, 15.5);

        assertEquals(0.25f, box.minX());
        assertEquals(15.75f, box.maxX());
        assertEquals(116.25, box.volume(), 0.0);
        assertEquals(116.25 / 4096.0, fractional.volume(), 1.0e-12);
        assertTrue(fractional.contains(new Point(0.25f, 0.0f, 0.5f)));
        assertFalse(fractional.contains(1.0f, 0.0f, 0.5f));
    }

    @Test
    void floatNarrowingAndPixelToBlockUnderflowRejectDegenerateBoxes() {
        GeometryException narrowed = assertThrows(GeometryException.class,
                () -> Box.fromPixels(32.0, 0.0, 0.0, 32.0000001, 1.0, 1.0));
        GeometryException pixelUnderflow = assertThrows(GeometryException.class,
                () -> Box.fromPixels(0.0, 0.0, 0.0, 1.0e-50, 1.0, 1.0));
        GeometryException blockUnderflow = assertThrows(GeometryException.class,
                () -> Box.fromPixels(0.0, 0.0, 0.0, 1.0e-45, 1.0, 1.0));

        assertTrue(narrowed.getMessage().contains("positive"));
        assertTrue(pixelUnderflow.getMessage().contains("positive"));
        assertTrue(blockUnderflow.getMessage().contains("positive"));
    }

    @Test
    void rawRangeAndNonFiniteInputsAreRejectedBeforeNarrowing() {
        assertThrows(GeometryException.class,
                () -> Box.fromPixels(-65_537.0, 0.0, 0.0, 1.0, 1.0, 1.0));
        assertThrows(GeometryException.class,
                () -> Box.fromPixels(Double.NaN, 0.0, 0.0, 1.0, 1.0, 1.0));
        assertThrows(GeometryException.class,
                () -> new Box(0, 0, 0, 0, 1, 1));
    }

    @Test
    void scaleUsesPivotBeforeRotationAndTranslation() {
        Shape input = Shape.of(Box.fromPixels(0, 0, 0, 16, 16, 16));
        Transform transform = Transform.fromPixels(
                2.0, 1.0, 1.0,
                0,
                8.0, 0.0, 8.0,
                16.0, 0.0, 0.0
        );

        Shape result = transform.apply(input);

        assertBox(result.boxes().getFirst(), 0.5f, 0.0f, 0.0f, 2.5f, 1.0f, 1.0f);
    }

    @Test
    void quarterTurnUsesTheBlockstateYConvention() {
        Shape input = Shape.of(box(0, 0, 0, 0.5f, 1, 0.25f));
        Transform transform = Transform.fromPixels(
                1.0, 1.0, 1.0,
                90,
                8.0, 0.0, 8.0,
                0.0, 0.0, 0.0
        );

        Shape result = transform.apply(input);

        assertBox(result.boxes().getFirst(), 0.75f, 0.0f, 0.0f, 1.0f, 1.0f, 0.5f);
    }

    @Test
    void fourQuarterTurnsRestoreTheSameShapeAndBoundary() {
        Shape input = Shape.of(
                box(0.125f, 0.25f, 0.375f, 0.625f, 0.75f, 0.875f),
                box(0.75f, 0.25f, 0.375f, 0.875f, 0.75f, 0.625f)
        );
        Shape turned = input;
        for (int index = 0; index < 4; index++) {
            turned = turned.transform(Transform.fromPixels(
                    1, 1, 1, 90,
                    8, 0, 8,
                    0, 0, 0
            ));
        }

        assertEquals(input.volume(), turned.volume(), 0.0);
        assertEquals(input.bounds().asBox().orElseThrow(), turned.bounds().asBox().orElseThrow());
        assertTrue(sameBoxes(input.boxes(), turned.boxes()));
    }

    @Test
    void identityLeavesAllActualResultBoxesAndBoundaryEdgesIntact() {
        Shape input = Shape.of(
                box(0, 0, 0, 2, 1, 1),
                box(3, 0, 0, 4, 1, 1)
        );

        Shape result = input.transform(Transform.identity());

        assertEquals(input.boxes(), result.boxes());
        assertEquals(input.boundaryEdges(), result.boundaryEdges());
    }

    @Test
    void invalidOrDegenerateTransformsAreRejected() {
        assertThrows(GeometryException.class,
                () -> Transform.fromPixels(1, 0, 1, 0, 8, 0, 8, 0, 0, 0));
        assertThrows(GeometryException.class,
                () -> Transform.fromPixels(1, 1, 1, 45, 8, 0, 8, 0, 0, 0));

        Shape tiny = Shape.of(box(
                Math.nextUp(0.5f), 0, 0.5f,
                Math.nextUp(Math.nextUp(0.5f)), 1, Math.nextUp(0.5f)
        ));
        Transform collapses = Transform.ofBlocks(
                Float.MIN_VALUE, 1, 1,
                0,
                0.5f, 0, 0.5f,
                0, 0, 0
        );
        assertThrows(GeometryException.class, () -> collapses.apply(tiny));
    }

    private static Box box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void assertBox(
            Box box,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ
    ) {
        assertEquals(minX, box.minX());
        assertEquals(minY, box.minY());
        assertEquals(minZ, box.minZ());
        assertEquals(maxX, box.maxX());
        assertEquals(maxY, box.maxY());
        assertEquals(maxZ, box.maxZ());
    }

    private static boolean sameBoxes(java.util.List<Box> left, java.util.List<Box> right) {
        return left.size() == right.size() && left.stream().allMatch(box ->
                right.stream().anyMatch(candidate -> sameBox(box, candidate)));
    }

    private static boolean sameBox(Box left, Box right) {
        return Float.compare(left.minX(), right.minX()) == 0
                && Float.compare(left.minY(), right.minY()) == 0
                && Float.compare(left.minZ(), right.minZ()) == 0
                && Float.compare(left.maxX(), right.maxX()) == 0
                && Float.compare(left.maxY(), right.maxY()) == 0
                && Float.compare(left.maxZ(), right.maxZ()) == 0;
    }
}
