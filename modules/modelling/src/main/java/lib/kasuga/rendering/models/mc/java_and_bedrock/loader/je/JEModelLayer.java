package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.je;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.je.JEElement;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.je.JEModelData;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;

public class JEModelLayer extends Layer<JsonObject> {

    @Override
    public void process(JsonObject input, Context context) {
        JEModelData modelData = (JEModelData) context.getData("modelData");

        context.getLoader().setData(modelData);

        addChildProcess(modelData, "texture_layer");

        for (JEElement element : modelData.getElements()) {
            addChildProcess(element, "element_layer");
        }
    }
}
