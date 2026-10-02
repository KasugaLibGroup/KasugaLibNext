package lib.kasuga.rendering.output;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.gl.FramePreviewWindow;
import lib.kasuga.rendering.output.mc.MinecraftFrameWindows;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import java.util.Map;

/** Native MC integration: multiple windows, window-driven resize, paused close, independent subscriptions. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class FrameWindowsSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testFrameWindows");
    private static final long START = System.nanoTime();
    private static MinecraftFrameWindows.WindowCamera first, second;
    private static MinecraftFrameWindows.WindowOutput mirror;
    private static MinecraftFrameWindows.WindowCamera paused;
    private static net.minecraft.client.multiplayer.ClientLevel mainLevel;
    private static Object mainRenderer, capabilities;
    private static int warmup, phase;
    private static long secondCount, startedResize;
    private static boolean finished;
    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        if (System.nanoTime() - START > 120_000_000_000L) { finish(new IllegalStateException("Window test timeout phase " + phase)); return; }
        if (mc.level == null || mc.player == null || mc.getOverlay() != null || ++warmup < 60) return;
        try {
            if (first == null) {
                mc.setScreen(null); mc.options.pauseOnLostFocus = false;
                mainLevel = mc.level; mainRenderer = mc.levelRenderer; capabilities = GL.getCapabilities();
                var eye = mc.player.getEyePosition();
                var settings = new CameraRenderSettings(4, CameraRenderSettings.Quality.FANCY, CameraRenderSettings.Shader.disabled());
                first = MinecraftFrameWindows.createCamera("window:first", () ->
                        new WorldCameraView(eye.x, eye.y + 2, eye.z, mc.player.getYRot(), 20, 0, 70, 320, 180), settings,
                        new FramePreviewWindow.Options("Kasuga test | Front", 320, 180));
                second = MinecraftFrameWindows.createCamera("window:second", () ->
                        new WorldCameraView(eye.x, eye.y + 2, eye.z, mc.player.getYRot() + 180, 20, 0, 90, 320, 180), settings,
                        new FramePreviewWindow.Options("Kasuga test | Rear", 320, 180));
                mirror = MinecraftFrameWindows.open("window:second", new FramePreviewWindow.Options("Kasuga test | Rear mirror", 240, 240));
                paused = MinecraftFrameWindows.createCamera("window:paused", () ->
                        new WorldCameraView(eye.x, eye.y, eye.z, 0, 0, 0, 70, 32, 32), settings,
                        new FramePreviewWindow.Options("Kasuga test | Paused", 32, 32, false));
                paused.pause(); paused.requestClose();
            }
        } catch (Exception failure) { finish(failure); }
    }
    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || first == null) return;
        try {
            var mc = Minecraft.getInstance();
            if (mc.level != mainLevel || mc.levelRenderer != mainRenderer || GL.getCapabilities() != capabilities
                    || GLFW.glfwGetCurrentContext() != mc.getWindow().getWindow())
                throw new IllegalStateException("Secondary presentation corrupted the main render context");
            if (first.state() == CameraState.FAILED || second.state() == CameraState.FAILED)
                throw new IllegalStateException("Window camera failed", first.failure().orElse(second.failure().orElse(null)));
            if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new IllegalStateException("MC window GL error");
            if (phase == 0 && first.presentedFrames() > 30 && second.presentedFrames() > 30 && mirror.presentedFrames() > 30) {
                if (!paused.isClosed() || paused.state() != CameraState.CLOSED) throw new IllegalStateException("Paused window did not close via event pump");
                first.resize(400, 250); startedResize = first.presentedFrames(); phase = 1;
            } else if (phase == 1 && first.presentedFrames() > startedResize + 20) {
                var size = first.framebufferSize();
                // Native window dimensions are logical; framebuffer size may include HiDPI scaling.
                if (size[0] * 250 != size[1] * 400) throw new IllegalStateException("Resize aspect not applied");
                secondCount = second.presentedFrames(); first.requestClose(); mirror.requestClose(); phase = 2;
            } else if (phase == 2 && second.presentedFrames() > secondCount + 20) {
                if (!first.isClosed() || first.state() != CameraState.CLOSED || !mirror.isClosed())
                    throw new IllegalStateException("Window close did not release its ownership");
                if (second.state() != CameraState.READY || second.windowHandle() == 0)
                    throw new IllegalStateException("Closing another window stopped the surviving camera");
                finish(null);
            }
        } catch (Exception failure) { finish(failure); }
    }
    private static void finish(Exception failure) {
        if (finished) return; finished = true;
        var mc = Minecraft.getInstance();
        try {
            MinecraftFrameWindows.shutdown();
            if (second != null && second.state() != CameraState.CLOSED) throw new IllegalStateException("Shutdown left camera alive");
            var report = mc.gameDirectory.toPath().resolve("debug/frame-windows.json");
            java.nio.file.Files.createDirectories(report.getParent());
            java.nio.file.Files.writeString(report, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                    Map.of("passed", failure == null, "resize", phase >= 2, "independentClose", phase >= 2,
                            "failure", failure == null ? "" : failure.toString())));
            if (failure == null) LogUtils.getLogger().info("FRAME_WINDOWS_SMOKE_PASS {}", report);
            else LogUtils.getLogger().error("FRAME_WINDOWS_SMOKE_FAIL", failure);
        } catch (Exception cleanup) { LogUtils.getLogger().error("Window smoke cleanup failed", cleanup); }
        mc.stop();
    }
}
