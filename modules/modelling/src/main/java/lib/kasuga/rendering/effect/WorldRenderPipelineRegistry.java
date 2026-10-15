package lib.kasuga.rendering.effect;

import lib.kasuga.rendering.effect.pipeline.CompiledRenderPipeline;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Ordered registry behind owner-scoped render pipeline registrars. */
public final class WorldRenderPipelineRegistry {
    private static final PipelineRegistryCore<RenderLevelStageEvent.Stage, PipelineEntry> PIPELINES =
            new PipelineRegistryCore<>();
    private static final Map<RenderLevelStageEvent.Stage, CachedPipelines> SNAPSHOTS =
            new ConcurrentHashMap<>();

    private WorldRenderPipelineRegistry() {}

    public static PipelineRegistration register(
            ResourceLocation owner,
            RenderPipelineDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            WorldRenderPipeline pipeline
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(duplicatePolicy, "duplicatePolicy");
        Objects.requireNonNull(pipeline, "pipeline");
        CompiledRenderPipeline compiled = new CompiledRenderPipeline(descriptor);
        PipelineRegistryCore.Registration registration = PIPELINES.register(
                owner, descriptor.id(), descriptor.stage(), descriptor.priority(), duplicatePolicy,
                new PipelineEntry(descriptor, compiled, pipeline)
        );
        return new PipelineRegistration() {
            @Override public ResourceLocation id() { return registration.id(); }
            @Override public ResourceLocation owner() { return registration.owner(); }
            @Override public boolean isActive() { return registration.isActive(); }
            @Override public RenderPipelineDescriptor descriptor() { return descriptor; }
            @Override public CompiledRenderPipeline compiledPipeline() { return compiled; }
            @Override public void close() { registration.close(); }
        };
    }

    public static boolean isRegistered(ResourceLocation id) {
        return PIPELINES.isRegistered(id);
    }

    public static int size() {
        return PIPELINES.size();
    }

    /** Stable, immutable snapshot in final render order. */
    @ApiStatus.Internal
    public static List<RegisteredPipeline> pipelines(RenderLevelStageEvent.Stage stage) {
        Objects.requireNonNull(stage, "stage");
        long version = PIPELINES.version();
        CachedPipelines cached = SNAPSHOTS.get(stage);
        if (cached != null && cached.version == version) return cached.pipelines;

        List<RegisteredPipeline> pipelines = PIPELINES.entries(stage).stream()
                .map(entry -> new RegisteredPipeline(
                        entry.owner(), entry.pipeline().descriptor(), entry.pipeline().compiled(),
                        entry.pipeline().pipeline()
                ))
                .sorted(Comparator
                        .comparingInt((RegisteredPipeline entry) -> semanticStageOrder(entry.descriptor()))
                        .thenComparingInt(RegisteredPipeline::priority)
                        .thenComparing(entry -> entry.id().toString()))
                .toList();
        SNAPSHOTS.put(stage, new CachedPipelines(version, pipelines));
        return pipelines;
    }

    /** Read-only diagnostics snapshot for all registered pipelines. */
    public static List<RegisteredPipeline> pipelines() {
        return PIPELINES.entries().stream()
                .map(entry -> new RegisteredPipeline(
                        entry.owner(), entry.pipeline().descriptor(), entry.pipeline().compiled(),
                        entry.pipeline().pipeline()
                ))
                .sorted(Comparator
                        .comparing((RegisteredPipeline entry) -> entry.stage().toString())
                        .thenComparingInt(entry -> semanticStageOrder(entry.descriptor()))
                        .thenComparingInt(RegisteredPipeline::priority)
                        .thenComparing(entry -> entry.id().toString()))
                .toList();
    }

    private static int semanticStageOrder(RenderPipelineDescriptor descriptor) {
        if (descriptor.phase().orElse(null) == RenderPhase.POST_PROCESS) return 0;
        if (descriptor.phase().orElse(null) == RenderPhase.AFTER_LEVEL) return 2;
        return 1;
    }

    public record RegisteredPipeline(
            ResourceLocation owner,
            RenderPipelineDescriptor descriptor,
            CompiledRenderPipeline compiledPipeline,
            WorldRenderPipeline pipeline
    ) {
        public ResourceLocation id() { return descriptor.id(); }
        public RenderLevelStageEvent.Stage stage() { return descriptor.stage(); }
        public int priority() { return descriptor.priority(); }
    }

    private record PipelineEntry(
            RenderPipelineDescriptor descriptor,
            CompiledRenderPipeline compiled,
            WorldRenderPipeline pipeline
    ) {}

    private record CachedPipelines(long version, List<RegisteredPipeline> pipelines) {}
}
