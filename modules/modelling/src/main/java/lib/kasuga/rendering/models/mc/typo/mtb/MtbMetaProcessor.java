package lib.kasuga.rendering.models.mc.typo.mtb;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.text_stream.LineSepProcessor;

import java.util.Objects;

public class MtbMetaProcessor extends LineSepProcessor<MtbContext> {
    public MtbMetaProcessor() {
        super("|", true);
    }

    @Override
    public void process(String[] data, SerialContext<MtbContext> context) {
        switch (data[0]) {
            case "TexSizeX" -> context.peek().textureWidth = Integer.parseInt(data[1]);
            case "TexSizeY" -> context.peek().textureHeight = Integer.parseInt(data[1]);
            case "ModelAuthor" -> context.peek().modelAuthor = data[1];
            case "ModelName" -> context.peek().modelName = data[1];
        }
    }

    @Override
    public boolean isValidInput(String[] data) {
        String type = data[0];
        return Objects.equals(type, "TexSizeX") ||
                Objects.equals(type, "TexSizeY") ||
                Objects.equals(type, "ModelAuthor") ||
                Objects.equals(type, "ModelName");
    }
}
