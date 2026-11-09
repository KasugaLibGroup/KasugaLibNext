package lib.kasuga.registration.data_driven.state;

import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** A property whose finite, serialized values are supplied by data instead of a Java enum. */
public final class StringStateProperty extends Property<String> {
    private final List<String> values;

    StringStateProperty(String name, List<String> values) {
        super(name, String.class);
        this.values = List.copyOf(values);
    }

    @Override
    public Collection<String> getPossibleValues() {
        return values;
    }

    @Override
    public String getName(String value) {
        return value;
    }

    @Override
    public Optional<String> getValue(String value) {
        return values.contains(value) ? Optional.of(value) : Optional.empty();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof StringStateProperty that
                && super.equals(other) && values.equals(that.values);
    }

    @Override
    public int generateHashCode() {
        return 31 * super.generateHashCode() + values.hashCode();
    }
}
