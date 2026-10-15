package lib.kasuga.rendering.effect.post.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/** Immutable, validated execution plan for a group of post-processing passes. */
public final class PostProcessGraph {
    private static final Comparator<PostProcessGraphPassDescriptor> PASS_ORDER =
            Comparator.comparingInt(PostProcessGraphPassDescriptor::priority)
                    .thenComparing(PostProcessGraphPassDescriptor::id);

    private final ResourceLocation id;
    private final Map<PostProcessGraphTarget, PostProcessTargetDescriptor> targets;
    private final Map<PostProcessGraphTarget, PostProcessTargetDescriptor> allocatedTargets;
    private final Set<ResourceLocation> allocatedTargetIds;
    private final List<PostProcessGraphPassDescriptor> executionOrder;
    private final PostProcessGraphPrepare prepare;

    private PostProcessGraph(ResourceLocation id,
                             Map<PostProcessGraphTarget, PostProcessTargetDescriptor> targets,
                             List<PostProcessGraphPassDescriptor> executionOrder,
                             PostProcessGraphPrepare prepare) {
        this.id = Objects.requireNonNull(id, "id");
        this.targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
        Map<PostProcessGraphTarget, PostProcessTargetDescriptor> allocated = new LinkedHashMap<>();
        Set<ResourceLocation> allocatedIds = new LinkedHashSet<>();
        this.targets.forEach((target, descriptor) -> {
            ResourceLocation physicalId = allocatedTargetId(target);
            allocated.put(target, descriptor.withId(physicalId));
            allocatedIds.add(physicalId);
        });
        this.allocatedTargets = Collections.unmodifiableMap(allocated);
        this.allocatedTargetIds = Collections.unmodifiableSet(allocatedIds);
        this.executionOrder = List.copyOf(executionOrder);
        this.prepare = Objects.requireNonNull(prepare, "prepare");
    }

    public ResourceLocation id() {
        return id;
    }

    public Map<PostProcessGraphTarget, PostProcessTargetDescriptor> targets() {
        return targets;
    }

    /** Dependency-sorted immutable pass list, useful for diagnostics and tooling. */
    public List<PostProcessGraphPassDescriptor> executionOrder() {
        return executionOrder;
    }

