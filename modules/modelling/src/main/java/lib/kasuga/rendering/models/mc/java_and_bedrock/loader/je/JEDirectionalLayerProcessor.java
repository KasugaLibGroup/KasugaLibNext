package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.je;

import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCMeshData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.je.JEFace;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.je.JEModelData;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.CubeVerticesMapper;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Processor;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import org.joml.Vector2f;
import org.joml.Vector4f;
import java.util.Map;

public class JEDirectionalLayerProcessor extends Processor<Map.Entry<Direction, JEFace>> {

    @Override
    public void process(Map.Entry<Direction, JEFace> entry, Context context) {
        Direction direction = entry.getKey();
        JEFace face = entry.getValue();
        JEModelData modelData = (JEModelData) context.getData("modelData");

        Material material = (Material) context.getData("material");

        Vector2f uvOrg = new Vector2f(face.getUvFrom()).div(16f);
        Vector2f uvSize = new Vector2f(face.getUvTo()).sub(face.getUvFrom()).div(16f);

        MCMeshData data = new MCMeshData(
                true, false, true, modelData.isAmbientOcclusion(),
                direction, new Vector4f(1f, 1f, 1f, 1f)
        );

        int rotation = face.getRotation();
        boolean isUpAndDown = direction == Direction.UP || direction == Direction.DOWN;
        float defaultRotation = isUpAndDown ? 270 : 90;

        CubeVerticesMapper mapper = (CubeVerticesMapper) context.getData("mapper");
        mapper.map(uvOrg, uvSize, material, direction, (rotation + defaultRotation) % 360,
                null, data, true, false);
    }
}
