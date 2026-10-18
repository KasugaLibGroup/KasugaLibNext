package lib.kasuga.formula.compute.data.functions;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.exceptions.FormulaSyntaxError;

/**
 * Single-parameter function ({@code cos sin tan sqrt round ...}): evaluates its
 * only argument, then computes through {@link Computer}. Throws
 * {@link lib.kasuga.formula.compute.exceptions.FormulaSyntaxError} when the argument is missing.
 */
public class SingleParamFunction extends Function {
    final Computer computer;
    /**
     * Creates a single-parameter function.
     *
     * @param codec the function name
     * @param namespace the owning namespace
     * @param computer the single-argument computation logic
     */
    public SingleParamFunction(String codec, Namespace namespace, Computer computer) {
        super(codec, namespace);
        this.computer = computer;
    }

    /**
     * Evaluates the single argument.
     *
     * @return the single-argument result
     */
    @Override
    public float operate() {
        if(params.isEmpty()) throw new FormulaSyntaxError(this, 0);
        return computer.getResult(params.get(0).getResult());
    }

    /**
     * Clones this function.
     *
     * @return a copy bound to the same namespace
     */
    @Override
    public SingleParamFunction clone() {
        return new SingleParamFunction(this.codec, namespace, computer);
    }

    @Override
    public Function clone(Namespace newNamespace) {
        return new SingleParamFunction(codec, newNamespace, computer);
    }

    /** Single-argument computation interface. */
    public interface Computer {
        /**
         * Computes the result for the input value.
         *
         * @param input the argument value
         * @return the result
         */
        float getResult(float input);
    }
}