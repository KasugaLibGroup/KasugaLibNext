package lib.kasuga.rendering.models.uml.structure;

import lib.kasuga.rendering.models.uml.dynamic.morph.Morph;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.MeshData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.VertexData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.AnchorData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonData;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.*;

@Getter
public class Model {

    private final Vertex[] vertices;

    private final Mesh[] meshes;

    private final Bone[] bones;

    private final Skeleton skeleton;

    @Nullable
    private final ModelData modelData;

    private final MaterialSet materialSet;

    private final Map<Material, Set<Vertex>> vertexByMaterials;

    private final Map<Bone, Set<Vertex>> vertexByBones;

    private final Morph morph;

    private final MeshMode meshMode;


    public Model(Vertex[] vertices,
                 Mesh[] meshes,
                 Bone[] bones,
                 Skeleton skeleton,
                 MaterialSet materialSet,
                 MeshMode meshMode,
                 @Nullable ModelData modelData,
                 @Nullable Morph morph) {
        this.vertices = vertices;
        this.meshes = meshes;
        this.bones = bones;
        this.skeleton = skeleton;
        this.modelData = modelData;
        this.materialSet = materialSet;
        this.meshMode = meshMode;
        this.morph = morph == null ? new Morph(this) : morph;
        this.vertexByMaterials = new HashMap<>();
        this.vertexByBones = new HashMap<>();
        collectVertexByMaterials();
        collectVertexByBones();
    }

    protected void collectVertexByMaterials() {
        for (Mesh m : meshes) {
            for (Material mat : m.getMaterials()) {
                vertexByMaterials.computeIfAbsent(mat, material -> new HashSet<>());
                for (Vertex v : m.getVertices()) {
                    vertexByMaterials.get(mat).add(v);
                }
            }
        }
    }

    protected void collectVertexByBones() {
        for (Vertex v : vertices) {
            BoneBinding binding = v.getBinding();
            for (Pair<Bone, Float> weight : binding.getWeights()) {
                Bone bone = weight.getFirst();
                vertexByBones.computeIfAbsent(bone, b -> new HashSet<>()).add(v);
            }
        }
    }

    public Set<Vertex> getVertexByMaterial(Material m) {
        return vertexByMaterials.getOrDefault(m, Set.of());
    }

    public Set<Vertex> getVertexByBone(Bone b) {
        return vertexByBones.getOrDefault(b, Set.of());
    }
}
