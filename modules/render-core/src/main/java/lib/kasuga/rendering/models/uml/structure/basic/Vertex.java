package lib.kasuga.rendering.models.uml.structure.basic;

import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.VertexData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.HashMap;

@Getter
public class Vertex {

    private final Vector3f position;

    private final HashMap<Mesh, HashMap<Material, Vector2f>> uvs;
    private final HashMap<Mesh, Vector3f> normals;

    @Nullable
    private final VertexData data;

    @Setter
    @NonNull
    private BoneBinding binding;

    /**
     * Position of this vertex inside its model's {@code Vertex[]} array, set by {@code MorphInstance}
     * construction. -1 until then. Backs the index-based morph result arrays (no per-vertex hashing).
     */
    @Setter
    private int index = -1;

    public Vertex(Vector3f position, @Nullable VertexData data) {
        this.position = position;
        this.uvs = new HashMap<>();
        this.normals = new HashMap<>();
        this.data = data;
    }

    public Vertex(Vector3f position, HashMap<Mesh, HashMap<Material, Vector2f>> uvs, HashMap<Mesh, Vector3f> normals, BoneBinding binding, @Nullable VertexData data) {
        this.position = position;
        this.uvs = uvs;
        this.normals = normals;
        this.data = data;
        this.binding = binding;
    }

    public Vertex(Vertex vertex, Vector3f position, HashMap<Mesh, Vector3f> normals) {
        this.position = position;
        this.uvs = vertex.uvs;
        this.normals = normals;
        this.data = vertex.data;
        this.binding = vertex.binding;
    }

    public void addUV(Mesh mesh, Material material, Vector2f uv) {
        uvs.computeIfAbsent(mesh, m -> new HashMap<>()).put(material, uv);
        normals.put(mesh, mesh.getNormal());
    }

    @Nullable
    public Vector2f getUV(Mesh mesh, Material material) {
        HashMap<Material, Vector2f> meshUVs = uvs.get(mesh);
        if (meshUVs == null) {
            return null;
        }
        return meshUVs.get(material);
    }

    public Vector3f getNormal(Mesh mesh) {
        return normals.getOrDefault(mesh, new Vector3f());
    }
}
