package lib.kasuga.geometry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryBudgetTest {
    @Test
    void depthBudgetRejectsNestedOperationBeforeEvaluation() {
        GeometryBudget budget = new GeometryBudget(0, 1_000, 16);
        Shape left = Shape.of(box(0, 0, 0, 1, 1, 1));
        Shape right = Shape.of(box(1, 0, 0, 2, 1, 1));

        GeometryBudgetException failure = assertThrows(GeometryBudgetException.class,
                () -> left.union(budget, right));

        assertEquals(GeometryBudget.Limit.DEPTH, failure.limit());
    }

    @Test
    void workBudgetRejectsBeforeGridAndBoundaryAllocation() {
        GeometryBudget budget = new GeometryBudget(64, 5, 16);

        GeometryBudgetException failure = assertThrows(GeometryBudgetException.class,
                () -> Shape.of(List.of(box(0, 0, 0, 1, 1, 1)), budget));

        assertEquals(GeometryBudget.Limit.WORK, failure.limit());
        assertTrue(failure.requested() > failure.maximum());
    }

    @Test
    void fragmentBudgetRejectsDisconnectedResult() {
        GeometryBudget budget = new GeometryBudget(64, 1_000_000, 1);
        Shape left = Shape.of(box(0, 0, 0, 1, 1, 1));
        Shape right = Shape.of(box(2, 0, 0, 3, 1, 1));

        GeometryBudgetException failure = assertThrows(GeometryBudgetException.class,
                () -> left.union(budget, right));

        assertEquals(GeometryBudget.Limit.FRAGMENTS, failure.limit());
    }

    @Test
    void operationDepthTracksNestedShapeGraphs() {
        Shape base = Shape.of(box(0, 0, 0, 4, 1, 1));
        Shape cut = Shape.of(box(1, 0, 0, 2, 1, 1));
        Shape nested = base.difference(cut).union(base);

        assertEquals(2, nested.depth());
    }

    @Test
    void invalidBudgetValuesAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new GeometryBudget(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GeometryBudget(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new GeometryBudget(1, 1, 0));
    }

    private static Box box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
