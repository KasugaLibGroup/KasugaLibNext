package lib.kasuga.rendering.models.uml.typo.wavefont_obj.processors;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjContextData;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjModelLoader;
import org.joml.Vector3f;

public class ObjVertexProcessor extends ObjKeyProcessor {

    public ObjVertexProcessor() {
        super("v");
    }

    @Override
    public void process(String[] data, SerialContext<ObjContextData> context) {
        ObjModelLoader loader = (ObjModelLoader) context.getLoader();
        loader.getObjVertexPositions().add(new Vector3f(
                Float.parseFloat(data[1]),
                Float.parseFloat(data[2]),
                Float.parseFloat(data[3])
        ));
    }
}
