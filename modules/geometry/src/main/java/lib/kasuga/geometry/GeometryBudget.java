package lib.kasuga.geometry;

import java.util.Objects;

/**
 * Immutable resource limits for shape normalization and CSG.
 *
 * <p>Depth counts nested shape operations. Work is a deterministic estimate of compressed-grid and
 * boundary processing. Fragments count the actual non-overlapping boxes in a result.</p>
 */
public final class GeometryBudget {
    public static final int DEFAULT_MAX_DEPTH = 64;
    public static final long DEFAULT_MAX_WORK = 4_000_000L;
    public static final int DEFAULT_MAX_FRAGMENTS = 4096;

    private static final GeometryBudget DEFAULT =
            new GeometryBudget(DEFAULT_MAX_DEPTH, DEFAULT_MAX_WORK, DEFAULT_MAX_FRAGMENTS);

    private final int maxDepth;
    private final long maxWork;
    private final int maxFragments;

    public GeometryBudget(int maxDepth, long maxWork, int maxFragments) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be non-negative");
        }
        if (maxWork <= 0) {
            throw new IllegalArgumentException("maxWork must be positive");
        }
        if (maxFragments <= 0) {
            throw new IllegalArgumentException("maxFragments must be positive");
        }
        this.maxDepth = maxDepth;
        this.maxWork = maxWork;
        this.maxFragments = maxFragments;
    }

    public static GeometryBudget standard() {
        return DEFAULT;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public long maxWork() {
        return maxWork;
    }

    public int maxFragments() {
        return maxFragments;
    }

    void checkDepth(int depth) {
        if (depth > maxDepth) {
            throw new GeometryBudgetException(Limit.DEPTH, depth, maxDepth, "shape operation");
        }
    }

    void checkFragments(int fragments) {
        if (fragments > maxFragments) {
            throw new GeometryBudgetException(Limit.FRAGMENTS, fragments, maxFragments,
                    "shape result");
        }
    }

    GeometryWork startWork() {
        return new GeometryWork(this);
    }

    void checkWork(long requested) {
        if (requested > maxWork) {
            throw new GeometryBudgetException(Limit.WORK, requested, maxWork, "shape operation");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GeometryBudget budget)) return false;
        return maxDepth == budget.maxDepth
                && maxWork == budget.maxWork
                && maxFragments == budget.maxFragments;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxDepth, maxWork, maxFragments);
    }

    @Override
    public String toString() {
        return "GeometryBudget[maxDepth=" + maxDepth + ", maxWork=" + maxWork
                + ", maxFragments=" + maxFragments + ']';
    }

    public enum Limit {
        DEPTH,
        WORK,
        FRAGMENTS
    }
}
