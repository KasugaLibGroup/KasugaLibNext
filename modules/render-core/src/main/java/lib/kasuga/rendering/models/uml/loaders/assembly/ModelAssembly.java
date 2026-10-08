package lib.kasuga.rendering.models.uml.loaders.assembly;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.MorphType;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;

import java.util.*;

/** A shared assembled model and the source-to-output references needed by animation and host adapters. */
public record ModelAssembly(Model model, Request request, Map<String, Remap> parts) {
    public ModelAssembly {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(request, "request");
        parts = Collections.unmodifiableMap(new LinkedHashMap<>(parts));
    }

    public Remap part(String id) {
        Remap remap = parts.get(id);
        if (remap == null) throw new IllegalArgumentException("unknown assembly part: " + id);
        return remap;
    }

    /** Resources are identified by object identity and a host-managed revision, never by their format. */
    public record Source(Model model, long revision) {
        public Source { Objects.requireNonNull(model, "model"); }
        @Override public boolean equals(Object other) {
            return other instanceof Source source && model == source.model && revision == source.revision;
        }
        @Override public int hashCode() { return 31 * System.identityHashCode(model) + Long.hashCode(revision); }
    }

    /** The first part is the body. Source geometry is already in the body's bind-space coordinates. */
    public record Part(String id, Source source, Map<Bone, String> boneMappings,
                       Set<Integer> hiddenMeshes, boolean matchBodyBones, boolean includeDynamics) {
        public Part {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("empty part name");
            Objects.requireNonNull(source, "source");
            boneMappings = Map.copyOf(boneMappings);
            hiddenMeshes = Set.copyOf(hiddenMeshes);
            Set<Bone> bones = Set.of(source.model().getBones());
            for (var mapping : boneMappings.entrySet()) {
                if (!bones.contains(mapping.getKey()) || mapping.getValue().isBlank()) {
                    throw new IllegalArgumentException("invalid bone mapping in " + id);
                }
            }
            for (int index : hiddenMeshes) {
                if (index < 0 || index >= source.model().getMeshes().length) {
                    throw new IllegalArgumentException("invalid hidden mesh index in " + id + ": " + index);
                }
            }
        }
    }

    public record Request(List<Part> parts, float bindTolerance) {
        public Request {
            parts = List.copyOf(parts);
            if (parts.isEmpty() || !Float.isFinite(bindTolerance) || bindTolerance <= 0) {
                throw new IllegalArgumentException("assembly needs a body and a positive bind tolerance");
            }
            Set<String> names = new HashSet<>();
            for (Part part : parts) if (!names.add(part.id())) throw new IllegalArgumentException("duplicate part: " + part.id());
            if (!parts.getFirst().boneMappings().isEmpty()) throw new IllegalArgumentException("body bones cannot be aliased");
        }
    }

    /** Namespaced identifiers avoid collisions between separate garments' morphs. */
    public record PartMorphId(String part, Object original) {
        public PartMorphId { Objects.requireNonNull(part); Objects.requireNonNull(original); }
    }

    public interface Remap {
        String id();
        Model source();
        Bone bone(Bone source);
        /** Null means the source element was hidden or unused. */
        Vertex vertex(Vertex source);
        Mesh mesh(Mesh source);
        Material material(Material source);
        Object morphId(Object source);
        String boneName(String source);
        String ikName(String source);
        String animationName(String source);
        String anchorName(String source);
        /** -1 means this part opted out of dynamics. */
        int rigidBodyIndex(int sourceIndex);
        Pose pose(Pose source);
        MorphType<?, ?, ?> morph(MorphType<?, ?, ?> source);
        <T> AnimationSampler<T> sampler(AnimationSampler<T> source);
    }
}
