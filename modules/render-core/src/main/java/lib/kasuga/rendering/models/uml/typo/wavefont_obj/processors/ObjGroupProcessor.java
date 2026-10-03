package lib.kasuga.rendering.models.uml.typo.wavefont_obj.processors;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjContextData;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjModelLoader;

public class ObjGroupProcessor extends ObjKeyProcessor {

    public ObjGroupProcessor() {
        super("g");
    }

    @Override
    public void process(String[] data, SerialContext<ObjContextData> context) {
        ((ObjModelLoader) context.getLoader()).endHighestGroup(context);
        ObjContextData contextData = new ObjContextData(data[1], true);
        context.push(contextData);
    }
}
