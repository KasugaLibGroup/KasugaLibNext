package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.util.JsonHelper;
import lib.kasuga.rendering.models.mc.util.RotHelper;
import lib.kasuga.rendering.models.uml.loaders.SkeletonBuilder;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.AnchorData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.rendering.models.uml.util.TransformStack;
import lib.kasuga.structure.Pair;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Stack;

public class BEBoneLayer extends Layer<JsonObject> {

    @Override
    public void process(JsonObject input, Context context) {
        HashMap<String, Vector3f> map = (HashMap<String, Vector3f>) context.getData("pivot_map");

        HashMap<String, Pair<Transform, Transform>> transformMap = (HashMap<String, Pair<Transform, Transform>>) context.getData("transform_map");
        SkeletonBuilder skeletonBuilder = context.getLoader().getBones();
        HashMap<String, Object> data = context.getData();
        String name = JsonHelper.jsonToString(input, "name", "");
        Vector3f pivot = JsonHelper.jsonToV3f(input.get("pivot"));
        pivot.mul(-1/16f, 1/16f, 1/16f);
        map.put(name, pivot);
        Vector3f rotation = input.has("rotation") ?
                JsonHelper.jsonToV3f(input.get("rotation")) :
                new Vector3f(0, 0, 0);
        Transform transform = new Transform();
        String parent = JsonHelper.jsonToString(input, "parent", null);
        Vector3f parentPivot;
        if (parent != null) parentPivot = map.getOrDefault(parent, new Vector3f(0, 0, 0));
        else parentPivot = new Vector3f(0, 0, 0);
        transform.translate(new Vector3f(pivot).sub(parentPivot));
        RotHelper.rotation(transform, rotation);

        transformMap.put(name, Pair.of(transform, parent != null ?
                transformMap.getOrDefault(parent, Pair.of(null, new Transform())).getSecond().mul(transform) :
                transform));
        if (input.has("cubes")) {
            JsonArray cubes = input.get("cubes").getAsJsonArray();
            for (JsonElement cube : cubes) {
                addChildProcess(cube.getAsJsonObject(), "cube_layer");
            }
        }

        data.put("bone_name", name);
        if (input.has("locators")) {
            JsonObject locators = input.get("locators").getAsJsonObject();
            for (String key : locators.keySet()) {
                data.put("locator_name", key);
                JsonElement locator = locators.get(key);
                addChildProcess(locator.getAsJsonObject(), "locator_layer");
            }
        }
        skeletonBuilder.addBone(name, transform, parent, null);
    }

    @Override
    public void postProcess(JsonObject input, Context context) {}
}
