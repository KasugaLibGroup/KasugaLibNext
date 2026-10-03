package lib.kasuga.rendering.models.uml.loaders.serial.text_stream;

import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;

public abstract class LineProcessor<I extends ContextData<I>> {

    public void input(String line, SerialContext<I> context) {
        if (!isValidInput(line, context)) return;
        process(line, context);
    }

    public abstract void process(String line, SerialContext<I> context);

    public abstract boolean isValidInput(String line, SerialContext<I> context);
}
