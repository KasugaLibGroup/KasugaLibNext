package test.kasuga.create.demo.preview;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;

/** Verifies native entity motion through assembled, exploded and isolated-layer display modes. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CreateWindmillClientSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testWindmill");
    private static final LinkedHashMap<String, Object> REPORT = new LinkedHashMap<>();
    private static final long START = System.nanoTime();
    private static boolean started, selected, finished;
    private static int warmup, stage;
    private static long tick;
    private static float angle;
    private static byte[] baseline;
    private static float[] baselineDepth;
    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        if (System.nanoTime() - START > 180_000_000_000L) { finish(new IllegalStateException("Windmill timeout at " + stage)); return; }
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getOverlay() != null) return;
        if (!started && ++warmup > 40) {
            started = true; mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.hideGui = true; mc.options.pauseOnLostFocus = false; mc.setWindowActive(true);
            if (!CreateModelGallery.start()) finish(new IllegalStateException("Cannot start windmill gallery"));
        }
        if (!selected && CreateModelGallery.windmillEntity() != null && CreateModelGallery.selectWindmill(true)) {
            selected = true; tick = mc.level.getGameTime();
            if (Boolean.getBoolean("kasuga.testWindmill.hide")) CreateModelGallery.entry().visible(false);
        }
    }
    @SubscribeEvent public static void world(RenderLevelStageEvent event) {
        if (!ENABLED || finished || !selected || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        var mc = Minecraft.getInstance();
        if (mc.level.getGameTime() - tick < 16) return;
        try {
            var rotor = CreateModelGallery.windmillEntity();
            check(rotor != null && rotor.getContraption().entity == rotor, "Native Contraption entity backlink changed");
            var target = mc.getMainRenderTarget();
            byte[] pixels = RgbaReadback.copy(new FrameTexture(target.frameBufferId, target.getColorTextureId(), target.width, target.height));
            float[] depth = new float[target.width * target.height];
            var depthBuffer = org.lwjgl.system.MemoryUtil.memAllocFloat(depth.length);
            try (var scope = new GuiRenderScope()) {
                org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
                org.lwjgl.opengl.GL11.glReadPixels(0, 0, target.width, target.height, org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, org.lwjgl.opengl.GL11.GL_FLOAT, depthBuffer);
                depthBuffer.get(depth);
            } finally { org.lwjgl.system.MemoryUtil.memFree(depthBuffer); }
            float current = rotor.getAngle(1);
            REPORT.put("stage-" + stage, java.util.Map.of("angle", current, "tick", mc.level.getGameTime(), "parts", rotor.getContraption().getBlocks().size()));
            var output = mc.gameDirectory.toPath().resolve("debug/windmill"); Files.createDirectories(output);
            try (var scope = new GuiRenderScope(); var screenshot = Screenshot.takeScreenshot(target)) {
                screenshot.writeToFile(output.resolve("windmill-" + stage + ".png"));
            }
            check(CreateModelGallery.inspect().isPresent() || stage >= 4, "Visible native windmill cannot be picked");
            if (stage % 2 == 0) { baseline = pixels; baselineDepth = depth; angle = current; }
            else {
                float delta = Math.abs(net.minecraft.util.Mth.wrapDegrees(current - angle));
                check(delta > 2, "Native entity stopped rotating in display mode " + stage + ": " + delta);
                check(!Arrays.equals(baseline, pixels), "Native entity angle changed without changing actual pixels in mode " + stage);
                int changedDepth = 0;
                for (int i = 0; i < depth.length; i++) if (Math.abs(depth[i] - baselineDepth[i]) > 1e-7) changedDepth++;
                check(changedDepth > 20, "Native geometry did not move in the actual depth buffer: " + changedDepth);
                REPORT.put("motion-" + stage, java.util.Map.of("angleDelta", delta, "changedDepthPixels", changedDepth));
                if (stage == 1) {
                    check(CreateModelGallery.openControls(), "Windmill inspect panel did not open");
                    double x = 204 + (mc.screen.width - 220) * .2;
                    click(x, mc.screen.height - 20);
                    check(CreateModelGallery.layout().separation() > .3, "Windmill explosion slider did not apply");
                } else if (stage == 3) {
                    click(140, mc.screen.height - 20);
                    check(CreateModelGallery.layout().firstLayer() == CreateModelGallery.layout().lastLayer(), "Windmill single layer control failed");
                } else { REPORT.put("nativeEntity", rotor.getClass().getSimpleName()); finish(null); return; }
            }
            tick = mc.level.getGameTime(); stage++;
        } catch (Exception failure) { finish(failure); }
    }
    private static void click(double x, double y) {
        var screen = Minecraft.getInstance().screen;
        check(screen.mouseClicked(x, y, 0), "Windmill control click rejected");screen.mouseReleased(x, y, 0);
    }
    private static void check(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
    private static void finish(Exception failure) {
        if (finished) return; finished = true;
        try {
            CreateModelGallery.stop(); REPORT.put("passed", failure == null); REPORT.put("failure", failure == null ? "" : failure.toString());
            var path = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/windmill/report.json");
            Files.createDirectories(path.getParent()); Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure == null) LogUtils.getLogger().info("CREATE_WINDMILL_SMOKE_PASS {}", path);
            else LogUtils.getLogger().error("CREATE_WINDMILL_SMOKE_FAIL", failure);
        } catch (Exception error) { LogUtils.getLogger().error("Cannot report windmill smoke", error); }
        finally { Minecraft.getInstance().stop(); }
    }
}
