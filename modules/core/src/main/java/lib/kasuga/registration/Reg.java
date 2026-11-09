package lib.kasuga.registration;

import lib.kasuga.registration.core.Modifier;
import lib.kasuga.registration.core.ModifierType;
import lib.kasuga.registration.core.RegisterContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class Reg<S extends Reg<?, ?>, T> implements TransformerProvider, EntrySupplier<T> {
    protected HashMap<ModifierType<?>, List<Modifier<?>>> modifiers = new HashMap<ModifierType<?>, List<Modifier<?>>>();

    // Class-keyed property functions, separate from Modifier/transform to avoid codegen coupling
    protected Map<Class<?>, List<Function<?, ?>>> propertyFunctions = new HashMap<>();

    protected Reg<?, ?> parent;

    protected Set<Reg<?, ?>> children = ConcurrentHashMap.newKeySet();


    @SuppressWarnings("unchecked")
    public S self() {
        return (S) this;
    }

    public S configure(Modifier<?> modifier) {
        this.modifiers.computeIfAbsent(modifier.getType(), (u)->new ArrayList<>()).add(modifier);
        return self();
    }

    public S configure(Consumer<? super S> consumer) {
        consumer.accept(self());
        return self();
    }

    public <L> L transform(ModifierType<L> modifierType, L element) {
        return transform(this, modifierType, element);
    }

    public <L> L transform(Reg<?, ?> origin, ModifierType<L> modifierType, L element) {
        if(this.parent != null) {
            element = this.parent.transform(origin, modifierType, element);
        }

        if(!this.modifiers.containsKey(modifierType)) {
            return element;
        }

        if(origin == this && modifierType.skipSelf)
            return element;

        for (Modifier<?> modifier : this.modifiers.get(modifierType)) {
            // noinspection unchecked cast
            Modifier<L> casted = (Modifier<L>) modifier;
            element = casted.transform(this, element);
        }

        return element;
    }

    public void register(RegisterContext<?> context) {}

    public void dispatchRegister(RegisterContext<?> context) {
        this.register(context);
        for (Reg<?, ?> child : this.children) {
            child.dispatchRegister(context);
        }
    }

    public S addChild(Reg<?, ?> child) {
        child.setParent(this);
        return self();
    }

    protected S addChildrenInternal(Reg<?, ?> child) {
        children.add(child);
        return self();
    }

    public S setParent(Reg<?, ?> parent) {
        this.parent = parent;
        this.parent.addChildrenInternal(this);
        return self();
    }

    public S consumeBy(Consumer<S> targetRegistration) {
        targetRegistration.accept(self());
        return self();
    }

    /**
     * @return the registration this one is attached to, or {@code null} for a root registration.
     */
    public Reg<?, ?> getParentReg() {
        return parent;
    }

    public Set<Reg<?, ?>> getChildren() {
        return children;
    }

    public <P> S withProperty(Class<P> type, Function<P, P> modifier) {
        propertyFunctions.computeIfAbsent(type, k -> new ArrayList<>()).add(modifier);
        return self();
    }

    @SuppressWarnings("unchecked")
    public <P> P applyProperties(Class<P> type, P value) {
        if (parent != null) {
            value = parent.applyProperties(type, value);
        }
        List<Function<?, ?>> mods = propertyFunctions.get(type);
        if (mods != null) {
            for (Function<?, ?> m : mods) {
                value = ((Function<P, P>) m).apply(value);
            }
        }
        return value;
    }
}
