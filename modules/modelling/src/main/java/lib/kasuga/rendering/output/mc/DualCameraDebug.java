package lib.kasuga.rendering.output.mc;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.gl.FramebufferScope;
import lib.kasuga.rendering.output.gl.OutputFramebuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Opt-in live split preview. Owns copies; router textures are borrowed only during callbacks. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class DualCameraDebug {
    private static final boolean MULTI_WINDOW = Boolean.getBoolean("kasuga.debugMultiWindow");
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.debugDualCamera") || MULTI_WINDOW;
    private static final int CAPTURE_FRAMES = Integer.getInteger("kasuga.debugDualCamera.captureFrames", 0);
    private static final CameraHandle[] CAMERAS = new CameraHandle[2];
    private static final OutputFramebuffer[] PREVIEWS = new OutputFramebuffer[2];
    private static final FrameTexture[] FRAMES = new FrameTexture[2];
    private static int shown;
    private static boolean failed, finished;
    private static Boolean previousPauseOnLostFocus;
    private DualCameraDebug() {}

    @SubscribeEvent public static void hideFirstPersonBody(RenderLivingEvent.Pre<?, ?> event) {
        var player = Minecraft.getInstance().player;
        if (ENABLED && player != null && MinecraftWorldViews.currentViewId().equals("debug:first-person")
                && event.getEntity().getUUID().equals(player.getUUID())) event.setCanceled(true);
    }

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || failed || finished) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { close(); return; }
        if (mc.getOverlay() != null || CAMERAS[0] != null) return;
        try {
            var settings = new CameraRenderSettings(8, CameraRenderSettings.Quality.FANCY,
                    CameraRenderSettings.Shader.disabled());
            if (MULTI_WINDOW) { previousPauseOnLostFocus = mc.options.pauseOnLostFocus; mc.options.pauseOnLostFocus = false; }
            for (int index = 0; index < 2; index++) {
                int slot = index;
                if (MULTI_WINDOW) {
                    CAMERAS[slot] = MinecraftFrameWindows.createCamera("debug:" + (slot == 0 ? "first-person" : "third-person"),
                            () -> pose(slot), settings, new lib.kasuga.rendering.output.gl.FramePreviewWindow.Options(
                                    "Kasuga DEBUG | " + (slot == 0 ? "First person" : "Third person"), 640, 360));
                    continue;
                }
                PREVIEWS[slot] = new OutputFramebuffer(MinecraftFrameOutputs.restorer());
                CAMERAS[slot] = MinecraftCameras.create("debug:" + (slot == 0 ? "first-person" : "third-person"),
                        () -> pose(slot), settings, frame -> {
                            var source = frame.resource();
                            FRAMES[slot] = PREVIEWS[slot].capture((width, height) -> {
                                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.framebufferId());
                                GL30.glBlitFramebuffer(0, 0, source.width(), source.height(), 0, 0, width, height,
                                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
                            }, source.width(), source.height());
                        });
            }
            if (!MULTI_WINDOW) mc.getWindow().setTitle("Kasuga DEBUG | Left: first person | Right: third person");
            if (CAPTURE_FRAMES > 0) { mc.options.pauseOnLostFocus = false; mc.setScreen(null); }
            LogUtils.getLogger().info("DUAL_CAMERA_DEBUG_READY: first-person + third-person, {}", MULTI_WINDOW ? "separate windows" : "split preview");
        } catch (RuntimeException failure) { fail(failure); }
    }

    private static WorldCameraView pose(int slot) {
        var mc = Minecraft.getInstance();
        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(true);
        var eye = mc.player.getEyePosition(partialTick);
        float yaw = mc.player.getViewYRot(partialTick), pitch = mc.player.getViewXRot(partialTick);
        var position = eye;
        if (slot == 1) {
            var behind = eye.subtract(mc.player.getViewVector(partialTick).scale(4));
            var hit = mc.level.clip(new ClipContext(eye, behind, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            position = hit.getType() == HitResult.Type.MISS ? behind : hit.getLocation().lerp(eye, 0.08);
        }
        var target = mc.getMainRenderTarget();
        if (MULTI_WINDOW) return new WorldCameraView(position.x, position.y, position.z, yaw, pitch, 0,
                mc.options.fov().get(), 640, 360);
        return new WorldCameraView(position.x, position.y, position.z, yaw, pitch, 0,
                mc.options.fov().get(), Math.max(1, target.width / 2), Math.max(1, target.height));
    }

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || failed || finished || CAMERAS[0] == null) return;
        var mc = Minecraft.getInstance();
        try {
            if (MULTI_WINDOW) {
                for (var camera : CAMERAS) if (camera.state() == CameraState.FAILED)
                    throw new IllegalStateException("Debug camera failed", camera.failure().orElse(null));
                if (CAPTURE_FRAMES > 0 && java.util.Arrays.stream(CAMERAS).allMatch(c ->
                        ((MinecraftFrameWindows.WindowCamera) c).presentedFrames() >= CAPTURE_FRAMES)) {
                    LogUtils.getLogger().info("MULTI_WINDOW_DEBUG_PASS"); finished = true; close(); mc.stop();
                }
                return;
            }
            for (var camera : CAMERAS) if (camera.state() == CameraState.FAILED)
                throw new IllegalStateException("Debug camera failed", camera.failure().orElse(null));
            if (mc.screen != null || mc.getOverlay() != null || FRAMES[0] == null || FRAMES[1] == null) return;
            var target = mc.getMainRenderTarget();
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            try (var ignored = new FramebufferScope(MinecraftFrameOutputs.restorer())) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
                for (int slot = 0; slot < 2; slot++) {
                    var source = FRAMES[slot];
                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.framebufferId());
                    GL30.glBlitFramebuffer(0, 0, source.width(), source.height(),
                            slot * target.width / 2, 0, (slot + 1) * target.width / 2, target.height,
                            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
                }
            } finally { if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); }
            if (CAPTURE_FRAMES > 0 && ++shown == CAPTURE_FRAMES) {
                var file = mc.gameDirectory.toPath().resolve("debug/dual-camera.png");
                java.nio.file.Files.createDirectories(file.getParent());
                try (var image = Screenshot.takeScreenshot(target)) { image.writeToFile(file); }
                LogUtils.getLogger().info("DUAL_CAMERA_DEBUG_PASS {}", file);
                finished = true;
                close(); mc.stop();
            }
        } catch (Exception failure) { fail(failure); }
    }

    private static void fail(Exception failure) {
        failed = true;
        LogUtils.getLogger().error("DUAL_CAMERA_DEBUG_FAIL", failure);
        close();
        if (CAPTURE_FRAMES > 0) Minecraft.getInstance().stop();
    }
    public static void close() {
        for (int slot = 0; slot < 2; slot++) {
            if (CAMERAS[slot] != null) try { CAMERAS[slot].close(); }
            catch (Exception failure) { LogUtils.getLogger().error("Cannot close debug camera", failure); }
            CAMERAS[slot] = null;
            if (PREVIEWS[slot] != null) PREVIEWS[slot].close();
            PREVIEWS[slot] = null; FRAMES[slot] = null;
        }
        shown = 0;
        if (previousPauseOnLostFocus != null) {
            Minecraft.getInstance().options.pauseOnLostFocus = previousPauseOnLostFocus;
            previousPauseOnLostFocus = null;
        }
    }
}
