package lib.kasuga.shader;

import java.util.Objects;

public final class ShaderVariable<T extends ShaderExpression> {
    private final ShaderAssignmentOwner owner;
    private final T reference;

    ShaderVariable(ShaderAssignmentOwner owner, T reference) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.reference = Objects.requireNonNull(reference, "reference");
    }

    public T get() {
        return reference;
    }

    public void set(T value) {
        owner.assign(reference, Objects.requireNonNull(value, "value"));
    }
}

@FunctionalInterface
interface ShaderAssignmentOwner {
    void assign(ShaderExpression target, ShaderExpression value);
}
