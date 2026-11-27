package lib.kasuga.geometry;

final class GeometryWork {
    private final GeometryBudget budget;
    private long used;

    GeometryWork(GeometryBudget budget) {
        this.budget = budget;
    }

    void ensureAdditional(long units) {
        if (units < 0) {
            throw new IllegalArgumentException("work units must be non-negative");
        }
        long requested;
        try {
            requested = Math.addExact(used, units);
        } catch (ArithmeticException overflow) {
            requested = Long.MAX_VALUE;
        }
        budget.checkWork(requested);
    }

    void advance(long units) {
        ensureAdditional(units);
        used += units;
    }

    long used() {
        return used;
    }
}
