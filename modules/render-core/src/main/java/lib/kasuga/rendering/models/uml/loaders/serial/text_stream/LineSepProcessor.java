package lib.kasuga.rendering.models.uml.loaders.serial.text_stream;

import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lombok.Getter;

public abstract class LineSepProcessor<I extends ContextData<I>> extends LineProcessor<I> {

    @Getter
    private final String separator;

    @Getter
    private final boolean trim;

    public LineSepProcessor(String separator, boolean trim) {
        this.separator = separator;
        this.trim = trim;
    }

    @Override
    public void input(String line, SerialContext<I> context) {
        String[] data = split(line);
        process(data, context);
    }

    @Override
    @Deprecated
    public void process(String line, SerialContext<I> context) {}

    public abstract void process(String[] data, SerialContext<I> context);

    @Override
    @Deprecated
    public boolean isValidInput(String line, SerialContext<I> context) {
        String[] data = split(line);
        return isValidInput(data);
    }

    public abstract boolean isValidInput(String[] data);

    public String[] split(String line) {
        String[] result = line.split(separator);
        if (!trim) return result;
        for (int i = 0; i < result.length; i++) {
            result[i] = TextStreamLoader.innerTrim(result[i]);
        }
        return result;
    }
}
