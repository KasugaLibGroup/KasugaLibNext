package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector2f;

@Getter
public class VertexUvMorph<IdType> implements MorphType<Vertex, Vector2f, IdType> {

    @Setter
    private int morphTypeIndex = -1;

    private final Vertex original;
    private final IdType identifier;
    private final Mesh mesh;
    private final Material material;
    private final Vector2f targetUv;

    public VertexUvMorph(Vertex original, IdType identifier, Mesh mesh, Material material, Vector2f targetUv) {
        this.original = original;
        this.identifier = identifier;
        this.mesh = mesh;
        this.material = material;
        this.targetUv = targetUv;
    }

    @Override
    public boolean isValidMorphInput(Vertex input, float percentage, float factor) {
        return input != null && percentage >= 0f && percentage <= 1f && factor >= 0f;
    }

    @Override
    public Vector2f morph(Vertex input, float percentage, float factor) {
        float weight = percentage * factor;
        Vector2f currentUv = original.getUV(mesh, material);
        if (currentUv == null) return new Vector2f(targetUv).mul(weight);
        return new Vector2f(
                (targetUv.x() - currentUv.x()) * weight,
                (targetUv.y() - currentUv.y()) * weight
        );
    }
}
