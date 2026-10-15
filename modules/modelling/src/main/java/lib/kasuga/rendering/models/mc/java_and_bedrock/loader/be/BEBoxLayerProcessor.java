package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.be;

import com.google.gson.JsonArray;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCMeshData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.loader.CubeVerticesMapper;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.mc.util.JsonHelper;
import lib.kasuga.rendering.models.uml.loaders.structural.Context;
import lib.kasuga.rendering.models.uml.loaders.structural.Processor;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.structure.Pair;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.HashMap;

import static lib.kasuga.rendering.models.mc.util.Direction.DOWN;
import static lib.kasuga.rendering.models.mc.util.Direction.UP;

public class BEBoxLayerProcessor extends Processor<JsonArray> {

    @Override
    public void process(JsonArray input, Context context) {
        boolean mirror = (Boolean) context.getDataOrDefault("mirror", false);
        Material material = (Material) context.getData("material");
        float texWidth = material.getTexture("root").getWidth();
        float texHeight = material.getTexture("root").getHeight();
        Vector2f textureOffset = JsonHelper.jsonToV2f(input).mul(1 / texWidth, 1 / texHeight);
        Vector3f org = (Vector3f) context.getData("origin");
        Vector3f size = (Vector3f) context.getData("size");
        Vector3f cubeMax = new Vector3f(org).add(size);
        HashMap<Direction, Vector2f> faceAndSize = new HashMap<>();
        HashMap<Direction, Vector2f> positions = new HashMap<>();

        for (Direction d : Direction.values()) {
            faceAndSize.put(d, flatten(getPositionPair(org, cubeMax, d)));
        }
        generatePositions(faceAndSize, positions, textureOffset, mirror);
        HashMap<Direction, Pair<Vector2f, Vector2f>> faceData = getAllFaceData(faceAndSize, positions, mirror, false);
        CubeVerticesMapper mapper = (CubeVerticesMapper) context.getData("mapper");
        for (Direction d : Direction.values()) {
            Pair<Vector2f, Vector2f> data = faceData.get(d);
            MCMeshData meshData = new MCMeshData(
                    (Boolean) context.getDataOrDefault("visible", true),
                    (Boolean) context.getDataOrDefault("emissive", false),
                    true, false, d, new Vector4f(1.0f, 1.0f, 1.0f, 1.0f)
            );
            boolean isUpAndDown = d == UP || d == DOWN;
            float defaultRotation = isUpAndDown ? 270 : 90;
            mapper.map(data.getFirst().mul(16f / texWidth, 16f / texHeight), data.getSecond().mul(16f / texWidth, 16f / texHeight),
                    material, d, defaultRotation, null, meshData, true, false);
        }
    }

    public Pair<Vector2f, Vector2f> getFaceData(HashMap<Direction, Vector2f> faceAndSize, HashMap<Direction, Vector2f> positions, boolean mirror, boolean flipV, Direction direction) {
        return dealWithMirror(direction, mirror, flipV, new Vector2f(positions.get(direction)), new Vector2f(faceAndSize.get(direction)));
    }

    public HashMap<Direction, Pair<Vector2f, Vector2f>> getAllFaceData(HashMap<Direction, Vector2f> faceAndSize, HashMap<Direction, Vector2f> positions, boolean mirror, boolean flipV) {
        HashMap<Direction, Pair<Vector2f, Vector2f>> map = new HashMap<>();
        for (Direction direction : Direction.values()) {
            map.put(direction, getFaceData(faceAndSize, positions, mirror, flipV, direction));
        }
        return map;
    }

    private void generatePositions(HashMap<Direction, Vector2f> faceAndSize, HashMap<Direction, Vector2f> positions, Vector2f textureOffset, boolean mirror) {
        float we_width = faceAndSize.get(Direction.WEST).x();
        float ud_width = faceAndSize.get(Direction.UP).x();
        float ud_height = faceAndSize.get(Direction.UP).y();
        float ns_width = faceAndSize.get(Direction.NORTH).x();

        positions.put(Direction.UP, new Vector2f(textureOffset).add(we_width, 0));
        positions.put(Direction.DOWN, new Vector2f(textureOffset).add(we_width + ud_width, 0));
        positions.put(Direction.NORTH, new Vector2f(textureOffset).add(we_width, ud_height));
        positions.put(Direction.SOUTH, new Vector2f(textureOffset).add(2 * we_width + ns_width, ud_height));
        positions.put(mirror ? Direction.WEST : Direction.EAST, new Vector2f(textureOffset).add(0, ud_height));
        positions.put(mirror ? Direction.EAST : Direction.WEST, new Vector2f(textureOffset).add(we_width + ns_width, ud_height));
    }

    public static Pair<Vector3f, Vector3f> getPositionPair(Vector3f org, Vector3f max, Direction direction) {
        return switch (direction) {
            case UP -> Pair.of(new Vector3f(org.x, max.y, org.z), new Vector3f(max));
            case DOWN -> Pair.of(new Vector3f(org), new Vector3f(max.x, org.y, max.z));
            case NORTH -> Pair.of(new Vector3f(org), new Vector3f(max.x, max.y, org.z));
            case SOUTH -> Pair.of(new Vector3f(org.x, org.y, max.z), new Vector3f(max));
            case WEST -> Pair.of(new Vector3f(org), new Vector3f(org.x, max.y, max.z));
            case EAST -> Pair.of(new Vector3f(max.x, org.y, org.z), new Vector3f(max));
        };
    }

    public static Vector2f flatten(Vector3f pos1, Vector3f pos2) {
        if (pos1.y() - pos2.y() == 0) {
            return new Vector2f(pos2.x() - pos1.x(), pos2.z() - pos1.z());
        } else if (pos1.z() - pos2.z() == 0) {
            return new Vector2f(pos2.x() - pos1.x(), pos2.y() - pos1.y());
        } else if (pos1.x() - pos2.x() == 0) {
            return new Vector2f(pos2.z() - pos1.z(), pos2.y() - pos1.y());
        }
        return new Vector2f();
    }

    public static Vector2f flatten(Pair<Vector3f, Vector3f> pair) {
        return flatten(pair.getFirst(), pair.getSecond());
    }

    public static Pair<Vector2f, Vector2f> dealWithMirror(Direction direction, boolean flipU, boolean flipV, Vector2f org, Vector2f size) {
        flipU = flipU && !(direction == Direction.EAST || direction == Direction.WEST);
        if (flipU) {
            float cache = org.x() + size.x();
            size.setComponent(0, -size.x());
            org.setComponent(0, cache);
        }
        if (flipV) {
            float cache = org.y() + size.y();
            size.setComponent(1, -size.y());
            org.setComponent(1, cache);
        }
        return Pair.of(org, size);
    }
}
