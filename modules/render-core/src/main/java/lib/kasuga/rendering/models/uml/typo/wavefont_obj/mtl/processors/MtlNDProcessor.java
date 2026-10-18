package lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.processors;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.MtlContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.MtlKeyProcessor;

public class MtlNDProcessor extends MtlKeyProcessor {

    public MtlNDProcessor() {
        super("N");
    }

    @Override
    public void process(String[] data, SerialContext<MtlContext> context) {
        switch (data[0]) {
            case "Ns":
                context.peek().Ns(Float.parseFloat(data[1]));
                break;
            case "Ni":
                context.peek().Ni(Float.parseFloat(data[1]));
                break;
            case "d":
                context.peek().d(Float.parseFloat(data[1]));
                break;
            case "illum":
                context.peek().illum(Integer.parseInt(data[1]));
                break;
            case "Tr":
                context.peek().d(1 - Float.parseFloat(data[1]));
                break;
        }
    }

    @Override
    public boolean isValidInput(String[] data) {
        return data.length > 1 && (
                data[0].equals("Ns") ||
                data[0].equals("Ni") ||
                data[0].equals("d") ||
                data[0].equals("illum") ||
                data[0].equals("Tr")
                );
    }
}
