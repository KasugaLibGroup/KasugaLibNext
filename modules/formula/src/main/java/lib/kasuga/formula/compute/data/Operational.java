package lib.kasuga.formula.compute.data;

import lib.kasuga.formula.compute.exceptions.FormulaSyntaxError;
import lib.kasuga.formula.compute.infrastructure.Assignable;
import lib.kasuga.formula.compute.infrastructure.Formula;
import lib.kasuga.formula.compute.exceptions.FormulaOperationError;

import java.util.HashMap;
import java.util.List;

/**
 * Atomic arithmetic operator: {@code + - * / % ^}.
 *
 * <p>{@link #mergeOperation(Formula, Formula, int)} merges adjacent operators:
 * two consecutive {@code *} ({@code a ** b}) combine into the power {@code ^}
 * (Python style); other illegal pairs (e.g. {@code * +}) throw
 * {@link lib.kasuga.formula.compute.exceptions.FormulaSyntaxError}.
 * {@link #operate(Formula, Formula)} evaluates the operator on both operands.
 */
public class Operational implements Formula {
    String operation = "+";
    boolean shouldRemove = false;

    /**
     * Creates an operator atom.
     *
     * @param string the operator symbol ({@code + - * / % ^})
     */
    public Operational(String string) {
        fromString(string);
    }

    @Override
    public String getString() {
        return operation;
    }

    @Override
    public String getIdentifier() {
        return "operational";
    }

    @Override
    public float getResult() {
        return 0;
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
        return shouldRemove;
    }

    @Override
    public void fromString(String string) {
        String s = string.trim();
        if(isOperational(s))
            this.operation = string;
    }

    /**
     * Finds the first arithmetic operator at or after {@code index}.
     *
     * @param string the string to scan
     * @param index the starting index
     * @return the operator position, or -1
     */
    public static int getOperationIndex(String string, int index) {
        String x = string.replaceAll("(\\+|-|\\*|/|\\^|%)", "OPT");
        return x.indexOf("OPT", index);
    }

    /**
     * Finds the first arithmetic operator from the start.
     *
     * @param string the string to scan
     * @return the operator position, or -1
     */
    public static int getOperationIndex(String string) {
        return getOperationIndex(string, 0);
    }

    /**
     * Applies this operator to the former and rear operands.
     *
     * @param former the former operand
     * @param rear the rear operand
     * @return the result
     */
    public float operate(Formula former, Formula rear) {
        if(former instanceof Operational) {
            throw new FormulaOperationError(former, this, rear);
        } else {
            switch (operation) {
                case "+" -> {return former.getResult() + rear.getResult();}
                case "-" -> {return former.getResult() - rear.getResult();}
                case "*" -> {return former.getResult() * rear.getResult();}
                case "/" -> {return former.getResult() / rear.getResult();}
                case "%" -> {return former.getResult() % rear.getResult();}
                case "^" -> {return (float) Math.pow(former.getResult(), rear.getResult());}
                default -> {return 0;}
            }
        }
    }

    /**
     * Whether the string is a valid arithmetic operator.
     *
     * @param string the string to test
     * @return true if it is an operator
     */
    public static boolean isOperational(String string) {
        String s = string.replaceAll(" ", "");
        return (s.equals("+") || s.equals("-") || s.equals("*") ||
                s.equals("/") || s.equals("%") || s.equals("^"));
    }

    /**
     * Merges adjacent operators ({@code **} becomes the power {@code ^}; illegal
     * combinations throw {@link lib.kasuga.formula.compute.exceptions.FormulaSyntaxError}).
     *
     * @param f the preceding operator
     * @param parent the owning line
     * @param index the index within the line
     */
    public void mergeOperation(Formula f, Formula parent, int index) {
        if(f instanceof Operational fromer) {
            switch (operation) {
                case "+" -> {
                    switch (fromer.operation) {
                        case "+" -> fromer.shouldRemove = true;
                        case "-" -> {
                            fromer.shouldRemove = true;
                            this.operation = "-";
                        }
                        default -> shouldRemove = true;
                    }
                }
                case "-" -> {
                    switch (fromer.operation) {
                        case "+" -> fromer.shouldRemove = true;
                        case "-" -> {
                            fromer.shouldRemove = true;
                            this.operation = "+";
                        }
                    }
                }
                case "*" -> {
                    // Two adjacent `*` (`a ** b`) merge into the power `^` (Python style):
                    // a ** b == a ^ b. A single `*` followed by another operator is a syntax error.
                    if (fromer.operation.equals("*")) {
                        fromer.shouldRemove = true;
                        this.operation = "^";
                    } else {
                        throw new FormulaSyntaxError(parent, index);
                    }
                }
                default -> throw new FormulaSyntaxError(parent, index);
            }
        }
    }

    public Operational clone() {
        return new Operational(String.copyValueOf(this.operation.toCharArray()));
    }

    @Override
    public void flipOutput(boolean flip) {}

    @Override
    public boolean isOutputFlipped() {
        return false;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Operational operational)) return false;
        return operational.operation.equals(operation);
    }
}
