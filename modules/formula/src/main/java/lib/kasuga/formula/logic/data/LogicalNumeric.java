package lib.kasuga.formula.logic.data;

import lib.kasuga.formula.Code;
import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.infrastructure.Assignable;
import lib.kasuga.formula.compute.infrastructure.Formula;
import lib.kasuga.formula.logic.infrastructure.LogicalAssignable;
import lib.kasuga.formula.logic.infrastructure.LogicalData;

import java.util.HashMap;
import java.util.Map;

/**
 * Logical numeric: wraps an arithmetic
 * {@link lib.kasuga.formula.compute.infrastructure.Formula}, exposing a numeric
 * result ({@link #mathResult()}) and truthiness (non-zero is true,
 * {@link #getResult()}).
 *
 * <p>It carries comparison operands and arithmetic sub-expressions (such as
 * {@code a + 1}) of logical expressions; variable assignment lands in the
 * wrapped formula's {@link lib.kasuga.formula.compute.data.Namespace}.
 */
public class LogicalNumeric implements LogicalData, LogicalAssignable {
    private final Formula formula;
    private final Namespace namespace;

    /**
     * Wraps an arithmetic formula.
     *
     * @param formula the arithmetic formula
     */
    public LogicalNumeric(Formula formula) {
        this.formula = formula;
        if(formula instanceof Assignable assignable)
            namespace = assignable.getNamespace();
        else
            namespace = null;
    }

    /**
     * Creates from an expression string (decoded into a formula internally).
     *
     * @param formulaString the arithmetic expression
     * @param namespace the evaluation namespace
     */
    public LogicalNumeric(String formulaString, Namespace namespace) {
        this.formula = namespace.decodeFormula(formulaString);
        this.namespace = namespace;
    }

    public boolean getResult() {return (int) mathResult() != 0;}

    @Override
    /**
     * Whether this element is atomic.
     *
     * @return always true
     */
    public boolean isAtomic() {
        return true;
    }

    @Override
    public void assign(String codec, float value) {
        if(isAssignable()) ((Assignable) formula).assign(codec, value);
    }

    @Override
    public Namespace getNamespace() {
        return namespace;
    }

    @Override
    public String toString() {
        return formula.getString();
    }

    @Override
    public boolean isAssignable() {
        return namespace != null;
    }

    /**
     * Clones this logical numeric.
     *
     * @return the copy
     */
    @Override
    public LogicalNumeric clone() {
        return new LogicalNumeric(formula.clone());
    }

    /**
     * The numeric result of the wrapped formula.
     *
     * @return the numeric result
     */
    public float mathResult() {return formula.getResult();}

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof LogicalNumeric numeric)) return false;
        return formula.equals(numeric.formula);
    }
}