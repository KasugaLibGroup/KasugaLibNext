package lib.kasuga.formula.logic.operations;

/**
 * Boolean operator enum: {@code and} / {@code or} / {@code not} (lowercase).
 */
public enum BoolType {

    /** And: {@code a and b}. */
    AND,
    /** Or: {@code a or b}. */
    OR,
    /** Not: {@code not a}. */
    NOT,
    /** Invalid marker. */
    INVALID;

    @Override
    public String toString() {
        switch (this) {
            case OR -> {return "or";}
            case AND -> {return "and";}
            case NOT -> {return "not";}
            default -> {return "invalid";}
        }
    }

    /**
     * Parses an enum from a word: {@code and} / {@code or} / {@code not};
     * unknown words yield {@link #INVALID}.
     *
     * @param string the operator word
     * @return the matching enum or INVALID
     */
    public static BoolType fromString(String string) {
        switch (string) {
            case "or" -> {return OR;}
            case "and" -> {return AND;}
            case "not" -> {return NOT;}
            default -> {return INVALID;}
        }
    }
}