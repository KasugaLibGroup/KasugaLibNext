package lib.kasuga.geometry;

/** A geometry operation exceeded one of its predeclared limits. */
public final class GeometryBudgetException extends GeometryException {
    private final GeometryBudget.Limit limit;
    private final long requested;
    private final long maximum;

    GeometryBudgetException(GeometryBudget.Limit limit, long requested, long maximum, String operation) {
        super(operation + " exceeded " + limit.name().toLowerCase() + " budget: requested "
                + requested + ", maximum " + maximum);
        this.limit = limit;
        this.requested = requested;
        this.maximum = maximum;
    }

    public GeometryBudget.Limit limit() {
        return limit;
    }

    public long requested() {
        return requested;
    }

    public long maximum() {
        return maximum;
    }
}
