package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.CubeVerticesMapper;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.mc.util.RotHelper;
import lib.kasuga.rendering.models.mc.util.JsonHelper;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.util.TransformStack;
import lib.kasuga.structure.Pair;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.HashMap;
import java.util.Stack;

public class BECubeLayer extends Layer<JsonObject> {

    @Override
    @SuppressWarnings("unchecked")
    public void process(JsonObject input, Context context) {
        HashMap<String, Vector3f> map = (HashMap<String, Vector3f>) context.getData("pivot_map");
        HashMap<String, Pair<Transform, Transform>> transformMap = (HashMap<String, Pair<Transform, Transform>>) context.getData("transform_map");
        Vector3f origin = JsonHelper.jsonToV3f(input.get("origin")).mul(1 / 16f);
        Vector3f size = JsonHelper.jsonToV3f(input.get("size")).mul(1 / 16f);

        origin.x = -1 * (origin.x() + size.x());
        Vector3f pivot = input.has("pivot") ?
                JsonHelper.jsonToV3f(input.get("pivot")) :
                new Vector3f(0, 0, 0);
        pivot.mul(-1/16f, 1/16f, 1/16f);

        String parent = (String) context.getData("bone_name");
        Vector3f parentPivot;
        if (parent == null) parentPivot = new Vector3f();
        else parentPivot = map.getOrDefault(parent, new Vector3f());
        Vector3f transformPivot = new Vector3f(pivot).sub(parentPivot);

        context.setTemp("pivot", pivot);

        Vector3f rotation = input.has("rotation") ?
                JsonHelper.jsonToV3f(input.get("rotation")) :
                new Vector3f(0, 0, 0);


        Transform transform = new Transform();
        transform.translate(transformPivot);
        RotHelper.rotation(transform, rotation);

        boolean mirror = JsonHelper.jsonToBool(input, "mirror", false);
        boolean visible = JsonHelper.jsonToBool(input, "visible", true);
        boolean emissive = JsonHelper.jsonToBool(input, "emissive", false);
        float inflate = JsonHelper.jsonToFloat(input, "inflate", 0) / 16f;

        // transform.scale(1 + inflate / size.x(), 1 + inflate / size.y(), 1 + inflate / size.z());
        float sx = size.x() == 0 ? 1f : 1f + inflate / size.x();
        float sy = size.y() == 0 ? 1f : 1f + inflate / size.y();
        float sz = size.z() == 0 ? 1f : 1f + inflate / size.z();
        transform.scale(sx, sy, sz);

        Transform parentAbs = transformMap.getOrDefault(parent, Pair.of(null, new Transform())).getSecond();
        Transform absTransform = parentAbs.copy().mul(transform);
        context.setTemp("abs_transform", absTransform);

        JsonElement uvElement = input.get("uv");
        CubeVerticesMapper mapper = new CubeVerticesMapper(origin, size);
        context.setTemp("mapper", mapper);
        HashMap<String, Object> data = context.getData();
        data.put("mapper", mapper);
        data.put("mirror", mirror);
        data.put("origin", origin);
        data.put("visible", visible);
        data.put("emissive", emissive);
        data.put("size", size);
        if (uvElement instanceof JsonArray uvArray) addChildProcess(uvArray, "box_layer");
        else if (uvElement instanceof JsonObject uvObject) {
            HashMap<JsonElement, Direction> directionMap = new HashMap<>();
            data.put("direction", directionMap);
            for (String key : uvObject.keySet()) {
                JsonElement faceUV = uvObject.get(key);
                Direction d = Direction.fromString(key);
                directionMap.put(faceUV, d);
                addChildProcess(faceUV, "directional_layer");
            }
        }
    }

    @Override
    public void postProcess(JsonObject input, Context context) {
        CubeVerticesMapper mapper = (CubeVerticesMapper) context.getTemp("mapper");
        Transform absTransform = (Transform) context.getTemp("abs_transform");
        Vector3f pivot = (Vector3f) context.getTemp("pivot");
        String parent = (String) context.getData("bone_name");
        mapper.setBoneName(parent);
        Pair<Collection<Mesh>, Collection<Vertex>> meshesAndVertices = mapper.build(context.getLoader());
        context.getLoader().getMeshes().addAll(meshesAndVertices.getFirst());
        for (Vertex vertex : meshesAndVertices.getSecond()) {
            absTransform.apply(vertex.getPosition().sub(pivot));
            for (Vector3f normal : ((HashMap<Mesh, Vector3f>) vertex.getNormals()).values()) {
                absTransform.normal().transform(normal);
            }
        }
        context.getLoader().getVertices().addAll(meshesAndVertices.getSecond());
    }
}
