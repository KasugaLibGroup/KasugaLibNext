package lib.kasuga.rendering.models.uml.typo.wavefont_obj.processors;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjContextData;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjVertexBinding;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjModelLoader;

import java.util.ArrayList;

public class ObjBindingProcessor extends ObjKeyProcessor {

    public ObjBindingProcessor() {
        super("f");
    }

    @Override
    public void process(String[] data, SerialContext<ObjContextData> context) {
        ObjModelLoader loader = (ObjModelLoader) context.getLoader();
        ObjContextData contextData = loader.ensureContext(context);
        ArrayList<ObjVertexBinding> bindings = new ArrayList<>();
        for (int i = 1; i < data.length; i++) {
            String[] bindingData = data[i].split("/");
            int vertexIndex = loader.resolveVertexIndex(Integer.parseInt(bindingData[0]));
            int textureIndex = bindingData.length > 1 && !bindingData[1].isEmpty() ? loader.resolveUvIndex(Integer.parseInt(bindingData[1])) : -1;
            int normalIndex = bindingData.length > 2 && !bindingData[2].isEmpty() ? loader.resolveNormalIndex(Integer.parseInt(bindingData[2])) : -1;
            bindings.add(new ObjVertexBinding(vertexIndex, textureIndex, normalIndex));
        }
        contextData.f(bindings);
    }
}
