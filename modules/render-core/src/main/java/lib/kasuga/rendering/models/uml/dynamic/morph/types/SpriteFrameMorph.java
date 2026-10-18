package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lib.kasuga.rendering.models.uml.structure.material.Material;
import lombok.Getter;
import lombok.Setter;

@Getter
public class SpriteFrameMorph<IdType> implements MorphType<Material, Integer, IdType> {

    @Setter
    private int morphTypeIndex = -1;

    private final Material original;
    private final IdType identifier;
    private final int spriteSetIndex;
    private final int targetFrame;

    public SpriteFrameMorph(Material original, IdType identifier, int spriteSetIndex, int targetFrame) {
        this.original = original;
        this.identifier = identifier;
        this.spriteSetIndex = spriteSetIndex;
        this.targetFrame = targetFrame;
    }

    @Override
    public boolean isValidMorphInput(Material input, float percentage, float factor) {
        return input != null && percentage >= 0f && percentage <= 1f && factor >= 0f;
    }

    @Override
    public Integer morph(Material input, float percentage, float factor) {
        float weight = percentage * factor;
        return weight >= 0.5f ? targetFrame : 0;
    }
}
