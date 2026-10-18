package lib.kasuga.formula.compute.exceptions;

import lib.kasuga.formula.compute.infrastructure.Formula;

/**
 * Expression syntax error: unbalanced brackets, illegal operator sequences,
 * non-numeric operands in comparisons, etc.
 *
 * <p>Carries an optional message (e.g. the chained-comparison explanation) and
 * a location hint ({@code formula} element + {@code position} index).
 */
public class FormulaSyntaxError extends RuntimeException {

    /** The offending element. */
    private final Formula formula;
    /** The position index of the error. */
    private final int position;
    /**
     * Creates a syntax error.
     *
     * @param formula the offending element
     * @param position the error position
     */
    public FormulaSyntaxError(Formula formula, int position) {
        this(null, formula, position);
    }

    /**
     * Creates a syntax error with a message.
     *
     * @param message the error description
     * @param formula the offending element
     * @param position the error position
     */
    public FormulaSyntaxError(String message, Formula formula, int position) {
        super(message);
        this.formula = formula;
        this.position = position;
    }
}