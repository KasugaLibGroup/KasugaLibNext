package lib.kasuga.rendering.models.uml.dynamic.morph.holder;

import lib.kasuga.rendering.models.uml.dynamic.morph.types.MorphType;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * A morph group that composes multiple {@link IMorphHolder} instances into a single
 * controller. Each sub-holder has a weight factor controlling its contribution.
 * GroupMorph implements {@link IMorphHolder} but is itself a group container, not
 * a single morph definition.
 */
@Getter
public class GroupMorph<IdType> implements IMorphHolder<Object, IdType> {

    private final IdType identifier;
    private final List<IMorphHolder<?, IdType>> subHolders;
    private final Map<IMorphHolder<?, IdType>, Float> factors;

    public GroupMorph(IdType identifier) {
        this.identifier = identifier;
        this.subHolders = new ArrayList<>();
        this.factors = new IdentityHashMap<>();
    }

    public GroupMorph(IdType identifier, List<IMorphHolder<?, IdType>> subHolders,
                      Map<IMorphHolder<?, IdType>, Float> factors) {
        this.identifier = identifier;
        this.subHolders = new ArrayList<>(subHolders);
        this.factors = new IdentityHashMap<>(factors);
    }

    /** Add a sub-holder with the given weight factor. */
    public void addHolder(IMorphHolder<?, IdType> holder, float factor) {
        subHolders.add(holder);
        factors.put(holder, factor);
    }

    /** Remove a sub-holder. */
    public void removeHolder(IMorphHolder<?, IdType> holder) {
        subHolders.remove(holder);
        factors.remove(holder);
    }

    @Override
    @Nullable
    public MorphType<Object, ?, IdType> getMorphPrototype() {
        return null;
    }

    @Override
    public Collection<Object> getMorphedElements() {
        List<Object> all = new ArrayList<>();
        for (IMorphHolder<?, IdType> holder : subHolders) {
            all.addAll(holder.getMorphedElements());
        }
        return all;
    }

    @Override
    public int elementCount() {
        return subHolders.stream().mapToInt(IMorphHolder::elementCount).sum();
    }

    @Override
    public boolean isGroup() {
        return true;
    }
}
