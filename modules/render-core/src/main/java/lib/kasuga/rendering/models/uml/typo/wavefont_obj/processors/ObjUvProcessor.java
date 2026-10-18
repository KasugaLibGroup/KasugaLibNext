package lib.kasuga.rendering.models.uml.typo.wavefont_obj.processors;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjContextData;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjModelLoader;
import org.joml.Vector2f;

public class ObjUvProcessor extends ObjKeyProcessor {

    public ObjUvProcessor() {
        super("vt");
    }

    @Override
    public void process(String[] data, SerialContext<ObjContextData> context) {
        ObjModelLoader loader = (ObjModelLoader) context.getLoader();
        loader.getObjVertexUvs().add(new Vector2f(
                Float.parseFloat(data[1]),
                Float.parseFloat(data[2])
        ));
    }
}
