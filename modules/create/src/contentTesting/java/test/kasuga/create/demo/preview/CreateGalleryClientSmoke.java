package test.kasuga.create.demo.preview;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Set;

/** Opt-in real-world pixel/depth acceptance; separate from the player-controlled demo. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CreateGalleryClientSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testCreateGallery");
    private static final LinkedHashMap<String, Object> REPORT = new LinkedHashMap<>();
    private static final long START = System.nanoTime();
    private static boolean started, finished, returning;
    private static int stage, wait, warmup, returnWait;
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> originalDimension;
    private static Vec3 originalPosition;
    private static int originalMode;
    private static long frontPixels, sidePixels;
    private static String inspectionScreenshot;
    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        if (System.nanoTime() - START > 180_000_000_000L) { finish(new IllegalStateException("Gallery timeout at " + stage)); return; }
        if (mc.level == null || mc.player == null || mc.getOverlay() != null) return;
        if (!started) {
            if (++warmup < 40) return;
            originalDimension = mc.level.dimension(); originalPosition = mc.player.position();
            originalMode = mc.gameMode.getPlayerMode().getId();
            // Vanilla clouds also write depth; remove them from this isolated geometry probe.
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.hideGui = false; mc.options.pauseOnLostFocus = false; mc.setWindowActive(true);
            started = true;
            if (!CreateModelGallery.start()) finish(new IllegalStateException("Gallery did not start"));
        }
        if (CreateModelGallery.active() && stage == 0 && Boolean.getBoolean("kasuga.testCreateGallery.hide")) CreateModelGallery.entry().visible(false);
        if (returning && mc.level.dimension().equals(originalDimension) && ++returnWait >= 20) {
            try {
                check(mc.player.position().distanceTo(originalPosition) < 1, "Return changed player position: " + originalPosition + " -> " + mc.player.position());
                check(mc.gameMode.getPlayerMode().getId() == originalMode, "Return changed game mode");
                REPORT.put("returnedToOrigin", true); finish(null);
            } catch (Exception failure) { finish(failure); }
        }
    }
    @SubscribeEvent public static void world(RenderLevelStageEvent event) {
        if (!ENABLED || finished || returning || !CreateModelGallery.active()
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        var mc = Minecraft.getInstance();
        if (++wait < 20) {
            if (wait == 10 && mc.screen != null) inspectionScreenshot = "controls-" + stage + ".png";
            return;
        }
        try {
            check(mc.level.dimension().equals(CreateModelGallery.DIMENSION), "Wrong gallery dimension");
            int width = mc.getMainRenderTarget().width, height = mc.getMainRenderTarget().height;
            var depth = MemoryUtil.memAllocFloat(width * height);
            long covered = 0;
            try (var scope = new GuiRenderScope()) {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mc.getMainRenderTarget().frameBufferId);
                GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
                for (int i = 0; i < depth.capacity(); i++) if (depth.get(i) < .9999f) covered++;
            } finally { MemoryUtil.memFree(depth); }
            var output = mc.gameDirectory.toPath().resolve("debug/create-gallery"); Files.createDirectories(output);
            try (var scope = new GuiRenderScope(); var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(output.resolve("world-" + stage + ".png"));
            }
            REPORT.put("world-" + stage, java.util.Map.of("depthPixels", covered, "width", width, "height", height));
            if (stage == 1 || stage == 10) check(covered == 0, "Hidden/closed source still writes world depth: " + covered);
            else check(covered > 1000, "World source did not draw native geometry/depth: " + covered);
            if (stage == 0) {
                var click = new net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT,
                        org.lwjgl.glfw.GLFW.GLFW_PRESS, 0);
                CreateModelGallery.click(click);
                check(click.isCanceled(), "Gallery inspection click leaked to gameplay");
                var hit = CreateModelGallery.inspect().orElseThrow(() -> new IllegalStateException("Gallery crosshair did not inspect the rig"));
                REPORT.put("inspectedBlock", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(hit.state().getBlock()).toString());
                inspectionScreenshot = "inspection-front.png";
                frontPixels = covered; CreateModelGallery.entry().visible(false);
            } else if (stage == 1) {
                check(CreateModelGallery.inspect().isEmpty(), "Hidden world model remained pickable");
                CreateModelGallery.entry().visible(true); observe(24, 0, 90);
            } else if (stage == 2) {
                check(Math.abs(covered - frontPixels) > 100, "Side view duplicates front view"); observe(0, -24, 0);
            } else if (stage == 3) observe(-24, 0, -90);
            else if (stage == 4) {
                sidePixels = covered;
                check(CreateModelGallery.openControls(), "World inspection controls did not open");
                check(mc.screen != null, "World inspection screen is missing");
                double x = 204 + (mc.screen.width - 220) * .4;
                check(mc.screen.mouseClicked(x, mc.screen.height - 20, 0), "World explode slider rejected input");
                mc.screen.mouseReleased(x, mc.screen.height - 20, 0);
            } else if (stage == 5) {
                check(CreateModelGallery.layout().separation() > 1, "World explosion slider did not change layout");
                check(Math.abs(covered - sidePixels) > 1000, "World explosion did not change actual pixels");
                click(201, mc.screen.height - 20); click(140, mc.screen.height - 20);
                check(CreateModelGallery.layout().firstLayer() == CreateModelGallery.layout().lastLayer(), "World slice did not isolate one layer");
            } else if (stage == 6) {
                check(covered < sidePixels, "World single layer did not reduce displayed geometry");
                click(24, mc.screen.height - 20);
            } else if (stage == 7) {
                click(80, mc.screen.height - 20);
                click(12 + 4 + 172 * .65, mc.screen.height - 48);
            } else if (stage == 8) {
                check(covered > sidePixels * 1.1, "World scale slider did not enlarge native geometry");
                click(230, mc.screen.height - 48);
            } else if (stage == 9) {
                check(Math.abs((double) covered / sidePixels - 1) < .04, "World reset did not restore original scale/layout");
                click(320, mc.screen.height - 48);
                check(mc.screen == null, "Done did not restore world navigation");
                CreateModelGallery.entry().visible(false);
            } else {
                CreateModelGallery.stop(); returning = true; return;
            }
            check(GL11.glGetError() == GL11.GL_NO_ERROR, "Gallery left a GL error");
            stage++; wait = 0;
        } catch (Exception failure) { finish(failure); }
    }
    private static void click(double x, double y) {
        var screen = Minecraft.getInstance().screen;
        check(screen.mouseClicked(x, y, 0), "World inspection widget rejected click");
        screen.mouseReleased(x, y, 0);
    }
    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || inspectionScreenshot == null) return;
        try (var scope = new GuiRenderScope(); var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            image.writeToFile(Minecraft.getInstance().gameDirectory.toPath().resolve("debug/create-gallery/" + inspectionScreenshot));
        } catch (Exception failure) { finish(failure); }
        inspectionScreenshot = null;
    }
    private static void observe(double x, double z, float yaw) {
        var mc = Minecraft.getInstance(); var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(uuid);
            player.teleportTo(server.getLevel(CreateModelGallery.DIMENSION), x, 106, z, Set.of(), yaw, 0);
            player.getAbilities().flying = true; player.onUpdateAbilities();
        });
    }
    private static void check(boolean value, String text) { if (!value) throw new IllegalStateException(text); }
    private static void finish(Exception failure) {
        if (finished) return;
        finished = true;
        try {
            CreateModelGallery.stop(); REPORT.put("passed", failure == null); REPORT.put("failure", failure == null ? "" : failure.toString());
            var output = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/create-gallery/report.json");
            Files.createDirectories(output.getParent()); Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure == null) LogUtils.getLogger().info("CREATE_GALLERY_SMOKE_PASS {}", output);
            else LogUtils.getLogger().error("CREATE_GALLERY_SMOKE_FAIL", failure);
        } catch (Exception error) { LogUtils.getLogger().error("Cannot finish gallery smoke", error); }
        finally { Minecraft.getInstance().stop(); }
    }
}
