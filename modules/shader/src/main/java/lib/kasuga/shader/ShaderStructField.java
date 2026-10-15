package lib.kasuga.shader;

import java.util.Objects;
import java.util.function.Function;

/** Typed key for one field of a nominal shader struct. */
public final class ShaderStructField<T extends ShaderExpression> {
    private final Object owner;
    private final String name;
    private final ShaderValueType type;
    private final Function<ShaderIr.Expression, T> wrapper;

    ShaderStructField(
            Object owner,
            String name,
            ShaderValueType type,
            Function<ShaderIr.Expression, T> wrapper
    ) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.name = ShaderNames.requireIdentifier(name);
        this.type = Objects.requireNonNull(type, "type");
        this.wrapper = Objects.requireNonNull(wrapper, "wrapper");
    }

    public String name() {
        return name;
    }

    public ShaderValueType type() {
        return type;
    }

    T wrap(ShaderIr.Expression expression) {
        return wrapper.apply(expression);
    }

    boolean belongsTo(Object owner) {
        return this.owner == owner;
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof ShaderStructField<?> other
                && owner == other.owner
                && name.equals(other.name)
                && type.equals(other.type);
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(owner) + Objects.hash(name, type);
    }

    @Override
    public String toString() {
        return type.glslName() + " " + name;
    }
}
