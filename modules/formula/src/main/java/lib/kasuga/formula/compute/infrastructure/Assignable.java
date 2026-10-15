package lib.kasuga.formula.compute.infrastructure;

import lib.kasuga.formula.UniversalAssignable;
import lib.kasuga.formula.compute.data.Namespace;

import java.util.Map;
import java.util.Set;

/**
 * Contract for assignable formula elements: variables, lines and functions.
 *
 * <p>Values are read and written by {@code codec} (e.g. {@code "x"} or
 * {@code "query.anim_time"}); codec resolution and storage are managed by
 * {@link lib.kasuga.formula.compute.data.Namespace}. {@link #hasVar()} reports whether
 * this element holds any variable (a line or function returns true when it
 * contains one internally).
 */
public interface Assignable extends UniversalAssignable {

    /** The namespace this element belongs to. */
    Namespace getNamespace();
    /**
     * All variable codecs referenced by this element.
     *
     * @return the set of codecs
     */
    Set<String> variableCodecs();
    /**
     * Writes a value by codec; silently ignored when the codec does not belong
     * to this element.
     *
     * @param codec the variable identifier
     * @param value the new value
     */
    void assign(String codec, float value);
    /**
     * Whether this element holds a variable with the given codec.
     *
     * @param codec the variable identifier
     * @return true if present
     */
    boolean containsVar(String codec);
    /**
     * Reads the value of the given codec; throws
     * {@link lib.kasuga.formula.compute.exceptions.FormulaSyntaxError} when absent.
     *
     * @param codec the variable identifier
     * @return the current value
     */
    float getValue(String codec);
    /**
     * Whether this element holds any variable (true for a line or function that
     * contains one internally).
     *
     * @return true if any variable is held
     */
    boolean hasVar();
}