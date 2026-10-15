package lib.kasuga.formula.logic.infrastructure;

/**
 * Logical operator: comparisons ({@code > < >= <= == !=}) and boolean
 * operators ({@code and or not}).
 *
 * <p>{@link #operate(LogicalData, LogicalData)} evaluates both operands;
 * {@link #is(Object)} matches the operator type (MathType / BoolType).
 */
public interface LogicalOperator {
    /**
     * Evaluates the former and rear operands; {@code not} ignores the former and
     * negates the rear.
     *
     * @param former the former operand (may be null for {@code not})
     * @param rear the rear operand
     * @return the boolean result
     */
    boolean operate(LogicalData former, LogicalData rear);
    /**
     * Whether this operator matches the given type (MathType / BoolType).
     *
     * @param obj the type enum (MathType or BoolType)
     * @return true if matching
     */
    boolean is(Object obj);
}