package test.kasuga.modelling;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.api.McModelHandle;
import lib.kasuga.rendering.models.mc.dynamic.ModelParticleRuntime;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Opt-in real world/framebuffer evidence for automatic emitters, reload, and disposal. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ModelEmitterClientSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testModelEmitters");
    private static final boolean LIVE_SYNC = Boolean.getBoolean("kasuga.testModelLiveSync");
    private static long reloadBaseline;
    private static final long START = System.nanoTime();
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static McModelHandle handle;
    private static ModelInstance previous;
    private static ModelParticleRuntime.EmitterModule retired;
    private static CompletableFuture<Void> reload;
    private static boolean finished, disposed;
    private static int frame, reloadedFrames, disposedFrames;

    private ModelEmitterClientSmoke() {}

    @SubscribeEvent
    public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            check(System.nanoTime() - START < 240_000_000_000L, "model emitter client smoke timeout");
            if (mc.getOverlay() == null && mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen) {
                String proceed = net.minecraft.network.chat.Component.translatable("selectWorld.backupJoinConfirmButton").getString();
                for (var child : screen.children()) if (child instanceof net.minecraft.client.gui.components.Button button
                        && button.getMessage().getString().equals(proceed)) { button.onPress(); return; }
            }
            if (mc.level == null || mc.player == null || mc.getOverlay() != null) return;
            if (handle == null) {
                mc.setScreen(null);
                mc.options.hideGui = true;
                mc.player.setYRot(0);
                mc.player.setXRot(0);
                Vec3 origin = mc.player.getEyePosition().add(0, -0.6, 4);
                handle = McModelHandle.of(ResourceLocation.parse("kasuga_lib:models/components/emitter_smoke.bbmodel"),
                        null, ResourceLocation.parse("kasuga_lib:smoke/model_emitter"), origin);
            }
            if (!disposed) handle.mount();
        } catch (Throwable failure) { finish(failure); }
    }

    @SubscribeEvent
    public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || handle == null) return;
        try {
            if (Minecraft.getInstance().getOverlay() != null) return;
            if (disposed) {
                if (++disposedFrames == 5) {
                    check(retired.closed() && retired.particleCount() == 0, "close retained source state");
                    int pixels = capture("closed");
                    check(ModelParticleRuntime.lastVisibleCount() == 0 && pixels == 0, "close retained visible particles: " + pixels);
                    REPORT.put("closedPixels", pixels);
                    finish(null);
                }
                return;
            }
            ModelInstance instance = handle.instance();
            if (instance == null) return;
            var module = ModelParticleRuntime.module(instance);
            check(module != null, "loaded model did not install source");
            frame++;
            if (reload == null) {
                if (frame == 60) {
                    check(module.enabled() && module.particleCount() > 20, "source did not automatically emit");
                    check(ModelParticleRuntime.lastVisibleCount() > 0, "world pass did not draw source");
                    int pixels = capture("emitting");
                    check(pixels > 20, "final framebuffer has no magenta particle pixels: " + pixels);
                    REPORT.put("emittingParticles", module.particleCount());
                    REPORT.put("emittingPixels", pixels);
                    previous = instance; retired = module;
                    if (LIVE_SYNC) {
                        reloadBaseline = lib.kasuga.rendering.models.mc.component.LiveModelSync.completedReloads();
                        var directory = lib.kasuga.rendering.models.mc.component.LiveModelSync.directory();
                        var modelFile = directory.resolve("assets/kasuga_lib/models/components/emitter_smoke.bbmodel");
                        Files.createDirectories(modelFile.getParent());
                        try (var source = Minecraft.getInstance().getResourceManager().getResourceOrThrow(
                                ResourceLocation.parse("kasuga_lib:models/components/emitter_smoke.bbmodel")).open()) {
                            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(source,
                                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                            json.getAsJsonObject("kasuga_lib").getAsJsonArray("emitters").get(0).getAsJsonObject()
                                    .getAsJsonObject("particle").addProperty("size", 4);
                            Files.writeString(modelFile, json.toString());
                        }
                        Files.writeString(directory.resolve(".kasuga-sync.json"),
                                "{\"version\":1,\"generation\":\"client-smoke-" + System.nanoTime() + "\"}");
                        lib.kasuga.rendering.models.mc.component.LiveModelSync.start();
                        reload = new CompletableFuture<>();
                    } else reload = Minecraft.getInstance().reloadResourcePacks();
                }
            } else {
                if (LIVE_SYNC && lib.kasuga.rendering.models.mc.component.LiveModelSync.completedReloads() > reloadBaseline)
                    reload.complete(null);
                if (!reload.isDone()) return;
                reload.join();
                if (instance == previous) return;
                check(previous.isClosed() && retired.closed() && retired.particleCount() == 0, "reload leaked old source");
                if (++reloadedFrames == 60) {
                    check(module.enabled() && module.particleCount() > 20, "reloaded source did not emit");
                    int pixels = capture("reloaded");
                    check(pixels > 20, "reloaded particle pixels missing: " + pixels);
                    REPORT.put("reloadedPixels", pixels);
                    REPORT.put("reloadReplacedInstance", true);
                    if (LIVE_SYNC) REPORT.put("automaticLiveReload", true);
                    retired = module; handle.destroy(); disposed = true;
                }
            }
        } catch (Throwable failure) { finish(failure); }
    }

    private static int capture(String name) throws Exception {
        var target = Minecraft.getInstance().getMainRenderTarget();
        byte[] rgba = RgbaReadback.copy(new FrameTexture(target.frameBufferId, target.getColorTextureId(), target.width, target.height));
        BufferedImage image = new BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB);
        int count = 0;
        for (int y = 0; y < target.height; y++) for (int x = 0; x < target.width; x++) {
            int p = (y * target.width + x) * 4;
            int r = rgba[p] & 255, g = rgba[p + 1] & 255, b = rgba[p + 2] & 255, a = rgba[p + 3] & 255;
            if (r > 170 && b > 150 && g < 90 && a > 0) count++;
            image.setRGB(x, y, a << 24 | r << 16 | g << 8 | b);
        }
        var output = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/model-emitters");
        Files.createDirectories(output);
        ImageIO.write(image, "PNG", output.resolve(name + ".png").toFile());
        return count;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void finish(Throwable failure) {
        if (finished) return;
        finished = true;
        if (LIVE_SYNC) lib.kasuga.rendering.models.mc.component.LiveModelSync.stop();
        try {
            if (handle != null && !handle.isDestroyed()) handle.destroy();
            REPORT.put("passed", failure == null);
            REPORT.put("failure", failure == null ? "" : failure.toString());
            REPORT.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
            var path = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/model-emitters/report.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure == null) LogUtils.getLogger().info("MODEL_EMITTER_CLIENT_SMOKE_PASS {}", path);
            else LogUtils.getLogger().error("MODEL_EMITTER_CLIENT_SMOKE_FAIL", failure);
        } catch (Exception cleanup) { LogUtils.getLogger().error("Cannot write model emitter report", cleanup); }
        Minecraft.getInstance().stop();
    }
}
