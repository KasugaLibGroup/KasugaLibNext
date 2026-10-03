package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector4f;

@Getter
public class VertexTangentMorph<IdType> implements MorphType<Vertex, Vector4f, IdType> {

    @Setter
    private int morphTypeIndex = -1;

    private final Vertex original;
    private final IdType identifier;
    private final Vector4f targetTangent;

    public VertexTangentMorph(Vertex original, IdType identifier, Vector4f targetTangent) {
        this.original = original;
        this.identifier = identifier;
        this.targetTangent = targetTangent;
    }

    @Override
    public boolean isValidMorphInput(Vertex input, float percentage, float factor) {
        return input != null && percentage >= 0f && percentage <= 1f && factor >= 0f;
    }

    @Override
    public Vector4f morph(Vertex input, float percentage, float factor) {
        float weight = percentage * factor;
        return new Vector4f(
                targetTangent.x() * weight,
                targetTangent.y() * weight,
                targetTangent.z() * weight,
                targetTangent.w() * weight
        );
    }
}
