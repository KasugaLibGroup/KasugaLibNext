package lib.kasuga.registration.data_driven.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, validated properties and their defaults for one block. */
public final class StatePropertySet {
    public static final int MAX_VALUES_PER_PROPERTY = 64;
    public static final int MAX_STATES = 4096;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    private static final Pattern VANILLA_FIELD = Pattern.compile("[A-Z0-9_]+");
    private static final StatePropertySet EMPTY = new StatePropertySet(List.of());

    private final List<Entry<?>> entries;

    private StatePropertySet(List<Entry<?>> entries) {
        this.entries = List.copyOf(entries);
    }

    public static StatePropertySet empty() {
        return EMPTY;
    }

    /** Build the same kind of property set as a {@code state_properties} JSON object. */
    public static Builder builder() {
        return new Builder();
    }

    /** Parse a block's {@code state_properties} object; invalid declarations fail the whole block. */
    public static StatePropertySet parse(JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            throw new IllegalArgumentException("state_properties must be an object");
        }
        Builder builder = builder();
        for (var field : json.getAsJsonObject().entrySet()) {
            String name = field.getKey();
            if (!NAME.matcher(name).matches()) {
                throw error(name, "name must match [a-z0-9_]+");
            }
            if (!field.getValue().isJsonObject()) {
                throw error(name, "definition must be an object");
            }
            JsonObject definition = field.getValue().getAsJsonObject();
            String type = stringField(definition, "type", name);
            Entry<?> entry = switch (type) {
                case "boolean" -> parseBoolean(name, definition);
                case "integer" -> parseInteger(name, definition);
                case "enum" -> parseEnum(name, definition);
                case "vanilla" -> parseVanilla(name, definition);
                default -> throw error(name, "unknown type '" + type + "'");
            };
            builder.addEntry(entry);
        }
        return builder.build();
    }

    private static Entry<Boolean> parseBoolean(String name, JsonObject definition) {
        requireKeys(name, definition, Set.of("type", "default"));
        BooleanProperty property = BooleanProperty.create(name);
        boolean value = false;
        if (definition.has("default")) {
            JsonElement json = definition.get("default");
            if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isBoolean()) {
                throw error(name, "default must be a boolean");
            }
            value = json.getAsBoolean();
        }
        return new Entry<>(property, value);
    }

    private static Entry<Integer> parseInteger(String name, JsonObject definition) {
        requireKeys(name, definition, Set.of("type", "min", "max", "default"));
        int min = intField(definition, "min", name);
        int max = intField(definition, "max", name);
        int value = definition.has("default") ? intField(definition, "default", name) : min;
        return integerEntry(name, min, max, value);
    }

    private static Entry<Integer> integerEntry(String name, int min, int max, int value) {
        if (min < 0 || max <= min || max == Integer.MAX_VALUE
                || (long) max - min + 1 > MAX_VALUES_PER_PROPERTY) {
            throw error(name, "integer range must be non-negative, contain 2–"
                    + MAX_VALUES_PER_PROPERTY + " values, and end below 2147483647");
        }
        if (value < min || value > max) {
            throw error(name, "default must be within the integer range");
        }
        return new Entry<>(IntegerProperty.create(name, min, max), value);
    }

    private static Entry<String> parseEnum(String name, JsonObject definition) {
        requireKeys(name, definition, Set.of("type", "values", "default"));
        JsonElement raw = definition.get("values");
        if (raw == null || !raw.isJsonArray()) {
            throw error(name, "values must be an array of strings");
        }
        JsonArray array = raw.getAsJsonArray();
        if (array.size() < 2 || array.size() > MAX_VALUES_PER_PROPERTY) {
            throw error(name, "values must contain 2–" + MAX_VALUES_PER_PROPERTY + " entries");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonElement element : array) {
            String value = stringValue(element, "values", name);
            if (!NAME.matcher(value).matches() || !values.add(value)) {
                throw error(name, "values must be unique and match [a-z0-9_]+");
            }
        }
        List<String> ordered = List.copyOf(values);
        String value = definition.has("default") ? stringField(definition, "default", name) : ordered.get(0);
        if (!values.contains(value)) {
            throw error(name, "default must be one of the enum values");
        }
        return new Entry<>(new StringStateProperty(name, ordered), value);
    }

    private static Entry<?> parseVanilla(String name, JsonObject definition) {
        requireKeys(name, definition, Set.of("type", "property", "default"));
        String fieldName = stringField(definition, "property", name);
        if (!VANILLA_FIELD.matcher(fieldName).matches()) {
            throw error(name, "vanilla property must be an uppercase BlockStateProperties field name");
        }
        try {
            Field field = BlockStateProperties.class.getField(fieldName);
            if (!Modifier.isStatic(field.getModifiers()) || !Property.class.isAssignableFrom(field.getType())) {
                throw error(name, "'" + fieldName + "' is not a vanilla Property");
            }
            Property<?> property = (Property<?>) field.get(null);
            if (!name.equals(property.getName())) {
                throw error(name, "vanilla property '" + fieldName + "' is named '" + property.getName() + "'");
            }
            return vanillaEntry(name, property, definition.get("default"));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw error(name, "unknown vanilla property '" + fieldName + "'");
        }
    }

    private static <T extends Comparable<T>> Entry<T> vanillaEntry(String name, Property<T> property, JsonElement json) {
        T value;
        if (json == null) {
            value = property.getPossibleValues().iterator().next();
        } else {
            if (!json.isJsonPrimitive()) {
                throw error(name, "default must be a primitive property value");
            }
            String serialized = json.getAsString();
            value = property.getValue(serialized)
                    .orElseThrow(() -> error(name, "invalid vanilla default '" + serialized + "'"));
        }
        return new Entry<>(property, value);
    }

    private static int intField(JsonObject json, String field, String name) {
        JsonElement element = json.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw error(name, "'" + field + "' must be an integer");
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw error(name, "'" + field + "' must be a 32-bit integer");
        }
    }

    private static String stringField(JsonObject json, String field, String name) {
        return stringValue(json.get(field), field, name);
    }

    private static String stringValue(JsonElement element, String field, String name) {
        if (element == null || !element.isJsonPrimitive()) {
            throw error(name, "'" + field + "' must be a string");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) {
            throw error(name, "'" + field + "' must be a string");
        }
        return primitive.getAsString();
    }

    private static void requireKeys(String name, JsonObject definition, Set<String> allowed) {
        for (String key : definition.keySet()) {
            if (!allowed.contains(key)) {
                throw error(name, "unknown field '" + key + "'");
            }
        }
    }

    private static IllegalArgumentException error(String name, String message) {
        return new IllegalArgumentException("state property '" + name + "': " + message);
    }

    public List<Property<?>> properties() {
        return entries.stream().<Property<?>>map(Entry::property).toList();
    }

    void addTo(StateDefinition.Builder<Block, BlockState> builder) {
        for (Entry<?> entry : entries) builder.add(entry.property());
    }

    BlockState applyDefaults(BlockState state) {
        for (Entry<?> entry : entries) state = entry.apply(state);
        return state;
    }

    private record Entry<T extends Comparable<T>>(Property<T> property, T defaultValue) {
        BlockState apply(BlockState state) {
            return state.setValue(property, defaultValue);
        }
    }

    /** Mutable during assembly; {@link #build()} returns an immutable snapshot. */
    public static final class Builder {
        private final Map<String, Entry<?>> entries = new LinkedHashMap<>();
        private long combinations = 1;

        private Builder() {}

        public Builder booleanProperty(String name, boolean defaultValue) {
            requireName(name);
            return add(BooleanProperty.create(name), defaultValue);
        }

        public Builder integerProperty(String name, int min, int max, int defaultValue) {
            requireName(name);
            return addEntry(integerEntry(name, min, max, defaultValue));
        }

        /** Add a finite string-valued property, analogous to JSON's {@code enum} type. */
        public Builder stringProperty(String name, List<String> values, String defaultValue) {
            requireName(name);
            Objects.requireNonNull(values, "values");
            if (values.size() < 2 || values.size() > MAX_VALUES_PER_PROPERTY) {
                throw error(name, "values must contain 2–" + MAX_VALUES_PER_PROPERTY + " entries");
            }
            if (values.stream().anyMatch(Objects::isNull)) {
                throw error(name, "values must not contain null");
            }
            return add(new StringStateProperty(name, values), defaultValue);
        }

        /** Add an existing Minecraft property, retaining its exact instance. */
        public <T extends Comparable<T>> Builder add(Property<T> property, T defaultValue) {
            Objects.requireNonNull(property, "property");
            return addEntry(new Entry<>(property, defaultValue));
        }

        private <T extends Comparable<T>> Builder addEntry(Entry<T> entry) {
            Property<T> property = entry.property();
            String name = property.getName();
            requireName(name);
            if (entries.containsKey(name)) throw error(name, "duplicate property name");
            Collection<T> values = property.getPossibleValues();
            if (values.size() < 2 || values.size() > MAX_VALUES_PER_PROPERTY
                    || combinations * values.size() > MAX_STATES) {
                throw error(name, "exceeds limit of " + MAX_VALUES_PER_PROPERTY
                        + " values per property or " + MAX_STATES + " combinations per block");
            }
            Set<String> serialized = new LinkedHashSet<>();
            for (T value : values) {
                String valueName = value == null ? null : property.getName(value);
                if (valueName == null || !NAME.matcher(valueName).matches() || !serialized.add(valueName)) {
                    throw error(name, "values must be unique and match [a-z0-9_]+");
                }
                if (property.getValue(valueName).filter(value::equals).isEmpty()) {
                    throw error(name, "value '" + valueName + "' cannot be read back by the property");
                }
            }
            if (entry.defaultValue() == null || !values.contains(entry.defaultValue())) {
                throw error(name, "default must be one of the property values");
            }
            combinations *= values.size();
            entries.put(name, entry);
            return this;
        }

        private static void requireName(String name) {
            if (name == null || !NAME.matcher(name).matches()) {
                throw error(String.valueOf(name), "name must match [a-z0-9_]+");
            }
        }

        public StatePropertySet build() {
            return entries.isEmpty() ? EMPTY : new StatePropertySet(new ArrayList<>(entries.values()));
        }
    }
}
