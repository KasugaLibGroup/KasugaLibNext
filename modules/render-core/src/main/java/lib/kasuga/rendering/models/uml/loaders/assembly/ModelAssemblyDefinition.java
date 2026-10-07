package lib.kasuga.rendering.models.uml.loaders.assembly;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** A resource-level recipe. K is a host resource key, independent of model format and rendering API. */
public record ModelAssemblyDefinition<K>(List<Part<K>> parts, float bindTolerance) {
    public ModelAssemblyDefinition {
        parts = List.copyOf(parts);
        if (parts.isEmpty() || !Float.isFinite(bindTolerance) || bindTolerance <= 0) {
            throw new IllegalArgumentException("body and positive bind tolerance required");
        }
        Set<String> names = new HashSet<>();
        for (Part<K> part : parts) if (!names.add(part.id())) throw new IllegalArgumentException("duplicate part: " + part.id());
        if (!parts.getFirst().boneMappings().isEmpty()) throw new IllegalArgumentException("body bones cannot be aliased");
    }

    public record Part<K>(String id, K source, Map<String, String> boneMappings, Set<Integer> hiddenMeshes,
                          boolean matchBodyBones, boolean includeDynamics) {
        public Part {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("empty part name");
            Objects.requireNonNull(source, "source");
            boneMappings = Map.copyOf(boneMappings); hiddenMeshes = Set.copyOf(hiddenMeshes);
            for (var entry : boneMappings.entrySet()) {
                if (entry.getKey().isBlank() || entry.getValue().isBlank()) throw new IllegalArgumentException("empty bone name");
            }
            for (int index : hiddenMeshes) if (index < 0) throw new IllegalArgumentException("negative hidden mesh index");
        }
    }

    /** Null means at least one source is unpublished. No partial geometry is built. */
    public ModelAssembly.Request resolve(Function<? super K, ModelAssembly.Source> sources) {
        List<ModelAssembly.Source> resolved = new ArrayList<>();
        for (Part<K> part : parts) {
            ModelAssembly.Source source = sources.apply(part.source());
            if (source == null) return null;
            resolved.add(source);
        }
        Part<K> body = parts.getFirst();
        ModelAssembly.Source first = resolved.getFirst();
        ModelAssemblyBuilder builder = new ModelAssemblyBuilder(body.id(), first.model(), first.revision(), part -> configure(body, part));
        for (int i = 1; i < parts.size(); i++) {
            Part<K> definition = parts.get(i); ModelAssembly.Source source = resolved.get(i);
            builder.part(definition.id(), source.model(), source.revision(), part -> configure(definition, part));
        }
        return builder.bindTolerance(bindTolerance).build();
    }

    private static void configure(Part<?> definition, ModelAssemblyBuilder.PartBuilder part) {
        definition.boneMappings().forEach(part::mapBone);
        part.matchBodyBones(definition.matchBodyBones()).includeDynamics(definition.includeDynamics())
                .hideMeshes(definition.hiddenMeshes().stream().mapToInt(Integer::intValue).toArray());
    }

    public static <K> Builder<K> builder(String bodyId, K source) { return new Builder<>(bodyId, source, part -> {}); }
    public static <K> Builder<K> builder(String bodyId, K source, Consumer<PartBuilder> configure) {
        return new Builder<>(bodyId, source, configure);
    }

    public static final class Builder<K> {
        private final List<Part<K>> parts = new ArrayList<>();
        private float tolerance = 1e-4f;
        private Builder(String id, K source, Consumer<PartBuilder> configure) { part(id, source, configure); }
        public Builder<K> part(String id, K source) { return part(id, source, part -> {}); }
        public Builder<K> part(String id, K source, Consumer<PartBuilder> configure) {
            PartBuilder builder = new PartBuilder(); configure.accept(builder);
            parts.add(new Part<>(id, source, builder.mappings, builder.hidden, builder.match, builder.dynamics));
            return this;
        }
        public Builder<K> bindTolerance(float tolerance) { this.tolerance = tolerance; return this; }
        public ModelAssemblyDefinition<K> build() { return new ModelAssemblyDefinition<>(parts, tolerance); }
    }

    public static final class PartBuilder {
        private final Map<String, String> mappings = new LinkedHashMap<>();
        private final Set<Integer> hidden = new LinkedHashSet<>();
        private final Map<String, Set<Integer>> regions = new LinkedHashMap<>();
        private boolean match = true, dynamics = true;
        public PartBuilder mapBone(String source, String target) { mappings.put(source, target); return this; }
        public PartBuilder matchBodyBones(boolean value) { match = value; return this; }
        public PartBuilder includeDynamics(boolean value) { dynamics = value; return this; }
        public PartBuilder hideMeshes(int... indices) { for (int index : indices) hidden.add(index); return this; }
        public PartBuilder region(String name, int... indices) {
            Set<Integer> values = new LinkedHashSet<>(); for (int index : indices) values.add(index);
            regions.put(Objects.requireNonNull(name), values); return this;
        }
        public PartBuilder hideRegions(String... names) {
            for (String name : names) {
                Set<Integer> values = regions.get(name);
                if (values == null) throw new IllegalArgumentException("unknown region: " + name);
                hidden.addAll(values);
            }
            return this;
        }
    }
}
