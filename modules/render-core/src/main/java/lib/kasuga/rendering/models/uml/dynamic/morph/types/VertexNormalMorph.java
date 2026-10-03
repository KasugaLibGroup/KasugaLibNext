package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

@Getter
public class VertexNormalMorph<IdType> implements MorphType<Vertex, Vector3f, IdType> {

    @Setter
    private int morphTypeIndex = -1;

    private final Vertex original;
    private final IdType identifier;
    private final Mesh mesh;
    private final Vector3f targetNormal;

    public VertexNormalMorph(Vertex original, IdType identifier, Mesh mesh, Vector3f targetNormal) {
        this.original = original;
        this.identifier = identifier;
        this.mesh = mesh;
        this.targetNormal = targetNormal;
    }

    @Override
    public boolean isValidMorphInput(Vertex input, float percentage, float factor) {
        return input != null && percentage >= 0f && percentage <= 1f && factor >= 0f;
    }

    @Override
    public Vector3f morph(Vertex input, float percentage, float factor) {
        float weight = percentage * factor;
        Vector3f currentNormal = original.getNormal(mesh);
        return new Vector3f(
                (targetNormal.x() - currentNormal.x()) * weight,
                (targetNormal.y() - currentNormal.y()) * weight,
                (targetNormal.z() - currentNormal.z()) * weight
        );
    }
}
