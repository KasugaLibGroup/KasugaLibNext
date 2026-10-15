package lib.kasuga.formula.logic.infrastructure;

/**
 * Common contract for logical elements: comparisons, boolean operations,
 * boolean literals and logical numerics all implement it.
 *
 * <p>It mirrors {@link lib.kasuga.formula.compute.infrastructure.Formula} on the
 * arithmetic side, except that {@link #getResult()} returns a boolean.
 */
public interface LogicalData {
    /**
     * Evaluates this element.
     *
     * @return the boolean result
     */
    boolean getResult();
    /**
     * Whether this element is atomic (a logical line is atomic when it holds at
     * most one element).
     *
     * @return true if atomic
     */
    boolean isAtomic();
    /**
     * Clones this element.
     *
     * @return the copy
     */
    LogicalData clone();
    String toString();
}