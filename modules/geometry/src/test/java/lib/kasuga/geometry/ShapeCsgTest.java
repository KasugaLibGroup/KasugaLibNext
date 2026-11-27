package lib.kasuga.geometry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeCsgTest {
    @Test
    void unionPreservesVolumeAndRemovesOverlap() {
        Shape left = Shape.of(box(0, 0, 0, 2, 1, 1));
        Shape right = Shape.of(box(1, 0, 0, 3, 1, 1));

        Shape result = left.union(right);

        assertEquals(3.0, result.volume(), 0.0);
        assertTrue(result.contains(0.25f, 0.5f, 0.5f));
        assertTrue(result.contains(1.5f, 0.5f, 0.5f));
        assertTrue(result.contains(2.75f, 0.5f, 0.5f));
        assertFalse(result.contains(3.1f, 0.5f, 0.5f));
        assertEquals(1, result.boxes().size());
    }

    @Test
    void intersectionDropsTouchingAndZeroVolumeRegions() {
        Shape left = Shape.of(box(0, 0, 0, 1, 1, 1));
        Shape touching = Shape.of(box(1, 0, 0, 2, 1, 1));
        Shape overlapping = Shape.of(box(0.5f, 0.25f, 0.25f, 1.5f, 1.25f, 1.25f));

        assertTrue(left.intersection(touching).isEmpty());
        Shape result = left.intersection(overlapping);
        assertEquals(0.5 * 0.75 * 0.75, result.volume(), 0.0);
        assertEquals(Box.class, result.boxes().getFirst().getClass());
    }

    @Test
    void differenceUsesLeftFoldAndLeavesExplicitEmptyResultsValid() {
        Shape base = Shape.of(box(0, 0, 0, 4, 1, 1));
        Shape firstCut = Shape.of(box(1, 0, 0, 3, 1, 1));
        Shape secondCut = Shape.of(box(2, 0, 0, 4, 1, 1));

        Shape leftFold = base.difference(firstCut, secondCut);
        Shape nested = base.difference(firstCut.difference(secondCut));
        Shape empty = base.difference(base);

        assertEquals(1.0, leftFold.volume(), 0.0);
        assertEquals(3.0, nested.volume(), 0.0);
        assertTrue(empty.isEmpty());
        assertTrue(empty.bounds().isEmpty());
        assertTrue(empty.boundaryEdges().isEmpty());
    }

    @Test
    void nestedOperationsMatchTheirSetDefinition() {
        Shape left = Shape.of(box(0, 0, 0, 2, 1, 1));
        Shape right = Shape.of(box(1, 0, 0, 3, 1, 1));
        Shape middle = Shape.of(box(1, 0, 0, 2, 1, 1));
        Shape nested = left.union(right).difference(left.intersection(middle));

        assertEquals(2.0, nested.volume(), 0.0);
        assertFalse(nested.contains(1.5f, 0.5f, 0.5f));
        assertTrue(nested.contains(0.5f, 0.5f, 0.5f));
        assertTrue(nested.contains(2.5f, 0.5f, 0.5f));
    }

    @Test
    void holesSurviveAsAbsentRegionsRatherThanTotalBounds() {
        Shape outer = Shape.of(box(0, 0, 0, 4, 4, 1));
        Shape opening = Shape.of(box(1, 1, 0, 3, 3, 1));

        Shape frame = outer.difference(opening);

        assertEquals(12.0, frame.volume(), 0.0);
        assertEquals(16.0, frame.bounds().volume(), 0.0);
        assertFalse(frame.contains(2.0f, 2.0f, 0.5f));
        assertTrue(frame.contains(1.0f, 2.0f, 0.5f));
        assertTrue(frame.contains(0.5f, 0.5f, 0.5f));
        assertTrue(frame.boxes().size() > 1);
        assertDisjoint(frame.boxes());
    }

    @Test
    void disconnectedRegionsRemainSeparateWhileBoundsSpanTheGap() {
        Shape shape = Shape.of(
                box(0, 0, 0, 1, 1, 1),
                box(10, 0, 0, 11, 1, 1)
        );

        assertEquals(2.0, shape.volume(), 0.0);
        assertEquals(11.0, shape.bounds().volume(), 0.0);
        assertFalse(shape.contains(5.0f, 0.5f, 0.5f));
        assertEquals(2, shape.boxes().size());
        assertDisjoint(shape.boxes());
    }

    @Test
    void emptyIdentityAndAbsorbingBehaviorsAreExplicit() {
        Shape empty = Shape.empty();
        Shape solid = Shape.of(box(0, 0, 0, 1, 1, 1));

        assertEquals(solid, empty.union(solid));
        assertTrue(empty.intersection(solid).isEmpty());
        assertTrue(solid.intersection(empty).isEmpty());
        assertTrue(empty.difference(solid).isEmpty());
        assertEquals(solid, solid.difference(empty));
    }

    @Test
    void resultBoxesAreActualNonOverlappingFragments() {
        Shape shape = Shape.of(
                box(0, 0, 0, 2, 2, 2),
                box(1, 0, 0, 3, 1, 1),
                box(4, 0, 0, 5, 1, 1)
        );

        assertEquals(8.0 + (1.0 * 1.0 * 1.0) + 1.0, shape.volume(), 0.0);
        assertTrue(shape.boxes().size() >= 2);
        assertDisjoint(shape.boxes());
    }

    @Test
    void mixedOperationsMatchAnIndependentUnitCubeOracle() {
        Random random = new Random(0x5A17C5L);
        for (int iteration = 0; iteration < 40; iteration++) {
            Box firstBox = randomIntegerBox(random);
            boolean[][][] expected = occupancy(firstBox);
            Shape shape = Shape.of(firstBox);

            for (int operation = 0; operation < 5; operation++) {
                Box nextBox = randomIntegerBox(random);
                int selected = random.nextInt(3);
                if (selected == 0) {
                    shape = shape.union(Shape.of(nextBox));
                    union(expected, occupancy(nextBox));
                } else if (selected == 1) {
                    shape = shape.intersection(Shape.of(nextBox));
                    intersect(expected, occupancy(nextBox));
                } else {
                    shape = shape.difference(Shape.of(nextBox));
                    difference(expected, occupancy(nextBox));
                }
            }

            long occupiedCubes = 0;
            for (int x = 0; x < expected.length; x++) {
                for (int y = 0; y < expected[x].length; y++) {
                    for (int z = 0; z < expected[x][y].length; z++) {
                        boolean expectedMember = expected[x][y][z];
                        assertEquals(expectedMember, shape.contains(x + 0.5f, y + 0.5f, z + 0.5f),
                                "membership at " + x + ',' + y + ',' + z + " in iteration " + iteration);
                        if (expectedMember) occupiedCubes++;
                    }
                }
            }
            assertEquals(occupiedCubes, shape.volume(), 0.0,
                    "volume in iteration " + iteration);
            assertDisjoint(shape.boxes());
        }
    }

    @Test
    void finalBoundaryMergesAdjacentBoxesWithoutAnInternalSeam() {
        Shape shape = Shape.of(
                box(0, 0, 0, 1, 1, 1),
                box(1, 0, 0, 2, 1, 1)
        );

        assertEquals(1, shape.boxes().size());
        assertEquals(12, shape.boundaryEdges().size());
        assertFalse(shape.boundaryEdges().contains(
                new BoundaryEdge(1, 0, 0, 1, 1, 0)
        ));
    }

    @Test
    void holeBoundaryContainsOuterAndOpeningEdgesOnly() {
        Shape shape = Shape.of(box(0, 0, 0, 4, 4, 1))
                .difference(Shape.of(box(1, 1, 0, 3, 3, 1)));

        assertTrue(shape.boundaryEdges().contains(
                new BoundaryEdge(1, 1, 0, 3, 1, 0)
        ));
        assertTrue(shape.boundaryEdges().contains(
                new BoundaryEdge(1, 1, 1, 1, 3, 1)
        ));
        assertFalse(shape.boundaryEdges().contains(
                new BoundaryEdge(2, 0, 0, 2, 4, 0)
        ));
        assertEquals(24, shape.boundaryEdges().size());
    }

    private static Box box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static Box randomIntegerBox(Random random) {
        int minX = random.nextInt(7);
        int minY = random.nextInt(7);
        int minZ = random.nextInt(7);
        return box(
                minX, minY, minZ,
                minX + 1 + random.nextInt(8 - minX),
                minY + 1 + random.nextInt(8 - minY),
                minZ + 1 + random.nextInt(8 - minZ)
        );
    }

    private static boolean[][][] occupancy(Box box) {
        boolean[][][] cells = new boolean[8][8][8];
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                for (int z = 0; z < 8; z++) {
                    cells[x][y][z] = box.contains(x + 0.5f, y + 0.5f, z + 0.5f);
                }
            }
        }
        return cells;
    }

    private static void union(boolean[][][] target, boolean[][][] other) {
        transform(target, other, (left, right) -> left || right);
    }

    private static void intersect(boolean[][][] target, boolean[][][] other) {
        transform(target, other, (left, right) -> left && right);
    }

    private static void difference(boolean[][][] target, boolean[][][] other) {
        transform(target, other, (left, right) -> left && !right);
    }

    private static void transform(
            boolean[][][] target,
            boolean[][][] other,
            java.util.function.BiFunction<Boolean, Boolean, Boolean> operation
    ) {
        for (int x = 0; x < target.length; x++) {
            for (int y = 0; y < target[x].length; y++) {
                for (int z = 0; z < target[x][y].length; z++) {
                    target[x][y][z] = operation.apply(target[x][y][z], other[x][y][z]);
                }
            }
        }
    }

    private static void assertDisjoint(List<Box> boxes) {
        for (int left = 0; left < boxes.size(); left++) {
            for (int right = left + 1; right < boxes.size(); right++) {
                String message = boxes.get(left) + " overlaps " + boxes.get(right);
                assertFalse(boxes.get(left).overlapsVolume(boxes.get(right)), message);
            }
        }
    }
}
