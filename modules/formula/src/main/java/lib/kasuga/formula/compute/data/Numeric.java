package lib.kasuga.formula.compute.data;

import lib.kasuga.formula.compute.infrastructure.Assignable;
import lib.kasuga.formula.compute.infrastructure.Formula;
import lib.kasuga.formula.compute.exceptions.FormulaSyntaxError;

import java.util.HashMap;
import java.util.List;

/**
 * Atomic numeric literal, e.g. {@code 3} or {@code 3.14}.
 *
 * <p>Supports the unary-minus flip ({@link #flipOutput(boolean)}).
 * {@link #isNumber(String)} only recognizes unsigned integers and decimals —
 * the sign is handled by the line parser during pre-treatment.
 */
public class Numeric implements Formula {
    private float number = 0;
    private boolean flip = false;

    /**
     * Creates a numeric literal.
     *
     * @param number the value
     */
    public Numeric(float number) {
        this.number = number;
    }

    /**
     * Creates a numeric literal by parsing a string.
     *
     * @param string the numeric string
     */
    public Numeric(String string) {
        this.number = Float.parseFloat(string);
    }

    @Override
    public String getString() {
        return (flip ? "-" : "") + number;
    }

    @Override
    public String getIdentifier() {
        return "numeric";
    }

    @Override
    public float getResult() {
        return flip ? - number : number;
    }

    @Override
    public List<Formula> getElements() {
        return List.of(this);
    }

    @Override
    public boolean isAtomic() {
        return true;
    }

    @Override
    public boolean shouldRemove() {
        return false;
    }

    @Override
    public void fromString(String string) {
        try {number = Float.parseFloat(string);}
        catch (Exception e) {throw new FormulaSyntaxError(this, 0);}
    }

    /**
     * Whether the string is an unsigned integer or decimal literal.
     *
     * @param string the string to test
     * @return true if it is a numeric literal
     */
    /**
     * Whether the string is an unsigned integer or decimal literal.
     *
     * @param string the string to test
     * @return true if it is a numeric literal
     */
    public static boolean isNumber(String string) {
        return string.replaceAll(" ", "")
                .replaceAll("^\\d+(\\.\\d+)?$", "").equals("");
    }

    public Numeric clone() {
        return new Numeric(String.valueOf(this.number));
    }

    @Override
    public void flipOutput(boolean flip) {
        this.flip = flip;
    }

    @Override
    public boolean isOutputFlipped() {
        return flip;
    }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof Numeric numeric)) return false;
        return numeric.number == number;
    }
}
