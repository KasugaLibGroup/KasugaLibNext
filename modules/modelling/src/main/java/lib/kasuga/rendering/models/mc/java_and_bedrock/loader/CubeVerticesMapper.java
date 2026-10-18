package lib.kasuga.rendering.models.mc.java_and_bedrock.loader;

import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCMeshData;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.mc.util.box_layer.FaceInfo;
import lib.kasuga.rendering.models.mc.util.box_layer.VertexCorner;
import lib.kasuga.rendering.models.uml.loaders.VertexBuilder;
import lib.kasuga.rendering.models.uml.loaders.structural.Loader;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.HashMap;

public class CubeVerticesMapper {

    public static final int NUM_VERTICES = 8;

    private final HashMap<FaceInfo.VertexInfo, VertexBuilder> vertexCluster;

    private final HashMap<Direction, MCMeshBuilder> meshes;

    @Getter
    @Setter
    private String boneName;

    public CubeVerticesMapper(Vector3f min, Vector3f size) {
        boneName = null;
        vertexCluster = new HashMap<>();
        meshes = new HashMap<>();
        Vector3f max = new Vector3f(min).add(size);
        for (int i = 0; i < NUM_VERTICES; i++) {
            VertexCorner xCorner = (i & 1) == 0 ? VertexCorner.MIN_X : VertexCorner.MAX_X;
            VertexCorner yCorner = (i & 2) == 0 ? VertexCorner.MIN_Y : VertexCorner.MAX_Y;
            VertexCorner zCorner = (i & 4) == 0 ? VertexCorner.MIN_Z : VertexCorner.MAX_Z;
            FaceInfo.VertexInfo vPos = new FaceInfo.VertexInfo(xCorner, yCorner, zCorner);
            Vector3f pos = new Vector3f(
                    xCorner == VertexCorner.MIN_X ? min.x() : max.x(),
                    yCorner == VertexCorner.MIN_Y ? min.y() : max.y(),
                    zCorner == VertexCorner.MIN_Z ? min.z() : max.z()
            );
            VertexBuilder builder = new VertexBuilder();
            builder.setPosition(pos);
            vertexCluster.put(vPos, builder);
        }
    }

    public void map(Vector2f uvOrg, Vector2f uvSize, Material material, Direction direction,
                    float uvRotation, @Nullable Transform transform, @Nullable MCMeshData data,
                    boolean flipU, boolean flipV) {
        MCMeshBuilder meshBuilder = new MCMeshBuilder(uvOrg, uvSize, direction, uvRotation);
        if (flipU) {
            meshBuilder.flipU();
        }
        if (flipV) {
            meshBuilder.flipV();
        }
        meshBuilder.setMaterial(material);
        meshBuilder.setData(data);
        if (transform != null) {
            meshBuilder.setTransform(transform);
        }
        meshes.put(direction, meshBuilder);
    }

    public Pair<Collection<Mesh>, Collection<Vertex>> build(Loader loader) {
        HashMap<Direction, Pair<Mesh, FaceInfo.VertexInfo[]>> builtMeshes = new HashMap<>();
        meshes.forEach((k, v) -> builtMeshes.put(k, v.build(vertexCluster)));
        HashMap<FaceInfo.VertexInfo, Vertex> builtVertices = new HashMap<>();

        final String bName = boneName;
        vertexCluster.forEach((k, v) -> {
                    if (bName != null) {v.bone(bName);}
                    builtVertices.put(k, v.build(loader));
                }
        );

        builtMeshes.forEach((k, v) -> {
                Mesh mesh = v.getFirst();
                FaceInfo.VertexInfo[] vertexInfos = v.getSecond();
                for (int i = 0; i < vertexInfos.length; i++) {
                    Vertex vertex = builtVertices.get(vertexInfos[i]);
                    mesh.getVertices()[i] = vertex;
                }
        });
        return Pair.of(builtMeshes.values().stream().map(Pair::getFirst).toList(), builtVertices.values());
    }
}
