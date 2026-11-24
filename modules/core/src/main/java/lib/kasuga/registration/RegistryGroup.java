package lib.kasuga.registration;

import lib.kasuga.registration.core.IModifierConfigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

public class RegistryGroup extends Reg<RegistryGroup, Void> implements Consumer<Reg<?, ?>>, IModifierConfigure<RegistryGroup> {
    public List<Function<Reg<?, ?>, Reg<?, ?>>> transformation = new ArrayList<>();
    private BooleanSupplier dispatchPredicate = () -> true;

    public RegistryGroup transform(Function<Reg<?, ?>, Reg<?, ?>> function) {
        this.transformation.add(function);
        return self();
    }

    @Override
    public void accept(Reg<?, ?> reg) {
        reg.setParent(this);
    }

    public RegistryGroup dispatchWhen(BooleanSupplier predicate) {
        dispatchPredicate = Objects.requireNonNull(predicate, "predicate");
        return this;
    }

    public boolean isDispatchEnabled() {
        return dispatchPredicate.getAsBoolean();
    }

    @Override
    public void dispatchRegister(lib.kasuga.registration.core.RegisterContext<?> context) {
        if (isDispatchEnabled()) {
            super.dispatchRegister(context);
        }
    }

    @Override
    public Void getEntry() {
        return null;
    }
}
