package lib.kasuga.formula.compute.data.functions;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.infrastructure.Formula;

/**
 * Parameterless function: {@link Computer} yields the result directly without
 * consuming arguments.
 */
public class NoParamFunction extends Function {
    final Computer computer;

    /**
     * Creates a parameterless function.
     *
     * @param codec the function name
     * @param namespace the owning namespace
     * @param computer the parameterless computation logic
     */
    public NoParamFunction(String codec, Namespace namespace, Computer computer) {
        super(codec, namespace);
        this.computer = computer;
    }

    /**
     * Computes the result directly.
     *
     * @return the result
     */
    @Override
    public float operate() {
        return computer.getResult();
    }

    @Override
    public Function clone(Namespace namespace) {
        return new NoParamFunction(this.codec, namespace, this.computer);
    }

    /**
     * Clones this function.
     *
     * @return a copy bound to the same namespace
     */
    @Override
    public NoParamFunction clone() {
        return new NoParamFunction(codec, getNamespace(), computer);
    }

    /** Parameterless computation interface. */
    public interface Computer {
        /**
         * Yields the result directly.
         *
         * @return the result
         */
        float getResult();
    }
}