package lib.kasuga.formula.compute.data.functions;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.exceptions.FormulaSyntaxError;

/**
 * Two-parameter function ({@code pow max min ...}): evaluates the first two
 * arguments, then computes through {@link Computer}. Throws
 * {@link lib.kasuga.formula.compute.exceptions.FormulaSyntaxError} when fewer than two
 * arguments are present.
 *
 * <p>The class name keeps the upstream historical spelling {@code Prarm} for
 * binary compatibility; it is not corrected.
 */
public class DoublePrarmFunction extends Function {

    final Computer computer;
    /**
     * Creates a two-parameter function.
     *
     * @param codec the function name
     * @param namespace the owning namespace
     * @param computer the two-argument computation logic
     */
    public DoublePrarmFunction(String codec, Namespace namespace, Computer computer) {
        super(codec, namespace);
        this.computer = computer;
    }

    @Override
    public float operate() {
        if(params.size() < 2) throw new FormulaSyntaxError(this, 0);
        return computer.getResult(params.get(0).getResult(), params.get(1).getResult());
    }

    @Override
    public Function clone() {
        return new DoublePrarmFunction(codec, namespace, computer);
    }

    @Override
    public Function clone(Namespace namespace) {
        return new DoublePrarmFunction(codec, namespace, computer);
    }

    /** Two-argument computation interface. */
    public interface Computer{
    /**
     * Computes the result for two argument values.
     *
     * @param param1 the first argument value
     * @param param2 the second argument value
     * @return the result
     */
        float getResult(float param1, float param2);
    }
}