package lib.kasuga.formula.logic.data;

import lib.kasuga.formula.logic.infrastructure.LogicalData;

/**
 * Atomic boolean literal: {@code True} / {@code False} (capitalized,
 * case-sensitive).
 *
 * <p>{@link #isBool(String)} recognizes pure boolean tokens; numeric values
 * participate through {@link lib.kasuga.formula.logic.data.LogicalNumeric} with
 * non-zero truthiness.
 */
public class LogicalBool implements LogicalData {

    private final boolean flag;
    /**
     * Creates a boolean literal.
     *
     * @param flag the boolean value
     */
    public LogicalBool(boolean flag) {
        this.flag = flag;
    }

    /**
     * Creates a literal from a string (only exactly {@code True} is true).
     *
     * @param boolFlag the boolean literal string
     */
    public LogicalBool(String boolFlag) {
        this.flag = boolFlag.replace(" ", "").equals("True");
    }

    /**
     * Whether the string is a boolean literal ({@code True}/{@code False}).
     *
     * @param boolFlag the string to test
     * @return true if it is a boolean literal
     */
    public static boolean isBool(String boolFlag) {
        String s = boolFlag.replaceAll("( )|(\\()|(\\))", "");
        return s.equals("True") || s.equals("False");
    }

    /**
     * Pre-built true literal.
     *
     * @return the true literal
     */
    public static LogicalBool defaultTrue() {
        return new LogicalBool(true);
    }

    /**
     * Pre-built false literal.
     *
     * @return the false literal
     */
    public static LogicalBool defaultFalse() {
        return new LogicalBool(false);
    }

    @Override
    /**
     * The boolean value.
     *
     * @return the boolean value
     */
    public boolean getResult() {
        return flag;
    }

    /**
     * Whether this element is atomic.
     *
     * @return always true (a boolean literal is atomic)
     */
    @Override
    public boolean isAtomic() {
        return true;
    }

    /**
     * Clones this literal.
     *
     * @return the copy
     */
    @Override
    public LogicalBool clone() {
        return new LogicalBool(flag);
    }

    /**
     * The literal text.
     *
     * @return {@code True} or {@code False}
     */
    @Override
    public String toString() {
        return flag ? "True" : "False";
    }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof LogicalBool bool)) return false;
        return bool.flag == this.flag;
    }
}