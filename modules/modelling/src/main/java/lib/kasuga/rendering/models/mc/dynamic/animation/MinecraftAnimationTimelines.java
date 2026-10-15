package lib.kasuga.rendering.models.mc.dynamic.animation;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationTimeline;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.LinkedHashMap;
import java.util.Objects;

/** Optional Minecraft tick owner for shared model/camera timelines. Registration and cleanup use the render thread. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class MinecraftAnimationTimelines {
    private static final LinkedHashMap<AnimationTimeline, Registration> TIMELINES = new LinkedHashMap<>();
    private MinecraftAnimationTimelines() {}

    /** One driver per timeline. Caller closes the registration when the scene is retired. */
    public static Registration drive(AnimationTimeline timeline) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(timeline, "timeline");
        if (timeline.isOwned()) throw new IllegalArgumentException("A standalone player already drives this timeline");
        if (TIMELINES.containsKey(timeline)) throw new IllegalArgumentException("Timeline is already driven by Minecraft");
        var registration = new Registration(timeline);
        TIMELINES.put(timeline, registration);
        return registration;
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;
        for (var timeline : TIMELINES.keySet()) timeline.tick(1f / 20f);
    }

    public static void shutdown() {
        RenderSystem.assertOnRenderThread();
        for (var registration : TIMELINES.values().toArray(Registration[]::new)) registration.close();
    }

    public static final class Registration implements AutoCloseable {
        private final AnimationTimeline timeline;
        private boolean closed;
        private Registration(AnimationTimeline timeline) { this.timeline = timeline; }
        /** Unregisters the clock without stopping its targets; another host may take over advancement. */
        public void close() {
            RenderSystem.assertOnRenderThread();
            if (!closed) { closed = true; TIMELINES.remove(timeline, this); }
        }
    }
}
