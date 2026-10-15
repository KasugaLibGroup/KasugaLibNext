package lib.kasuga.rendering.models.uml.typo.wavefont_obj.processors;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjContextData;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjModelLoader;

public class ObjUseMtlProcessor extends ObjKeyProcessor {

        public ObjUseMtlProcessor() {
            super("usemtl");
        }

    @Override
    public void process(String[] data, SerialContext<ObjContextData> context) {
        ObjModelLoader loader = (ObjModelLoader) context.getLoader();
        ObjContextData contextData = loader.ensureContext(context);
        contextData.useMtl(data[1]);
    }
}
