package lib.kasuga.formula.logic.operations;

import lib.kasuga.formula.logic.infrastructure.LogicalData;
import lib.kasuga.formula.logic.infrastructure.LogicalOperator;

/**
 * Boolean operator implementation: {@code and}, {@code or}, {@code not}.
 *
 * <p>{@code not} is unary: {@link #operate(LogicalData, LogicalData)} negates
 * only the rear operand. Implements both
 * {@link lib.kasuga.formula.logic.infrastructure.LogicalData} and
 * {@link lib.kasuga.formula.logic.infrastructure.LogicalOperator}.
 */
public class BoolOperation implements LogicalData, LogicalOperator {
    private final BoolType type;

    /**
     * Creates a boolean operator element.
     *
     * @param type the boolean operator type
     */
    public BoolOperation(BoolType type) {
        this.type = type;
    }

    /**
     * Creates a boolean operator element from a string.
     *
     * @param type the operator string ({@code and}/{@code or}/{@code not})
     */
    public BoolOperation(String type) {
        this.type = BoolType.fromString(type);
    }

    @Override
    /**
     * Evaluates the former and rear operands ({@code not} ignores the former
     * and negates the rear).
     *
     * @param former the former operand
     * @param rear the rear operand
     * @return the boolean result
     */
    public boolean operate(LogicalData former, LogicalData rear) {
        switch (type) {
            case AND -> {return former.getResult() && rear.getResult();}
            case OR -> {return former.getResult() || rear.getResult();}
            case NOT -> {return !rear.getResult();}
            default -> {return false;}
        }
    }

    /**
     * Whether the string is a valid boolean operator.
     *
     * @param typeFlag the string to test
     * @return true if it is a boolean operator
     */
    public static boolean isBoolOperation(String typeFlag) {
        return BoolType.fromString(typeFlag.replace(" ", "")) != BoolType.INVALID;
    }

    /**
     * Whether this operator matches the given type.
     *
     * @param type the {@link BoolType} to match
     * @return true if matching
     */
    public boolean is(Object type) {
        if(type instanceof BoolType)
            return type == this.type;
        return false;
    }

    @Override
    public boolean getResult() {
        return false;
    }

    @Override
    public boolean isAtomic() {
        return true;
    }

    @Override
    public String toString() {
        return type.toString();
    }

    /**
     * Clones this operator element.
     *
     * @return the copy
     */
    @Override
    /**
     * Clones this operator element.
     *
     * @return the copy
     */
    public BoolOperation clone() {
        return new BoolOperation(type);
    }
}