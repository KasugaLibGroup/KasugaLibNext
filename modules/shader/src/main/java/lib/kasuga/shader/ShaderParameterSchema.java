package lib.kasuga.shader;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Ordered, immutable set of parameters explicitly exposed by one shader. */
public final class ShaderParameterSchema {
    private static final ShaderParameterSchema EMPTY = new ShaderParameterSchema(List.of());

    private final List<ShaderParameter> parameters;
    private final Map<String, ShaderParameter> byName;

    private ShaderParameterSchema(Collection<ShaderParameter> declarations) {
        LinkedHashMap<String, ShaderParameter> indexed = new LinkedHashMap<>();
        for (ShaderParameter parameter : declarations) {
            Objects.requireNonNull(parameter, "parameters contains null");
            ShaderParameter previous = indexed.putIfAbsent(parameter.name(), parameter);
            if (previous != null && !previous.equals(parameter)) {
                throw new IllegalArgumentException("Conflicting exposed shader parameter: " + parameter.name());
            }
        }
        parameters = List.copyOf(indexed.values());
        byName = Map.copyOf(indexed);
    }

    public static ShaderParameterSchema empty() {
        return EMPTY;
    }

    public static ShaderParameterSchema of(Collection<ShaderParameter> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        return parameters.isEmpty() ? EMPTY : new ShaderParameterSchema(parameters);
    }

    public List<ShaderParameter> parameters() {
        return parameters;
    }

    public Optional<ShaderParameter> find(String name) {
        return Optional.ofNullable(byName.get(Objects.requireNonNull(name, "name")));
    }

    public ShaderParameter require(String name) {
        ShaderParameter parameter = byName.get(Objects.requireNonNull(name, "name"));
        if (parameter == null) throw new IllegalArgumentException("Shader does not expose parameter: " + name);
        return parameter;
    }

    public boolean isEmpty() {
        return parameters.isEmpty();
    }

    public int size() {
        return parameters.size();
    }

    @Override
    public boolean equals(Object object) {
        return object == this || object instanceof ShaderParameterSchema other
                && parameters.equals(other.parameters);
    }

    @Override
    public int hashCode() {
        return parameters.hashCode();
    }

    @Override
    public String toString() {
        return parameters.toString();
    }
}
