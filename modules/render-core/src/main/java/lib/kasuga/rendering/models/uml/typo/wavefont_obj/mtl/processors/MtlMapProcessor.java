package lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.processors;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.MtlContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.MtlKeyProcessor;

public class MtlMapProcessor extends MtlKeyProcessor {

    public MtlMapProcessor() {
        super("map");
    }

    @Override
    public void process(String[] data, SerialContext<MtlContext> context) {
        switch (data[0]) {
            case "map_Ka":
                context.peek().map_Ka(removeCtrlCodes(data[1]));
                break;
            case "map_Kd":
                context.peek().map_Kd(removeCtrlCodes(data[1]));
                break;
            case "map_Ks":
                context.peek().map_Ks(removeCtrlCodes(data[1]));
                break;
        }
    }

    @Override
    public boolean isValidInput(String[] data) {
        return data.length > 1 &&
                (
                        data[0].equals("map_Ka") ||
                        data[0].equals("map_Kd") ||
                        data[0].equals("map_Ks")
                );
    }

    public static String removeCtrlCodes(String input) {
        return input.replaceAll("\\p{Cntrl}", "");
    }
}