    void execute(PostProcessContext context) {
        Objects.requireNonNull(context, "context");
        PostProcessGraphFrame frame = new PostProcessGraphFrame();
        final boolean execute;
        try {
            execute = prepare.prepare(context, frame);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Post-process graph " + id + " failed during frame preparation", exception);
        }
        if (!execute) return;

        Map<PostProcessGraphTarget, RenderTarget> resolved =
                new HashMap<>(Math.max(4, (allocatedTargets.size() + 1) * 2));
        resolved.put(PostProcessGraphTarget.main(), context.mainTarget());
        allocatedTargets.forEach((target, descriptor) -> resolved.put(
                target, context.acquire(descriptor)
        ));

        for (PostProcessGraphPassDescriptor pass : executionOrder) {
            try {
                pass.pass().execute(new PostProcessGraphContext(context, pass, resolved, frame));
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Post-process graph " + id
                        + " failed in pass " + pass.id(), exception);
            }
        }
    }

    public static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    /** Physical target IDs are graph-scoped; logical target names never collide across graphs. */
    public Set<ResourceLocation> allocatedTargetIds() {
        return allocatedTargetIds;
    }

    private ResourceLocation allocatedTargetId(PostProcessGraphTarget target) {
        ResourceLocation logical = Objects.requireNonNull(target.id());
        return ResourceLocation.fromNamespaceAndPath(
                id.getNamespace(), id.getPath() + "/targets/" + logical.getNamespace() + "/" + logical.getPath()
        );
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final Map<PostProcessGraphTarget, PostProcessTargetDescriptor> targets = new LinkedHashMap<>();
        private final Map<ResourceLocation, PostProcessGraphPassDescriptor> passes = new LinkedHashMap<>();
        private PostProcessGraphPrepare prepare = PostProcessGraphPrepare.ALWAYS;

        private Builder(ResourceLocation id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public Builder target(PostProcessTargetDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "descriptor");
            PostProcessGraphTarget target = PostProcessGraphTarget.managed(descriptor.id());
            if (targets.putIfAbsent(target, descriptor) != null) {
                throw new IllegalArgumentException("Duplicate graph target: " + descriptor.id());
            }
            return this;
        }

        public Builder pass(PostProcessGraphPassDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "descriptor");
            if (passes.putIfAbsent(descriptor.id(), descriptor) != null) {
                throw new IllegalArgumentException("Duplicate graph pass: " + descriptor.id());
            }
            return this;
        }

        public Builder prepare(PostProcessGraphPrepare value) {
            prepare = Objects.requireNonNull(value, "prepare");
            return this;
        }

        public PostProcessGraph build() {
            validateTargets();
            return new PostProcessGraph(id, targets, sortPasses(), prepare);
        }

        private void validateTargets() {
            Map<PostProcessGraphTarget, ResourceLocation> writers = new HashMap<>();
            for (PostProcessGraphPassDescriptor pass : passes.values()) {
                for (PostProcessGraphTarget target : pass.reads()) requireDeclared(target, pass.id());
                for (PostProcessGraphTarget target : pass.writes()) {
                    requireDeclared(target, pass.id());
                    if (target.isMain()) continue;
                    ResourceLocation replaced = writers.putIfAbsent(target, pass.id());
                    if (replaced != null) {
                        throw new IllegalArgumentException("Managed graph target " + target
                                + " has multiple writers: " + replaced + " and " + pass.id());
                    }
                }
            }
            for (PostProcessGraphPassDescriptor pass : passes.values()) {
                for (PostProcessGraphTarget target : pass.reads()) {
                    if (!target.isMain() && !writers.containsKey(target)) {
                        throw new IllegalArgumentException("Graph pass " + pass.id()
                                + " reads target with no writer: " + target);
                    }
                }
            }
        }

        private void requireDeclared(PostProcessGraphTarget target, ResourceLocation passId) {
            if (!target.isMain() && !targets.containsKey(target)) {
                throw new IllegalArgumentException("Graph pass " + passId
                        + " references undeclared target: " + target);
            }
        }

        private List<PostProcessGraphPassDescriptor> sortPasses() {
            Map<ResourceLocation, Set<ResourceLocation>> dependencies = new LinkedHashMap<>();
            Map<ResourceLocation, Set<ResourceLocation>> dependents = new LinkedHashMap<>();
            Map<PostProcessGraphTarget, ResourceLocation> writers = new HashMap<>();

            for (PostProcessGraphPassDescriptor pass : passes.values()) {
                dependencies.put(pass.id(), new LinkedHashSet<>());
                dependents.put(pass.id(), new LinkedHashSet<>());
                for (PostProcessGraphTarget target : pass.writes()) {
                    if (!target.isMain()) writers.put(target, pass.id());
                }
            }

            for (PostProcessGraphPassDescriptor pass : passes.values()) {
                for (ResourceLocation explicit : pass.after()) {
                    if (!passes.containsKey(explicit)) {
                        throw new IllegalArgumentException("Graph pass " + pass.id()
                                + " depends on missing pass: " + explicit);
                    }
                    addDependency(dependencies, dependents, pass.id(), explicit);
                }
                for (PostProcessGraphTarget target : pass.reads()) {
                    if (target.isMain()) continue;
                    ResourceLocation writer = writers.get(target);
                    if (writer != null) addDependency(dependencies, dependents, pass.id(), writer);
                }
            }

            PriorityQueue<PostProcessGraphPassDescriptor> ready = new PriorityQueue<>(PASS_ORDER);
            for (PostProcessGraphPassDescriptor pass : passes.values()) {
                if (dependencies.get(pass.id()).isEmpty()) ready.add(pass);
            }

            List<PostProcessGraphPassDescriptor> sorted = new ArrayList<>(passes.size());
            while (!ready.isEmpty()) {
                PostProcessGraphPassDescriptor next = ready.remove();
                sorted.add(next);
                for (ResourceLocation dependent : dependents.get(next.id())) {
                    Set<ResourceLocation> remaining = dependencies.get(dependent);
                    remaining.remove(next.id());
                    if (remaining.isEmpty()) ready.add(passes.get(dependent));
                }
            }

            if (sorted.size() != passes.size()) {
                List<ResourceLocation> cycle = dependencies.entrySet().stream()
                        .filter(entry -> !entry.getValue().isEmpty())
                        .map(Map.Entry::getKey)
                        .sorted()
                        .toList();
                throw new IllegalArgumentException("Post-process graph contains a dependency cycle: " + cycle);
            }
            return sorted;
        }

        private static void addDependency(
                Map<ResourceLocation, Set<ResourceLocation>> dependencies,
                Map<ResourceLocation, Set<ResourceLocation>> dependents,
                ResourceLocation pass,
                ResourceLocation dependency
        ) {
            if (dependencies.get(pass).add(dependency)) dependents.get(dependency).add(pass);
        }
    }
}
