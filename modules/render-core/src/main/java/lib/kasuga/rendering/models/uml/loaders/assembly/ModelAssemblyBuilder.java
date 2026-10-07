package lib.kasuga.rendering.models.uml.loaders.assembly;

import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;

import java.util.*;
import java.util.function.Consumer;

/** Constructs a cacheable assembly request; no GPU resources or mutable instance state are captured. */
public final class ModelAssemblyBuilder {
    private final List<ModelAssembly.Part> parts = new ArrayList<>();
    private float bindTolerance = 1e-4f;

    public ModelAssemblyBuilder(String bodyId, Model body, long revision) {
        this(bodyId, body, revision, part -> {});
    }

    public ModelAssemblyBuilder(String bodyId, Model body, long revision, Consumer<PartBuilder> configure) {
        part(bodyId, body, revision, configure);
    }

    public ModelAssemblyBuilder part(String id, Model model, long revision) {
        return part(id, model, revision, part -> {});
    }

    public ModelAssemblyBuilder part(String id, Model model, long revision, Consumer<PartBuilder> configure) {
        PartBuilder builder = new PartBuilder(id, model, revision);
        Objects.requireNonNull(configure, "configure").accept(builder);
        parts.add(builder.build());
        return this;
    }

    public ModelAssemblyBuilder bindTolerance(float tolerance) { bindTolerance = tolerance; return this; }
    public ModelAssembly.Request build() { return new ModelAssembly.Request(parts, bindTolerance); }
    public ModelAssembly assemble() { return ModelAssembler.standard().assemble(build()); }
    public ModelAssembly assemble(ModelAssemblyCache cache) { return cache.getOrAssemble(build()); }

    public static final class PartBuilder {
        private final String id;
        private final Model model;
        private final long revision;
        private final Map<Bone, String> mappings = new LinkedHashMap<>();
        private final Set<Integer> hidden = new LinkedHashSet<>();
        private final Map<String, Set<Integer>> regions = new LinkedHashMap<>();
        private final Set<String> hiddenRegions = new LinkedHashSet<>();
        private boolean matchBodyBones = true, includeDynamics = true;

        private PartBuilder(String id, Model model, long revision) {
            this.id = id; this.model = Objects.requireNonNull(model); this.revision = revision;
        }

        public PartBuilder mapBone(String source, String target) {
            Bone bone = model.getSkeleton().getBoneMap().get(source);
            if (bone == null) throw new IllegalArgumentException("unknown source bone: " + source);
            return mapBone(bone, target);
        }
        public PartBuilder mapBone(Bone source, String target) { mappings.put(source, target); return this; }
        public PartBuilder matchBodyBones(boolean enabled) { matchBodyBones = enabled; return this; }
        public PartBuilder includeDynamics(boolean enabled) { includeDynamics = enabled; return this; }
        public PartBuilder hideMeshes(int... indices) { for (int index : indices) hidden.add(index); return this; }
        public PartBuilder region(String name, int... indices) {
            Set<Integer> meshes = new LinkedHashSet<>();
            for (int index : indices) meshes.add(index);
            regions.put(Objects.requireNonNull(name), meshes);
            return this;
        }
        public PartBuilder hideRegions(String... names) { hiddenRegions.addAll(List.of(names)); return this; }

        private ModelAssembly.Part build() {
            for (String name : hiddenRegions) {
                Set<Integer> indices = regions.get(name);
                if (indices == null) throw new IllegalArgumentException("unknown region in " + id + ": " + name);
                hidden.addAll(indices);
            }
            return new ModelAssembly.Part(id, new ModelAssembly.Source(model, revision), mappings, hidden,
                    matchBodyBones, includeDynamics);
        }
    }
}
