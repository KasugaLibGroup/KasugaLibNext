package lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.processors;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.MtlContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.mtl.MtlKeyProcessor;

public class MtlObjectProcessor extends MtlKeyProcessor {

    public MtlObjectProcessor() {
        super("newmtl");
    }

    @Override
    public void process(String[] data, SerialContext<MtlContext> context) {
        MtlContext contextData = new MtlContext(MtlMapProcessor.removeCtrlCodes(data[1]));
        context.push(contextData);
    }
}
