package lib.kasuga.rendering.effect.builtin.cloud;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.*;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.cloud.gl.CloudVolumeRenderer;
import lib.kasuga.rendering.cloud.SkyCloudField;
import lib.kasuga.rendering.cloud.SkyCloudSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Built-in volumetric cloud pipeline, shared by the main view and detached world cameras. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CloudEffects {
    private static EffectRenderPipeline<CumulonimbusCloud> pipeline;
    private static CloudEffectRenderer renderer;
    private static PipelineRegistration skyPipeline;
    private static SkyCloudField sky = new SkyCloudField(SkyCloudSettings.DEFAULT);
    private static ClientLevel previousLevel;
    private static volatile boolean enabled, skyEnabled = true;
    private CloudEffects() {}

    public static synchronized void initialize(RenderPipelineRegistrar registrar) {
        if (pipeline != null && pipeline.isActive()) return;
        renderer = new CloudEffectRenderer();
        skyPipeline = registrar.world(RenderPipelineDescriptor.builder(
                        ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "effects/sky_clouds"), RenderPhase.AFTER_WEATHER)
                .priority(499).build(), renderer::renderSky);
        pipeline = registrar.effects(RenderPipelineDescriptor.builder(
                        ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "effects/cumulonimbus"), RenderPhase.AFTER_WEATHER)
                .priority(500).build(), true, renderer);
    }

    public static synchronized EffectHandle<CumulonimbusCloud> spawn(CumulonimbusCloud cloud) {
        if (pipeline == null || !pipeline.isActive()) throw new IllegalStateException("Cloud pipeline has not been registered");
        return pipeline.spawn(cloud);
    }
    /** Optional client-wide test mode. While enabled, vanilla clouds are hidden in every world view. */
    public static boolean isEnabled() { return enabled; }
    /** Preserves cloud instances and the user's saved vanilla cloud setting when toggled. */
    public static void setEnabled(boolean value) {
        if (value) CloudVolumeRenderer.prepareNoise();
        enabled = value;
    }
    /** Disabling the field keeps the explicit single-volume API available for authoring. */
    public static boolean isSkyEnabled() { return skyEnabled; }
    public static void setSkyEnabled(boolean value) { skyEnabled = value; }
    public static synchronized SkyCloudField sky() { return sky; }
    @SubscribeEvent public static synchronized void tick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != previousLevel) {
            sky = new SkyCloudField(sky.settings()); previousLevel = minecraft.level;
        }
        if (enabled && skyEnabled && minecraft.level != null && !minecraft.isPaused()) sky.tick(.05);
    }
    @SubscribeEvent public static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        setEnabled(false); skyEnabled = true;
    }
    public static synchronized void clear() { if (pipeline != null) pipeline.clear(); }
    /** Explicit volume instances only; the continuous sky field does not enumerate cloud bodies. */
    public static synchronized int visibleCount() { return !enabled || pipeline == null ? 0 : pipeline.lastVisibleCount(); }
    public static synchronized void shutdown() {
        enabled = false;
        if (skyPipeline != null) { skyPipeline.close(); skyPipeline = null; }
        if (pipeline != null) { pipeline.close(); pipeline = null; }
        if (renderer != null) { renderer.close(); renderer = null; }
    }
}
