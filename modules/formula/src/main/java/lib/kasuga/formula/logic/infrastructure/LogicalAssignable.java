package lib.kasuga.formula.logic.infrastructure;

import lib.kasuga.formula.UniversalAssignable;
import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.infrastructure.Assignable;

import java.util.Map;

/**
 * Assignable contract on the logic side: logical lines and logical numerics
 * implement it, and values ultimately live in the arithmetic-side
 * {@link lib.kasuga.formula.compute.data.Namespace}.
 */
public interface LogicalAssignable extends UniversalAssignable {
    /**
     * Whether this element is assignable (holds a non-null namespace).
     *
     * @return true if assignable
     */
    boolean isAssignable();
    /**
     * Writes a value by codec (into the arithmetic-side namespace).
     *
     * @param codec the variable identifier
     * @param value the new value
     */
    void assign(String codec, float value);
    /**
     * The namespace holding the value.
     *
     * @return the namespace
     */
    Namespace getNamespace();
}