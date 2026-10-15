package lib.kasuga.rendering.effect;

import lib.kasuga.rendering.effect.post.PostProcessTargetPool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.jetbrains.annotations.ApiStatus;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Comparator;
import java.util.List;

/** Ticks all live effect pipelines once per client tick. */
@EventBusSubscriber(value = Dist.CLIENT)
@ApiStatus.Internal
public final class ClientEffectRuntime {
    private static final CopyOnWriteArrayList<EffectRenderPipeline<?>> PIPELINES = new CopyOnWriteArrayList<>();
    private static ClientLevel previousLevel;

    private ClientEffectRuntime() {}

    static synchronized void add(EffectRenderPipeline<?> pipeline, DuplicatePolicy duplicatePolicy) {
        List<EffectRenderPipeline<?>> existing = PIPELINES.stream()
                .filter(value -> value != pipeline && value.id().equals(pipeline.id()))
                .toList();
        if (!existing.isEmpty() && duplicatePolicy == DuplicatePolicy.FAIL) {
            throw new IllegalStateException("Effect pipeline ID is already active: " + pipeline.id());
        }
        existing.forEach(EffectRenderPipeline::close);
        PIPELINES.addIfAbsent(pipeline);
    }

    static synchronized void remove(EffectRenderPipeline<?> pipeline) {
        PIPELINES.remove(pipeline);
    }

    /** Stable diagnostics snapshot for GUIs and tooling. */
    public static List<EffectPipelineSnapshot> snapshot() {
        return PIPELINES.stream()
                .map(pipeline -> new EffectPipelineSnapshot(
                        pipeline.owner(), pipeline.id(), pipeline.descriptor(), pipeline.effectTypeName(),
                        pipeline.activeCount(), pipeline.lastVisibleCount(), pipeline.lastRenderNanos()
                ))
                .sorted(Comparator.comparing(entry -> entry.id().toString()))
                .toList();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != previousLevel) {
            PIPELINES.forEach(EffectRenderPipeline::clearImmediately);
            PostProcessTargetPool.getInstance().clear();
            previousLevel = level;
        }
        if (level == null || minecraft.isPaused()) return;
        PIPELINES.forEach(pipeline -> pipeline.tick(level));
    }

    public record EffectPipelineSnapshot(
            net.minecraft.resources.ResourceLocation owner,
            net.minecraft.resources.ResourceLocation id,
            lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor descriptor,
            String effectType,
            int activeCount,
            int visibleCount,
            long lastRenderNanos
    ) {}
}
