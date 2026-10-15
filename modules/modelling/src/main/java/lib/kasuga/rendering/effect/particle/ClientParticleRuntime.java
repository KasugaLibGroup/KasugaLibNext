package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.effect.DuplicatePolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Applies optional group and per-instance behaviors once per active client tick. */
@EventBusSubscriber(value = Dist.CLIENT)
@ApiStatus.Internal
public final class ClientParticleRuntime {
    private static final CopyOnWriteArrayList<ParticleRenderPipeline> PIPELINES =
            new CopyOnWriteArrayList<>();
    private static ClientLevel previousLevel;

    private ClientParticleRuntime() {
    }

    static synchronized void add(ParticleRenderPipeline pipeline, DuplicatePolicy duplicatePolicy) {
        List<ParticleRenderPipeline> existing = PIPELINES.stream()
                .filter(value -> value != pipeline && value.id().equals(pipeline.id()))
                .toList();
        if (!existing.isEmpty() && duplicatePolicy == DuplicatePolicy.FAIL) {
            throw new IllegalStateException("Particle pipeline ID is already active: " + pipeline.id());
        }
        existing.forEach(ParticleRenderPipeline::close);
        PIPELINES.addIfAbsent(pipeline);
    }

    static synchronized void remove(ParticleRenderPipeline pipeline) {
        PIPELINES.remove(pipeline);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != previousLevel) {
            PIPELINES.forEach(ParticleRenderPipeline::clearWorldState);
            previousLevel = level;
        }
        if (level == null || minecraft.isPaused()) return;
        PIPELINES.forEach(pipeline -> pipeline.update(level));
    }
}
