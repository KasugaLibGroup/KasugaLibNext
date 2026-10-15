package lib.kasuga.rendering.models.mc.java_and_bedrock.loader;

import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCMeshData;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.mc.util.box_layer.FaceInfo;
import lib.kasuga.rendering.models.mc.util.box_layer.FaceUVInfo;
import lib.kasuga.rendering.models.mc.util.box_layer.UVCorner;
import lib.kasuga.rendering.models.uml.loaders.VertexBuilder;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.*;

@Getter
public class MCMeshBuilder {

    private final HashMap<FaceInfo.VertexInfo, Vector2f> vertices;

    @SuppressWarnings("unchecked")
    private Pair<FaceInfo.VertexInfo, Vector2f>[] vertexPairs;

    private final Direction direction;

    private final Vector2f uvOrg, uvSize;

    private final float uvRotation;

//    public static final HashMap<Direction, FaceUVInfo> FACE_UV_INFO = getFaceInfo();

    @Setter
    private Material material;

    @Setter
    @NonNull
    private Transform transform;

    @Setter
    @Nullable
    private MCMeshData data;


    public MCMeshBuilder(Vector2f uvOrg, Vector2f uvSize, Direction direction, float uvRotation) {
        this.vertices = new HashMap<>();
        this.material = null;
        this.transform = new Transform();
        this.direction = direction;
        this.uvOrg = uvOrg;
        this.uvSize = uvSize;
        this.uvRotation = uvRotation;
    }

    private void genVertices() {
        float check = uvRotation % 90f;
        if (check != 0) {
            throw new IllegalArgumentException("UV rotation must be a multiple of 90 degrees");
        }
        check = uvRotation;
        while (check < 0) check += 360f;
        int offset = (int) (check / 90) % 4;
        FaceInfo faceInfo = FaceInfo.fromFacing(direction);

        vertexPairs = new Pair[4];
        for (FaceInfo.VertexInfo vertexInfo : faceInfo.getCorners()) {
            UVCorner corner = faceInfo.getCorner(vertexInfo);
            Pair<FaceInfo.VertexInfo, Vector2f> pair = Pair.of(vertexInfo, corner.getUVPosition(offset, uvOrg, uvSize));
            vertexPairs[corner.getIndex()] = pair;
            vertices.put(pair.getFirst(), pair.getSecond());
        }
    }

    public void flipU() {
        uvOrg.setComponent(0, uvOrg.x() + uvSize.x());
        uvSize.setComponent(0, - uvSize.x());
    }

    public void flipV() {
        uvOrg.setComponent(1, uvOrg.y() + uvSize.y());
        uvSize.setComponent(1, - uvSize.y());
    }

    public Vector2f getUV(FaceInfo.VertexInfo vertexInfo) {
        return vertices.get(vertexInfo);
    }

    public FaceInfo.VertexInfo[] getSortedVertices() {
        FaceInfo.VertexInfo[] sortedVertices = new FaceInfo.VertexInfo[4];
        for (int i = 0; i < 4; i++) {
            sortedVertices[i] = vertexPairs[i].getFirst();
        }
        return sortedVertices;
    }

    public Pair<Mesh, FaceInfo.VertexInfo[]> build(HashMap<FaceInfo.VertexInfo, VertexBuilder> vertexPos) {
        Objects.requireNonNull(material);
        genVertices();
        FaceInfo.VertexInfo[] vertexInfos = getSortedVertices();
        Vector3f pos1 = vertexPos.get(vertexInfos[0]).getPosition();
        Vector3f pos2 = vertexPos.get(vertexInfos[1]).getPosition();
        Vector3f pos3 = vertexPos.get(vertexInfos[2]).getPosition();
        Vector3f normal = getNormal(pos1, pos2, pos3);
        Mesh mesh = new Mesh(new Vertex[4], normal, transform, new Material[]{material} , data);
        for (FaceInfo.VertexInfo info : vertexInfos) {
            VertexBuilder builder = vertexPos.get(info);
            builder.uv(mesh, material, vertices.get(info));
            builder.normal(mesh, direction);
        }
        return Pair.of(mesh, vertexInfos);
    }

    public static Vector3f getNormal(Vector3f pos1, Vector3f pos2, Vector3f pos3) {
        Vector3f edge1 = new Vector3f(pos2).sub(pos1);
        Vector3f edge2 = new Vector3f(pos3).sub(pos1);
        Vector3f cross = edge1.cross(edge2);
        // Zero-area (degenerate) faces — zero-thickness panels in Bedrock/Blockbench models — cross to a
        // zero vector; normalize() would yield NaN normals that poison the shader's lighting (black).
        // The face itself is invisible; fall back to a valid direction so no NaN reaches the buffer.
        return cross.lengthSquared() <= 1e-12f ? new Vector3f(0f, 1f, 0f) : cross.normalize();
    }

    public static HashMap<Direction, FaceUVInfo> getFaceInfo() {
        HashMap<Direction, FaceUVInfo> faces = new HashMap<>();
        for (Direction direction : Direction.values()) {
            faces.put(direction, new FaceUVInfo(FaceInfo.fromFacing(direction)));
        }
        return faces;
    }
}
