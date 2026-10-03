package lib.kasuga.rendering.models.uml.dynamic.morph.types;

public interface MorphType<MorphedElement, MorphOutput, Identifier> {

    boolean isValidMorphInput(MorphedElement input, float percentage, float factor);

    MorphOutput morph(MorphedElement input, float percentage, float factor);

    /** The element that this morph applies to (e.g., Vertex, Bone, Material). */
    MorphedElement getOriginal();

    Identifier getIdentifier();

    /**
     * Model-scoped ordinal assigned at registration time by {@code Morph.addMorph}; -1 until then.
     * Backs the {@code MorphInstance} factor/value arrays so the hot per-vertex morph loops avoid hashing.
     */
    int getMorphTypeIndex();

    /** Set once by {@code Morph.addMorph} at registration. */
    void setMorphTypeIndex(int index);
}
