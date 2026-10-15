package lib.kasuga.rendering.models.uml.typo.wavefont_obj.processors;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjContextData;

public class ObjObjectProcessor extends ObjKeyProcessor {


    public ObjObjectProcessor() {
        super("o");
    }

    @Override
    public void process(String[] data, SerialContext<ObjContextData> context) {
        ObjContextData contextData = new ObjContextData(data[1], false);
        context.push(contextData);
    }
}
