package lib.kasuga.rendering.models.uml.dynamic.morph.holder;

import lib.kasuga.rendering.models.uml.dynamic.morph.types.MorphType;
import lombok.Getter;

import java.util.*;

/**
 * Static morph data holder — groups one {@link MorphType} definition with the set of
 * {@link MorphedElement}s it applies to. This is a pure data structure used during
 * model loading and morph construction; it does not perform any dynamic morph blending.
 *
 * @param <MorphedElement> the type of element being morphed (Vertex, Bone, Mesh, Material)
 * @param <IdType>         the identifier type for morph lookup
 */
@Getter
public class MorphHolder<MorphedElement, IdType> implements IMorphHolder<MorphedElement, IdType> {

    private final IdType identifier;
    private final MorphType<MorphedElement, ?, IdType> morphPrototype;
    private final List<MorphedElement> targetElements;

    public MorphHolder(IdType identifier, MorphType<MorphedElement, ?, IdType> morphPrototype,
                       List<MorphedElement> targetElements) {
        this.identifier = identifier;
        this.morphPrototype = morphPrototype;
        this.targetElements = new ArrayList<>(targetElements);
    }

    public MorphHolder(IdType identifier, MorphType<MorphedElement, ?, IdType> morphPrototype) {
        this(identifier, morphPrototype, new ArrayList<>());
    }

    @Override
    public Collection<MorphedElement> getMorphedElements() {
        return targetElements;
    }

    /** Add an element affected by this morph. */
    public void addElement(MorphedElement element) {
        targetElements.add(element);
    }

    /** Remove an element from this morph's target list. */
    public boolean removeElement(MorphedElement element) {
        return targetElements.remove(element);
    }

    @Override
    public int elementCount() {
        return targetElements.size();
    }

    /**
     * Create a copy of this holder with a different identifier.
     * The prototype's target value is shared (not deep-copied).
     */
    public MorphHolder<MorphedElement, IdType> withIdentifier(IdType newId) {
        return new MorphHolder<>(newId, morphPrototype, new ArrayList<>(targetElements));
    }
}
