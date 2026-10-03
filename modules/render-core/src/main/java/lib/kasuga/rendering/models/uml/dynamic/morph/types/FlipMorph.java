package lib.kasuga.rendering.models.uml.dynamic.morph.types;

import lombok.Getter;
import lombok.Setter;

@Getter
public class FlipMorph<IdType> implements MorphType<Object, Float, IdType> {

    @Setter
    private int morphTypeIndex = -1;

    private final IdType identifier;
    private final MorphType<Object, ?, IdType> referenceMorph;

    public FlipMorph(IdType identifier, MorphType referenceMorph) {
        this.identifier = identifier;
        this.referenceMorph = referenceMorph;
    }

    @Override
    public Object getOriginal() {
        return referenceMorph.getOriginal();
    }

    @Override
    public boolean isValidMorphInput(Object input, float percentage, float factor) {
        return referenceMorph.isValidMorphInput(input, percentage, factor);
    }

    /** Returns the inverted weight. The consumer maps this to the referenced morph. */
    @Override
    public Float morph(Object input, float percentage, float factor) {
        return 1.0f - (percentage * factor);
    }
}
