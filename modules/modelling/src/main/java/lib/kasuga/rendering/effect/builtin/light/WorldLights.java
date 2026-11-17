package lib.kasuga.rendering.effect.builtin.light;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.EffectHandle;
import lib.kasuga.rendering.effect.EffectRenderPipeline;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.lighting.LightSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Built-in dynamic lights using the shared effect lifecycle and resource-backed post-process graph. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class WorldLights {
    private static EffectRenderPipeline<WorldLight> pipeline;
    private static WorldLightRenderer renderer;

    private WorldLights() {}

    public static void initialize(RenderPipelineRegistrar registrar) {
        if (pipeline != null && pipeline.isActive()) return;
        renderer = new WorldLightRenderer(registrar);
        pipeline = registrar.effects(RenderPipelineDescriptor.builder(id("collect"), RenderPhase.POST_PROCESS)
                .priority(700).build(), false, renderer);
        renderer.registerGraph(registrar);
    }

    public static EffectHandle<WorldLight> spawn(ClientLevel level, LightSource source) {
        if (pipeline == null || !pipeline.isActive()) throw new IllegalStateException("World light pipeline is not registered");
        return pipeline.spawn(new WorldLight(level, source));
    }

    public static boolean ready() { return renderer != null && renderer.ready(); }
    public static int activeCount() { return pipeline == null ? 0 : pipeline.activeCount(); }
    public static int visibleCount() { return pipeline == null ? 0 : pipeline.lastVisibleCount(); }
    public static void clear() { if (pipeline != null) pipeline.clear(); }

    @SubscribeEvent
    public static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        if (renderer == null) return;
        WorldLightRenderer retired = renderer;
        if (RenderSystem.isOnRenderThread()) retired.release();
        else RenderSystem.recordRenderCall(retired::release);
    }

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "lighting/" + path);
    }
}
