package lib.kasuga.rendering.models.uml.dynamic.animation.function;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.data.functions.Function;
import lib.kasuga.formula.compute.exceptions.FormulaSyntaxError;

public class FanRampFunction extends Function {
    private final Computer computer;
    public FanRampFunction(String codec, Namespace namespace, Computer computer) {
        super(codec, namespace);
        this.computer = computer;
    }
    @Override
    public float operate() {
        if (params.size() < 6) throw new FormulaSyntaxError(this, 0);
        return computer.getResult(
                params.get(0).getResult(), params.get(1).getResult(), params.get(2).getResult(),
                params.get(3).getResult(), params.get(4).getResult(), params.get(5).getResult());
    }
    @Override
    public Function clone() { return new FanRampFunction(getCodec(), getNamespace(), this.computer); }
    @Override
    public Function clone(Namespace newNamespace) { return new FanRampFunction(getCodec(), newNamespace, this.computer); }
    public interface Computer { float getResult(float p1, float p2, float p3, float p4, float p5, float p6); }
}