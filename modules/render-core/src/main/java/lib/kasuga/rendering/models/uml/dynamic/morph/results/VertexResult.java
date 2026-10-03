package lib.kasuga.rendering.models.uml.dynamic.morph.results;

import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lombok.Getter;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.Map;

@Getter
public class VertexResult implements IMorphResult<Vertex> {

    private final Vertex original;

    /** Position delta (VertexPosMorph), null if unchanged. */
    private Vector3f position;
    @Getter(lombok.AccessLevel.NONE)
    private Vector3f positionCache;

    /** Normal deltas per mesh (VertexNormalMorph), null/empty if unchanged. */
    private final Map<Mesh, Vector3f> normals;

    /** UV deltas per mesh → material (VertexUvMorph), null/empty if unchanged. */
    private final Map<Mesh, Map<Material, Vector2f>> uvs;

    /** Tangent delta (VertexTangentMorph), null if unchanged. */
    private Vector4f tangent;

    public VertexResult(Vertex original) {
        this.original = original;
        this.normals = new HashMap<>();
        this.uvs = new HashMap<>();
    }

    /** Accumulate position delta (linear superposition for multiple VertexPosMorphs). */
    public void addPosition(Vector3f delta) {
        addPosition(delta.x, delta.y, delta.z);
    }

    /** Reuses storage while keeping the public null-after-reset contract. */
    public void addPosition(float x, float y, float z) {
        if (position == null) {
            if (positionCache == null) positionCache = new Vector3f();
            position = positionCache.set(x, y, z);
        } else {
            position.add(x, y, z);
        }
    }

    /** Accumulate normal delta per mesh. */
    public void addNormal(Mesh mesh, Vector3f delta) {
        normals.merge(mesh, delta, (prev, d) -> prev.add(d));
    }

    /** Accumulate UV delta per mesh+material. */
    public void addUv(Mesh mesh, Material material, Vector2f delta) {
        uvs.computeIfAbsent(mesh, k -> new HashMap<>())
                .merge(material, delta, (prev, d) -> prev.add(d));
    }

    /** Accumulate tangent delta. */
    public void addTangent(Vector4f delta) {
        if (this.tangent == null) this.tangent = new Vector4f(delta);
        else this.tangent.add(delta);
    }

    @Override
    public void reset() {
        this.position = null;
        this.normals.clear();
        this.uvs.clear();
        this.tangent = null;
    }

    @Override
    public boolean isEmpty() {
        return position == null && normals.isEmpty() && uvs.isEmpty() && tangent == null;
    }
}
