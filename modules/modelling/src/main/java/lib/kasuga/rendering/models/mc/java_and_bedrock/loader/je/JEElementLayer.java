package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.je;

import lib.kasuga.rendering.models.mc.java_and_bedrock.data.je.JEElement;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.je.JEFace;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.je.JERotation;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.CubeVerticesMapper;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Layer;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.structure.Pair;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.Collection;
import java.util.Map;

public class JEElementLayer extends Layer<JEElement> {

    private static final float RESCALE_22_5 = (float) Math.sqrt(2 - Math.sqrt(2));
    private static final float RESCALE_45 = (float) Math.sqrt(2);
    private static final float EPSILON = 0.01f;

    @Override
    public void process(JEElement element, Context context) {
        Vector3f from = element.getFrom();
        Vector3f to = element.getTo();

        Transform rotationTransform = new Transform();
        Vector3f rescale = new Vector3f(1, 1, 1);
        Vector3f origin = new Vector3f(0, 0, 0);
        boolean hasRotation = element.hasRotation();

        if (hasRotation) {
            JERotation rot = element.getRotation();
            origin = new Vector3f(rot.getOrigin());
            rotationTransform = buildTransformFromRotation(rot);
            if (rot.isRescale()) {
                rescale = calculateRescale(rot);
            }
        }

        context.setTemp("rotation_transform", rotationTransform);
        context.setTemp("rescale", rescale);
        context.setTemp("origin", origin);
        context.setTemp("has_rotation", hasRotation);

        Vector3f size = element.getSize();
        CubeVerticesMapper mapper = new CubeVerticesMapper(from, size);

        context.setData("mapper", mapper);

        for (Map.Entry<Direction, JEFace> entry : element.getFaces().entrySet()) {
            addChildProcess(entry, "directional_layer");
        }
    }

    @Override
    public void postProcess(JEElement element, Context context) {
        CubeVerticesMapper mapper = (CubeVerticesMapper) context.getData("mapper");
        Transform rotationTransform = (Transform) context.getTemp("rotation_transform");
        Vector3f rescale = (Vector3f) context.getTemp("rescale");
        Vector3f origin = (Vector3f) context.getTemp("origin");
        boolean hasRotation = (boolean) context.getTemp("has_rotation");

        Pair<Collection<Mesh>, Collection<Vertex>> meshesAndVertices = mapper.build(context.getLoader());
        context.getLoader().getMeshes().addAll(meshesAndVertices.getFirst());

        int vertexCount = 0;
        for (Vertex vertex : meshesAndVertices.getSecond()) {
            Vector3f pos = vertex.getPosition();

            if (hasRotation) {
                rotationTransform.apply(pos);
                pos.sub(origin);
                pos.mul(rescale);
                pos.add(origin);

                for (Vector3f normal : vertex.getNormals().values()) {
                    rotationTransform.normal().transform(normal);
                }
            }
            vertexCount++;
        }

        context.getLoader().getVertices().addAll(meshesAndVertices.getSecond());
    }

    private Transform buildTransformFromRotation(JERotation rot) {
        Transform transform = new Transform();
        Vector3f origin = rot.getOrigin();
        transform.translate(origin.x(), origin.y(), origin.z());

        if (rot.multiAxis()) {
            Vector3f multiAxis = rot.getMultiAxis();
            Quaternionf rotation = QuaternionHelper.fromXYZDegrees(
                    multiAxis.x(), multiAxis.y(), multiAxis.z()
            );
            transform.mul(rotation);
        } else {
            float angleRadians = (float) Math.toRadians(rot.getAngle());
            String axis = rot.getAxisName();
            float ax = axis.equals("x") ? 1 : 0;
            float ay = axis.equals("y") ? 1 : 0;
            float az = axis.equals("z") ? 1 : 0;
            Quaternionf rotation = new Quaternionf().rotationAxis(angleRadians, ax, ay, az);
            transform.mul(rotation);
        }

        transform.translate(-origin.x(), -origin.y(), -origin.z());
        return transform;
    }

    private Vector3f calculateRescale(JERotation rot) {
        float scaleFactor;
        float absAngle = Math.abs(rot.getAngle());

        if (Math.abs(absAngle - 22.5f) < EPSILON) {
            scaleFactor = RESCALE_22_5;
        } else if (Math.abs(absAngle - 45.0f) < EPSILON) {
            scaleFactor = RESCALE_45;
        } else {
            return new Vector3f(1, 1, 1);
        }

        String axis = rot.getAxisName();
        return switch (axis.toLowerCase()) {
            case "x" -> new Vector3f(1, scaleFactor, scaleFactor);
            case "y" -> new Vector3f(scaleFactor, 1, scaleFactor);
            case "z" -> new Vector3f(scaleFactor, scaleFactor, 1);
            default -> new Vector3f(1, 1, 1);
        };
    }
}
