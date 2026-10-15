package lib.kasuga.formula.compute.exceptions;

import lib.kasuga.formula.compute.data.Operational;
import lib.kasuga.formula.compute.infrastructure.Formula;

/**
 * Operation error: an operator was used as an operand (e.g. two operators in a
 * row that cannot be merged).
 */
public class FormulaOperationError extends RuntimeException {
    /** The offending operand-combination text. */
    private final String statement;
    /**
     * Creates an operation error.
     *
     * @param former the former operand
     * @param operational the operator
     * @param rear the rear operand
     */
    public FormulaOperationError(Formula former, Operational operational, Formula rear) {
        this.statement = "<" + former.getString() + operational.getString() + rear.getString() + ">";
    }

    /** Prints the offending operand combination with a hint. */
    @Override
    public void printStackTrace() {
        System.err.println("Cannot operate via " + statement + ", pls check your input");
        super.printStackTrace();
    }
}