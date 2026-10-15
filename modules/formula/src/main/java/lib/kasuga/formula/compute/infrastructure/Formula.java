package lib.kasuga.formula.compute.infrastructure;

import java.util.HashMap;
import java.util.List;

/**
 * Common contract for every formula element: numbers, variables, operators,
 * functions and lines ({@code Line}) all implement this interface.
 *
 * <p>An element can be serialized to a string ({@link #getString()}), evaluated
 * to a float ({@link #getResult()}), parsed from a string ({@link #fromString(String)})
 * and cloned ({@link #clone()}). {@link #flipOutput(boolean)} expresses the unary
 * minus (negating the result); {@link #isAtomic()} tells whether the element cannot
 * be decomposed further.
 */
public interface Formula {

    /** Codec of the opening bracket. */
    String FRONT_BRACKET_CODEC = "(";
    /** Codec of the closing bracket. */
    String BACK_BRACKET_CODEC = ")";
    /**
     * Serializes this element to a string. Atomic elements (numbers, variables,
     * functions) render directly; a line wraps non-atomic children in parentheses
     * and prefixes {@code -} when the output is flipped.
     *
     * @return the expression string
     */
    String getString();
    /**
     * Type identifier of the element: {@code line}, {@code numeric}, {@code var},
     * {@code operational} or {@code function}.
     *
     * @return the type identifier
     */
    String getIdentifier();
    /**
     * Evaluates this element (including its children) and returns the current result.
     * Variables always reflect the latest value written through
     * {@link lib.kasuga.formula.compute.data.Namespace#assign(String, float)}.
     *
     * @return the current result
     */
    float getResult();
    /**
     * Child elements of this element (an atomic element returns a list containing
     * itself, or an empty list).
     *
     * @return the child elements
     */
    List<Formula> getElements();
    /**
     * Whether this element is atomic, i.e. cannot be decomposed further
     * (numbers, variables, operators and functions are atomic; a line is atomic
     * when it holds at most one element).
     *
     * @return true if atomic
     */
    boolean isAtomic();
    /**
     * Whether this element should be removed (an empty line or a merged operator
     * returns true).
     *
     * @return true if it should be dropped from the parent line
     */
    boolean shouldRemove();
    /**
     * Parses the given string into this element; throws
     * {@link lib.kasuga.formula.compute.exceptions.FormulaSyntaxError} on failure.
     *
     * @param string the expression fragment to parse
     */
    void fromString(String string);
    /**
     * Clones this element, including its children (variables share their value
     * reference).
     *
     * @return the copy
     */
    Formula clone();
    /**
     * Sets the unary-minus flip: when {@code flip} is true the result is negated.
     *
     * @param flip true to negate this element's result
     */
    void flipOutput(boolean flip);
    /**
     * Whether the unary-minus flip is currently active.
     *
     * @return true if flipped
     */
    boolean isOutputFlipped();
}