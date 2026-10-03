package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lib.kasuga.rendering.models.uml.structure.material.Material;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector3f;
import org.joml.Vector4f;

@Getter
public class MaterialSpecularMorph<IdType> implements MorphType<Material, Vector4f, IdType> {

    @Setter
    private int morphTypeIndex = -1;

    private final Material original;
    private final IdType identifier;
    private final Vector4f targetSpecular;

    public MaterialSpecularMorph(Material original, IdType identifier, Vector4f targetSpecular) {
        this.original = original;
        this.identifier = identifier;
        this.targetSpecular = targetSpecular;
    }

    @Override
    public boolean isValidMorphInput(Material input, float percentage, float factor) {
        return input != null && percentage >= 0f && percentage <= 1f && factor >= 0f;
    }

    @Override
    public Vector4f morph(Material input, float percentage, float factor) {
        float weight = percentage * factor;
        return new Vector4f(
                targetSpecular.x() * weight,
                targetSpecular.y() * weight,
                targetSpecular.z() * weight,
                1f
        );
    }
}
