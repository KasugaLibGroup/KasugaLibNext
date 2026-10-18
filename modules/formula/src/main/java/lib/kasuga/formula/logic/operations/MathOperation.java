package lib.kasuga.formula.logic.operations;

import lib.kasuga.formula.compute.exceptions.FormulaSyntaxError;
import lib.kasuga.formula.logic.data.LogicalNumeric;
import lib.kasuga.formula.logic.infrastructure.LogicalData;
import lib.kasuga.formula.logic.infrastructure.LogicalOperator;

/**
 * Comparison operator implementation: {@code > < >= <= == !=}.
 *
 * <p>Requires both operands to be {@link lib.kasuga.formula.logic.data.LogicalNumeric}
 * (numeric comparison); {@code ==} additionally allows arbitrary logical
 * elements to compare their boolean results. Non-numeric operands in other
 * comparisons throw {@link lib.kasuga.formula.compute.exceptions.FormulaSyntaxError}
 * (e.g. the chained comparison {@code a > b > c}).
 */
public class MathOperation implements LogicalData, LogicalOperator {
    private final MathType type;

    /**
     * Creates a comparison element.
     *
     * @param type the comparison operator type
     */
    public MathOperation(MathType type) {
        this.type = type;
    }

    /**
     * Creates a comparison element from a string.
     *
     * @param type the operator symbol string
     */
    public MathOperation(String type) {
        this.type = MathType.fromString(type);
    }

    @Override
    public boolean operate(LogicalData former, LogicalData rear) {
        if(!(former instanceof LogicalNumeric numeric1) || !(rear instanceof LogicalNumeric numeric2)) {
            if(type == MathType.EQUALS) {return former.getResult() == rear.getResult();}
            // Chained comparison (a > b > c) or a non-numeric operand: the intermediate result is not numeric —
            throw new FormulaSyntaxError("Chained comparison / non-numeric operand not supported: " + type, null, 0);
        }
        switch (type) {
            case EQUALS -> {return numeric1.mathResult() == numeric2.mathResult();}
            case LARGER -> {return numeric1.mathResult() > numeric2.mathResult();}
            case SMALLER -> {return numeric1.mathResult() < numeric2.mathResult();}
            case LARGER_EQU -> {return numeric1.mathResult() >= numeric2.mathResult();}
            case SMALLER_EQU -> {return numeric1.mathResult() <= numeric2.mathResult();}
            case NOT_EQU -> {return numeric1.mathResult() != numeric2.mathResult();}
            default -> {return false;}
        }
    }

    /**
     * Whether the string is a valid comparison operator.
     *
     * @param mathFlag the string to test
     * @return true if it is a comparison operator
     */
    public static boolean isMathOperation(String mathFlag) {
        return MathType.fromString(mathFlag.replace(" ", "")) != MathType.INVALID;
    }

    /**
     * The operator symbol string.
     *
     * @return the operator symbol
     */
    @Override
    public String toString() {
        return type.toString();
    }

    @Override
    public boolean getResult() {
        return false;
    }

    @Override
    public boolean isAtomic() {
        return true;
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
    public MathOperation clone() {
        return new MathOperation(type);
    }

    /**
     * Whether this operator matches the given type.
     *
     * @param type the {@link MathType} to match
     * @return true if matching
     */
    public boolean is(Object type) {
        if(type instanceof MathType)
            return type == this.type;
        return false;
    }
}