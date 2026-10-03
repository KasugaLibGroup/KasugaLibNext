package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lib.kasuga.rendering.models.uml.structure.material.Material;
import lombok.Getter;
import lombok.Setter;
import lib.kasuga.rendering.models.uml.dynamic.morph.BlendMode;
import org.joml.Vector4f;

@Getter
public class MaterialColorMorph<IdType> implements MorphType<Material, Vector4f, IdType> {

    @Setter
    private int morphTypeIndex = -1;

    private final Material original;
    private final IdType identifier;
    private final Vector4f targetColor;
    private final BlendMode blendMode;

    public MaterialColorMorph(Material original, IdType identifier, Vector4f targetColor) {
        this(original, identifier, targetColor, BlendMode.MULTIPLY);
    }

    public MaterialColorMorph(Material original, IdType identifier, Vector4f targetColor, BlendMode blendMode) {
        this.original = original;
        this.identifier = identifier;
        this.targetColor = targetColor;
        this.blendMode = blendMode;
    }

    @Override
    public boolean isValidMorphInput(Material input, float percentage, float factor) {
        return input != null && percentage >= 0f && percentage <= 1f && factor >= 0f;
    }

    @Override
    public Vector4f morph(Material input, float percentage, float factor) {
        float weight = percentage * factor;
        if (blendMode == BlendMode.ADD) {
            return new Vector4f(targetColor).mul(weight);
        }
        return new Vector4f(
                1f + (targetColor.x() - 1f) * weight,
                1f + (targetColor.y() - 1f) * weight,
                1f + (targetColor.z() - 1f) * weight,
                1f + (targetColor.w() - 1f) * weight
        );
    }
}
