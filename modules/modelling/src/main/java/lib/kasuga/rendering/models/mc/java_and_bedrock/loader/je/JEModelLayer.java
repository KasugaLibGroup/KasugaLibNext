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

        java.util.Map<JEElement, Integer> indices = new java.util.IdentityHashMap<>();
        for (int i = 0; i < modelData.getElements().size(); i++) indices.put(modelData.getElements().get(i), i);
        context.setData("component_element_indices", indices);
        context.setData("component_vertex_bones", new java.util.IdentityHashMap<lib.kasuga.rendering.models.uml.structure.basic.Vertex, String>());
        var components = lib.kasuga.rendering.models.mc.component.ModelComponents.parse(modelData.getComponents());
        modelData.setKasugaComponents(components.isEmpty() ? null : components);
        components.validateElementCount(modelData.getElements().size());
        context.setData("model_components", components);
        for (JEElement element : modelData.getElements()) {
            addChildProcess(element, "element_layer");
        }
    }
}
