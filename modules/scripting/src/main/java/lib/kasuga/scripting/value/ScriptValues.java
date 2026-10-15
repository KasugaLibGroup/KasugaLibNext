package lib.kasuga.scripting.value;

import lib.kasuga.scripting.ScriptException;

/**
 * Truthiness and small coercion helpers for {@link ScriptValue} results returned by JS callbacks.
 * The JS↔Java bridge delivers a JS {@code boolean} as a {@link ScriptPrimitive} whose {@link
 * ScriptPrimitive#getValue()} is a {@link Boolean}; a JS {@code number} arrives as a numeric
 * primitive. {@link #isTrue(ScriptValue)} collapses both (plus null/undefined) into a Java boolean
 * for guards that bridge back into Java (e.g. an FSM condition whose body is a JS arrow function).
 */
public final class ScriptValues {

    private ScriptValues() {}

    /**
     * JS-truthiness for a callback result: {@code null}/undefined → {@code false}; a primitive is
     * unpacked and interpreted as boolean ({@code Boolean}), non-zero number, or parsed string;
     * any non-primitive value (object/array/function) is truthy.
     */
    public static boolean isTrue(ScriptValue value) {
        if (value == null) {
            return false;
        }
        if (value instanceof ScriptPrimitive primitive) {
            try {
                Object unwrapped = primitive.getValue();
                if (unwrapped instanceof Boolean b) {
                    return b;
                }
                if (unwrapped instanceof Number n) {
                    return n.doubleValue() != 0d;
                }
                if (unwrapped instanceof String s) {
                    return Boolean.parseBoolean(s);
                }
                return unwrapped != null;
            } catch (ScriptException ignored) {
                return false;
            }
        }
        return true;
    }
}
