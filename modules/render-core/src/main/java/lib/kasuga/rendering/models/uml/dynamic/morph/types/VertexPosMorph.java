package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector3f;

@Getter
public class VertexPosMorph<IdType> implements MorphType<Vertex, Vector3f, IdType> {

    @Setter
    private int morphTypeIndex = -1;

    private final Vertex original;
    private final IdType identifier;
    private final Vector3f targetPosition;

    public VertexPosMorph(Vertex original, IdType identifier, Vector3f targetPosition) {
        this.original = original;
        this.identifier = identifier;
        this.targetPosition = targetPosition;
    }

    @Override
    public boolean isValidMorphInput(Vertex input, float percentage, float factor) {
        return input != null && percentage >= 0f && percentage <= 1f && factor >= 0f;
    }

    @Override
    public Vector3f morph(Vertex input, float percentage, float factor) {
        float weight = percentage * factor;
        Vector3f originPos = original.getPosition();
        return new Vector3f(
                (targetPosition.x() - originPos.x()) * weight,
                (targetPosition.y() - originPos.y()) * weight,
                (targetPosition.z() - originPos.z()) * weight
        );
    }
}
