package lib.kasuga.rendering.models.uml.dynamic.morph.holder;

import lib.kasuga.rendering.models.uml.dynamic.morph.types.MorphType;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Common interface for morph data holders — both single-morph {@link MorphHolder}
 * and group-morph containers. A holder stores static morph definitions and the
 * elements they affect; it does not perform dynamic blending or state management.
 *
 * @param <MorphedElement> the type of element being morphed (Vertex, Bone, Mesh, Material)
 * @param <IdType>         the identifier type for morph lookup
 */
public interface IMorphHolder<MorphedElement, IdType> {

    /** The morph identifier used for activation / lookup. */
    IdType getIdentifier();

    /** The prototype morph that defines the morph behavior for elements in this holder. */
    @Nullable MorphType<MorphedElement, ?, IdType> getMorphPrototype();

    /** All elements affected by this morph holder (flattened through groups). */
    Collection<MorphedElement> getMorphedElements();

    /** Number of leaf-level elements affected. */
    int elementCount();

    /** Whether this holder is a group containing other holders. */
    default boolean isGroup() {
        return false;
    }
}
