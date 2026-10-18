package lib.kasuga.formula.compute.data.functions;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.exceptions.FormulaSyntaxError;

/**
 * Three-parameter function: evaluates the first three arguments, then computes
 * through {@link Computer}. No three-parameter function is pre-registered;
 * callers register one via {@code namespace.register3Param(...)}.
 */
public class TripleParamFunction extends Function {

    final Computer computer;
    /**
     * Creates a three-parameter function.
     *
     * @param codec the function name
     * @param namespace the owning namespace
     * @param computer the three-argument computation logic
     */
    public TripleParamFunction(String codec, Namespace namespace, Computer computer) {
        super(codec, namespace);
        this.computer = computer;
    }

    /**
     * Evaluates the three arguments.
     *
     * @return the three-argument result
     */
    @Override
    public float operate() {
        if(params.size() < 3) throw new FormulaSyntaxError(this, 0);
        return computer.getResult(params.get(0).getResult(), params.get(1).getResult(), params.get(2).getResult());
    }

    @Override
    public Function clone() {
        return new TripleParamFunction(this.codec, namespace, this.computer);
    }

    /**
     * Clones this function and binds the copy to a new namespace.
     *
     * @param newNamespace the new namespace
     * @return a copy
     */
    @Override
    public Function clone(Namespace newNamespace) {
        return new TripleParamFunction(this.codec, newNamespace, this.computer);
    }

    /** Three-argument computation interface. */
    public interface Computer {
        /**
         * Computes the result for three argument values.
         *
         * @param param1 the first argument value
         * @param param2 the second argument value
         * @param param3 the third argument value
         * @return the result
         */
        float getResult(float param1, float param2, float param3);
    }
}