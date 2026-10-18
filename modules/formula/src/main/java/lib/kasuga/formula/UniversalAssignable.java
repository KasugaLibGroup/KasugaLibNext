package lib.kasuga.formula;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.infrastructure.Assignable;

import java.util.Map;

/**
 * Minimal common contract for assignable objects, shared by the compute
 * (arithmetic) and logic sides.
 *
 * <p>Only value writing ({@link #assign(String, float)}) and namespace access
 * ({@link #getNamespace()}) are declared; the full read/write semantics are
 * extended by each domain's Assignable interface.
 */
public interface UniversalAssignable {
    /**
     * Writes a value by codec.
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