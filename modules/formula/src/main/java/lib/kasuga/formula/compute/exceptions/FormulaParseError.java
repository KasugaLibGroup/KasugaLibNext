package lib.kasuga.formula.compute.exceptions;

/**
 * Expression parse error: a token could not be classified as any formula
 * element (number, variable, function or operator).
 *
 * <p>Wraps the underlying
 * {@link lib.kasuga.formula.compute.exceptions.FormulaSyntaxError} plus the original
 * input string that triggered the error.
 */
public class FormulaParseError extends RuntimeException {

    /** The underlying syntax error. */
    private final Exception main;
    /** The input string that triggered the error. */
    private final String parsing;
    /**
     * Creates a parse error.
     *
     * @param exception the underlying syntax error
     * @param parsing the input string that triggered the error
     */
    public FormulaParseError(Exception exception, String parsing) {
        this.main = exception;
        this.parsing = parsing;
    }

    /** Prints the parsing context and the underlying error. */
    @Override
    public void printStackTrace() {
        System.err.println("Unexpected error while parsing " + parsing);
        main.printStackTrace();
        super.printStackTrace();
    }
}