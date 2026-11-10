package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be;

import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.util.ModelJson;
import lib.kasuga.utils.json.JsonFieldPolicy.DefaultOnMismatch;
import lib.kasuga.rendering.models.mc.util.RotHelper;
import lib.kasuga.rendering.models.uml.loaders.SkeletonBuilder;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.structure.Pair;
import org.joml.Vector3f;

import java.util.HashMap;

public class BEBoneLayer extends Layer<JsonObject> {

    @Override
    public void process(JsonObject input, Context context) {
        Input definition = ModelJson.GSON.fromJson(input, Input.class);
        HashMap<String, Vector3f> map = (HashMap<String, Vector3f>) context.getData("pivot_map");

        HashMap<String, Pair<Transform, Transform>> transformMap = (HashMap<String, Pair<Transform, Transform>>) context.getData("transform_map");
        SkeletonBuilder skeletonBuilder = context.getLoader().getBones();
        HashMap<String, Object> data = context.getData();
        String name = definition.name;
        Vector3f pivot = new Vector3f(definition.pivot);
        pivot.mul(-1/16f, 1/16f, 1/16f);
        map.put(name, pivot);
        Vector3f rotation = new Vector3f(definition.rotation);
        Transform transform = new Transform();
        String parent = definition.parent;
        if (parent == null) parent = (String) context.getData("component_root");
        Vector3f parentPivot;
        if (parent != null) parentPivot = map.getOrDefault(parent, new Vector3f(0, 0, 0));
        else parentPivot = new Vector3f(0, 0, 0);
        transform.translate(new Vector3f(pivot).sub(parentPivot));
        RotHelper.rotation(transform, rotation);

        transformMap.put(name, Pair.of(transform, parent != null ?
                transformMap.getOrDefault(parent, Pair.of(null, new Transform())).getSecond().mul(transform) :
                transform));
        for (JsonObject cube : definition.cubes) addChildProcess(cube, "cube_layer");
        data.put("bone_name", name);
        definition.locators.forEach((key, locator) -> {
            data.put("locator_name", key);
            addChildProcess(locator, "locator_layer");
        });
        skeletonBuilder.addBone(name, transform, parent, null);
    }

    @Override
    public void postProcess(JsonObject input, Context context) {}
    private static final class Input {
        @DefaultOnMismatch String name = "", parent;
        Vector3f pivot, rotation = new Vector3f();
        java.util.List<JsonObject> cubes = java.util.List.of();
        java.util.Map<String, com.google.gson.JsonElement> locators = java.util.Map.of();
    }
}
