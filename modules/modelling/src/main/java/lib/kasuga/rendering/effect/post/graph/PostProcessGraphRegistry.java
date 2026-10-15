package lib.kasuga.rendering.effect.post.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.PipelineRegistration;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.effect.post.PostProcessPipelineRegistry;
import lib.kasuga.rendering.effect.post.PostProcessTargetPool;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owner-aware registry for validated post-processing graphs. */
public final class PostProcessGraphRegistry {
    private static final Object LOCK = new Object();
    private static final Map<ResourceLocation, Entry> GRAPHS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Integer> TARGET_USERS = new HashMap<>();

    private PostProcessGraphRegistry() {}

    public static PostProcessGraphRegistration register(
            ResourceLocation owner,
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            PostProcessGraph graph
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(duplicatePolicy, "duplicatePolicy");
        Objects.requireNonNull(graph, "graph");
        if (!descriptor.id().equals(graph.id())) {
            throw new IllegalArgumentException("Graph and pipeline descriptor IDs must match");
        }
        synchronized (LOCK) {
            Entry existing = GRAPHS.get(graph.id());
            if (existing != null && duplicatePolicy == DuplicatePolicy.FAIL) {
                throw new IllegalStateException(
                        "Post-process graph ID " + graph.id() + " is already owned by " + existing.owner
                );
            }
        }

        PipelineRegistration pipeline = PostProcessPipelineRegistry.register(
                owner, descriptor, duplicatePolicy, graph::execute
        );
        Entry entry = new Entry(owner, descriptor, graph, pipeline);
        List<ResourceLocation> releaseCandidates = new ArrayList<>();
        synchronized (LOCK) {
            Entry replaced = GRAPHS.put(graph.id(), entry);
            if (replaced != null) removeTargetUsers(replaced.graph, releaseCandidates);
            addTargetUsers(graph);
            releaseCandidates.removeIf(TARGET_USERS::containsKey);
        }
        releaseCandidates.forEach(PostProcessGraphRegistry::releaseWhenUnused);
        return new RegistrationImpl(entry);
    }

    public static boolean isRegistered(ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        synchronized (LOCK) {
            return GRAPHS.containsKey(id);
        }
    }

    public static List<ResourceLocation> registeredIds() {
        synchronized (LOCK) {
            return GRAPHS.keySet().stream().sorted().toList();
        }
    }

    private static void addTargetUsers(PostProcessGraph graph) {
        graph.allocatedTargetIds().forEach(id -> TARGET_USERS.merge(id, 1, Integer::sum));
    }

    private static void removeTargetUsers(PostProcessGraph graph, List<ResourceLocation> releaseCandidates) {
        graph.allocatedTargetIds().forEach(id -> {
            int remaining = TARGET_USERS.getOrDefault(id, 1) - 1;
            if (remaining <= 0) {
                TARGET_USERS.remove(id);
                releaseCandidates.add(id);
            } else {
                TARGET_USERS.put(id, remaining);
            }
        });
    }

    private static void releaseWhenUnused(ResourceLocation id) {
        Runnable release = () -> {
            synchronized (LOCK) {
                if (TARGET_USERS.containsKey(id)) return;
                PostProcessTargetPool.getInstance().release(id);
            }
        };
        if (RenderSystem.isOnRenderThread()) release.run();
        else RenderSystem.recordRenderCall(release::run);
    }

    private static boolean isActive(Entry entry) {
        synchronized (LOCK) {
            return GRAPHS.get(entry.graph.id()) == entry && entry.pipeline.isActive();
        }
    }

    private static void close(Entry entry) {
        List<ResourceLocation> releaseCandidates = new ArrayList<>();
        synchronized (LOCK) {
            if (GRAPHS.get(entry.graph.id()) == entry) {
                GRAPHS.remove(entry.graph.id());
                removeTargetUsers(entry.graph, releaseCandidates);
            }
        }
        entry.pipeline.close();
        releaseCandidates.forEach(PostProcessGraphRegistry::releaseWhenUnused);
    }

    private record Entry(
            ResourceLocation owner,
            RenderPipelineDescriptor descriptor,
            PostProcessGraph graph,
            PipelineRegistration pipeline
    ) {}

    private static final class RegistrationImpl implements PostProcessGraphRegistration {
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RegistrationImpl(Entry entry) {
            this.entry = entry;
        }

        @Override public ResourceLocation id() { return entry.graph.id(); }
        @Override public ResourceLocation owner() { return entry.owner; }
        @Override public boolean isActive() { return !closed.get() && PostProcessGraphRegistry.isActive(entry); }
        @Override public RenderPipelineDescriptor descriptor() { return entry.descriptor; }
        @Override public lib.kasuga.rendering.effect.pipeline.CompiledRenderPipeline compiledPipeline() {
            return entry.pipeline.compiledPipeline();
        }
        @Override public PostProcessGraph graph() { return entry.graph; }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) PostProcessGraphRegistry.close(entry);
        }
    }
}
