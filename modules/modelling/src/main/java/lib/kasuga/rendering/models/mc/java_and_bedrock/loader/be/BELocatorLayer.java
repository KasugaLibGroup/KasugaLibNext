package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be;

import com.google.gson.JsonElement;
import lib.kasuga.rendering.models.mc.util.JsonHelper;
import lib.kasuga.rendering.models.mc.util.ModelJson;
import lib.kasuga.rendering.models.mc.util.RotHelper;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;

import java.util.List;

public class BELocatorLayer extends Layer<JsonElement> {

    @Override
    public void process(JsonElement input, Context context) {
        String name = (String) context.getData("locator_name");
        String boneName = (String) context.getData("bone_name");
        if (input.isJsonArray()) {
            Vector3f pos = JsonHelper.jsonToV3f(input);
            pos.mul(-1/16f, 1/16f, 1/16f);
            Transform transform = new Transform();
            transform.translate(pos);
            context.getLoader().getBones().addAnchor(name, transform, List.of(boneName), null);
        }
        if (input.isJsonObject()) {
            Input definition = ModelJson.GSON.fromJson(input, Input.class);
            Vector3f pos = new Vector3f(definition.offset).mul(1/16f);
            pos.mul(-1, 1, 1);
            Vector3f rotation = new Vector3f(definition.rotation);
            Transform transform = new Transform();
            transform.translate(pos);
            RotHelper.rotation(transform, rotation);
            context.getLoader().getBones().addAnchor(name, transform, List.of(boneName), null);
        }
    }
    private static final class Input {
        Vector3f offset, rotation = new Vector3f();
    }
}
