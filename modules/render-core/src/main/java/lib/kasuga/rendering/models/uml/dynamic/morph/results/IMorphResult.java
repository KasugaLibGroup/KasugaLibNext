package lib.kasuga.rendering.models.uml.dynamic.morph.results;

/**
 * Result of applying morphs to an element. Each property that was morphed
 * holds the computed value; unmorphed properties are {@code null}.
 *
 * @param <MorphedElement> the type of the original element (Vertex, Bone, Mesh, Material)
 */
public interface IMorphResult<MorphedElement> {

    MorphedElement getOriginal();

    default boolean isEmpty() { return true; }

    /** Clear all accumulated morph deltas for the next update cycle. */
    void reset();
}
