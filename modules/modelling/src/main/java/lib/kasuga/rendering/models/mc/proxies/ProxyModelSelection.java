package lib.kasuga.rendering.models.mc.proxies;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable default model group plus ordered block-state selectors. */
public final class ProxyModelSelection {

    public record Rule(String property, Map<String, CompiledModelGroup> groups) {
        public Rule {
            Objects.requireNonNull(property, "property");
            groups = Map.copyOf(Objects.requireNonNull(groups, "groups"));
            if (groups.isEmpty()) {
                throw new IllegalArgumentException("model_by_state rule must not be empty");
            }
        }
    }

    private final CompiledModelGroup defaultGroup;
    private final Map<String, CompiledModelGroup> groups;
    private final List<Rule> rules;
    private final List<String> partIds;

    public ProxyModelSelection(
            CompiledModelGroup defaultGroup,
            Map<String, CompiledModelGroup> groups,
            List<Rule> rules
    ) {
        this.defaultGroup = Objects.requireNonNull(defaultGroup, "defaultGroup");
        Map<String, CompiledModelGroup> copied =
                new LinkedHashMap<>(Objects.requireNonNull(groups, "groups"));
        copied.putIfAbsent(defaultGroup.id(), defaultGroup);
        this.groups = java.util.Collections.unmodifiableMap(copied);
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        partIds = defaultGroup.partIds();
        for (CompiledModelGroup group : this.groups.values()) {
            if (!partIds.equals(group.partIds())) {
                throw new IllegalArgumentException(
                        "all proxy model groups must declare the same ordered part ids: "
                                + defaultGroup.id() + " vs " + group.id());
            }
        }
    }

    public CompiledModelGroup defaultGroup() {
        return defaultGroup;
    }

    public Map<String, CompiledModelGroup> groups() {
        return groups;
    }

    public List<String> partIds() {
        return partIds;
    }

    public CompiledModelGroup resolve(BlockState state) {
        Objects.requireNonNull(state, "state");
        for (Rule rule : rules) {
            Property<?> property = state.getProperties().stream()
                    .filter(candidate -> candidate.getName().equals(rule.property()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "proxy model selection references missing block-state property '"
                                    + rule.property() + "'"));
            CompiledModelGroup selected = rule.groups().get(serializedName(state, property));
            if (selected != null) {
                return selected;
            }
        }
        return defaultGroup;
    }

    /** Registration-time validation against a representative block state. */
    public void validate(BlockState state) {
        Objects.requireNonNull(state, "state");
        for (Rule rule : rules) {
            Property<?> property = state.getProperties().stream()
                    .filter(candidate -> candidate.getName().equals(rule.property()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "model_by_state references missing property '" + rule.property() + "'"));
            Map<String, Boolean> known = new LinkedHashMap<>();
            validateValues(property, known);
            for (String value : rule.groups().keySet()) {
                if (!known.containsKey(value)) {
                    throw new IllegalArgumentException(
                            "model_by_state." + rule.property()
                                    + " references unknown state value '" + value + "'");
                }
            }
        }
    }

    private static <T extends Comparable<T>> String serializedName(
            BlockState state,
            Property<T> property
    ) {
        return property.getName(state.getValue(property));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void validateValues(
            Property<?> property,
            Map<String, Boolean> known
    ) {
        Property raw = property;
        for (Object value : property.getPossibleValues()) {
            known.put(raw.getName((Comparable) value), true);
        }
    }
}
