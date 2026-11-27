package lib.kasuga.registration.minecraft.block.shape;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Stable serialized property values identifying one compiled block state without retaining a state. */
public record BlockStateKey(Map<String, String> values) implements Comparable<BlockStateKey> {
    public BlockStateKey {
        Objects.requireNonNull(values, "values");
        TreeMap<String, String> copy = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "property name");
            String value = Objects.requireNonNull(entry.getValue(), "property value");
            if (name.isEmpty() || value.isEmpty()) {
                throw new IllegalArgumentException("Block-state property names and values must not be empty");
            }
            if (copy.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("Duplicate block-state property '" + name + "'");
            }
        }
        values = Collections.unmodifiableMap(copy);
    }

    public static BlockStateKey of(BlockState state) {
        Objects.requireNonNull(state, "state");
        TreeMap<String, String> values = new TreeMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            values.put(entry.getKey().getName(), propertyValue(entry.getKey(), entry.getValue()));
        }
        return new BlockStateKey(values);
    }

    @Override
    public int compareTo(BlockStateKey other) {
        Objects.requireNonNull(other, "other");
        Iterator<Map.Entry<String, String>> left = values.entrySet().iterator();
        Iterator<Map.Entry<String, String>> right = other.values.entrySet().iterator();
        while (left.hasNext() && right.hasNext()) {
            Map.Entry<String, String> leftEntry = left.next();
            Map.Entry<String, String> rightEntry = right.next();
            int name = leftEntry.getKey().compareTo(rightEntry.getKey());
            if (name != 0) return name;
            int value = leftEntry.getValue().compareTo(rightEntry.getValue());
            if (value != 0) return value;
        }
        return Boolean.compare(left.hasNext(), right.hasNext());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValue(Property<?> property, Comparable<?> value) {
        return ((Property) property).getName((Comparable) value);
    }
}
