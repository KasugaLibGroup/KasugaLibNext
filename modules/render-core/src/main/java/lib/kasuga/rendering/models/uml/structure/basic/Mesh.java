package lib.kasuga.rendering.models.uml.structure.basic;

import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.MeshData;
import lib.kasuga.rendering.models.uml.structure.material.data.TextureData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.VertexData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

@Getter
public class Mesh {

    private final Vertex[] vertices;

    private final Vector3f normal;

    @Setter
    private boolean visible = true, culled = false;

    @Nullable
    private final MeshData data;

    @NonNull
    @Setter
    private Transform transform;

    @NonNull
    private Material[] materials;

    public Mesh(Vertex[] vertices, Vector3f normal, @NonNull Transform transform, @NonNull Material[] materials, @Nullable MeshData data) {
        this.vertices = vertices;
        this.transform = transform;
        this.data = data;
        this.normal = normal;
        this.materials = materials;
    }

    public void calculateNormal() {
        Vector3f p1 = new Vector3f(),
                 p2 = new Vector3f(),
                 p3 = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int i = 0; i < vertices.length; i++) {
            int j1 = (i + 1) % materials.length;
            int j2 = (i + 2) % materials.length;
            p1.set(vertices[i].getPosition());
            p2.set(vertices[j1].getPosition());
            p3.set(vertices[j2].getPosition());
            normal.add(new Vector3f(p2).sub(p1).cross(p3.sub(p2)));
        }
        normal.normalize();
        this.normal.set(normal);
    }
}
