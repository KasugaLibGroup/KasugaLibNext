package lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl;

import lib.kasuga.rendering.models.uml.loaders.serial.text_stream.LineSepProcessor;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lombok.Getter;

public abstract class MtlKeyProcessor extends LineSepProcessor<MtlContext> {

    @Getter
    private final String key;

    public MtlKeyProcessor(String key) {
        super(" ", true);
        this.key = key;
    }

    @Override
    public abstract void process(String[] data, SerialContext<MtlContext> context);

    @Override
    public boolean isValidInput(String[] data) {
        return data.length > 0 && data[0].equals(key);
    }
}
