package lib.kasuga.formula.logic.operations;

/**
 * Comparison operator enum: {@code > < >= <= == !=}; {@code <>} normalizes to
 * {@code !=}.
 */
public enum MathType {
    /** Greater than {@code >}. */
    LARGER,
    /** Less than {@code <}. */
    SMALLER,
    /** Equal to {@code ==}. */
    EQUALS,
    /** Not equal to {@code !=} ({@code <>} normalizes to it). */
    NOT_EQU,
    /** Greater than or equal to {@code >=}. */
    LARGER_EQU,
    /** Less than or equal to {@code <=}. */
    SMALLER_EQU,
    /** Invalid marker. */
    INVALID;

    @Override
    public String toString() {
        switch (this) {
            case LARGER -> {return ">";}
            case SMALLER -> {return "<";}
            case LARGER_EQU -> {return ">=";}
            case SMALLER_EQU -> {return "<=";}
            case EQUALS -> {return "==";}
            case NOT_EQU -> {return "!=";}
            default -> {return "invalid";}
        }
    }

    /**
     * Parses an enum from a symbol: {@code > < >= <= == !=}; {@code <>}
     * normalizes to {@code !=}; unknown symbols yield {@link #INVALID}.
     *
     * @param string the operator symbol
     * @return the matching enum or INVALID
     */
    public static MathType fromString(String string) {
        switch (string) {
            case ">" -> {return LARGER;}
            case "<" -> {return SMALLER;}
            case "==" -> {return EQUALS;}
            case ">=" -> {return LARGER_EQU;}
            case "<=" -> {return SMALLER_EQU;}
            case "<>", "!=" -> {return NOT_EQU;}
            default -> {return INVALID;}
        }
    }
}