package lib.kasuga.rendering.output;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import lib.kasuga.rendering.output.mc.HeadlessClient;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import java.nio.file.Files;
import java.util.LinkedHashMap;

/** Real client/world smoke; launch with no display variables on Linux, not Xvfb. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class HeadlessClientSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testHeadless");
    private static final long START = System.nanoTime();
    private static final CameraHandle[] CAMERAS = new CameraHandle[2];
    private static final int[] CAPTURED = new int[3];
    private static boolean started, finished;
    private static int warmup;
    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        if (System.nanoTime() - START > 240_000_000_000L) { finish(new IllegalStateException("Headless world/capture timeout")); return; }
        if (started || mc.level == null || mc.player == null || mc.getOverlay() != null || ++warmup < 60) return;
        try {
            if (!HeadlessClient.enabled()) throw new IllegalStateException("Smoke requires the headless launcher");
            verifyWindow();
            mc.setScreen(null);
            MinecraftFrameOutputs.open(FrameOutputMode.OFFSCREEN_ONLY, frame -> capture(0, frame));
            var settings = new CameraRenderSettings(4, CameraRenderSettings.Quality.FANCY, CameraRenderSettings.Shader.disabled());
            for (int i = 0; i < 2; i++) {
                int index = i;
                CAMERAS[i] = MinecraftCameras.create("headless:" + i, () -> {
                    var eye = mc.player.getEyePosition(mc.getTimer().getGameTimeDeltaPartialTick(true));
                    return new WorldCameraView(eye.x, eye.y + 3 + index * 3, eye.z + index * 5,
                            mc.player.getYRot(), index == 0 ? 35 : 25, 0,
                            index == 0 ? 70 : 90, 320, 180);
                }, settings, frame -> capture(index + 1, frame));
            }
            started = true;
        } catch (Exception failure) { finish(failure); }
    }
    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || !started || finished) return;
        try {
            verifyWindow();
            for (var camera : CAMERAS) if (camera.state() == CameraState.FAILED)
                throw new IllegalStateException("Headless camera failed", camera.failure().orElse(null));
            if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new IllegalStateException("Headless GL error");
            if (CAPTURED[0] >= 80 && CAPTURED[1] >= 80 && CAPTURED[2] >= 80) finish(null);
        } catch (Exception failure) { finish(failure); }
    }
    private static void verifyWindow() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) != GLFW.GLFW_FALSE)
            throw new IllegalStateException("Headless MC window became visible");
        if (GLFW.glfwGetCurrentContext() != window) throw new IllegalStateException("Headless main context not restored");
        if (HeadlessClient.egl() && (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_NULL
                || System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null))
            throw new IllegalStateException("EGL test must use Null platform and no display service");
    }
    private static void capture(int index, OutputFrame<FrameTexture> frame) {
        if (++CAPTURED[index] != 60) return;
        try {
            byte[] rgba = RgbaReadback.copy(frame.resource());
            var image = new java.awt.image.BufferedImage(frame.width(), frame.height(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var colors = new java.util.HashSet<Integer>();
            for (int y = 0; y < frame.height(); y++) for (int x = 0; x < frame.width(); x++) {
                int offset = (y * frame.width() + x) * 4;
                int pixel = (rgba[offset + 3] & 255) << 24 | (rgba[offset] & 255) << 16 | (rgba[offset + 1] & 255) << 8 | rgba[offset + 2] & 255;
                if ((pixel >>> 24) != 255) throw new IllegalStateException("Output alpha is not opaque");
                image.setRGB(x, y, pixel); colors.add(pixel);
            }
            if (colors.size() < 32) throw new IllegalStateException("Output has no rendered world detail: " + colors.size());
            var path = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/headless-" + index + ".png");
            Files.createDirectories(path.getParent());
            javax.imageio.ImageIO.write(image, "PNG", path.toFile());
        } catch (Exception failure) { throw new IllegalStateException("Headless capture " + index + " failed", failure); }
    }
    private static void finish(Exception failure) {
        if (finished) return; finished = true;
        var mc = Minecraft.getInstance();
        try {
            for (var camera : CAMERAS) if (camera != null) camera.close();
            var report = new LinkedHashMap<String, Object>();
            report.put("passed", failure == null); report.put("backend", HeadlessClient.backend());
            report.put("platform", GLFW.glfwGetPlatform()); report.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
            report.put("display", String.valueOf(System.getenv("DISPLAY"))); report.put("waylandDisplay", String.valueOf(System.getenv("WAYLAND_DISPLAY")));
            report.put("captures", CAPTURED); report.put("failure", failure == null ? "" : failure.toString());
            var path = mc.gameDirectory.toPath().resolve("debug/headless-client.json");
            Files.createDirectories(path.getParent()); Files.writeString(path, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
            if (failure == null) LogUtils.getLogger().info("HEADLESS_CLIENT_SMOKE_PASS {}", path);
            else LogUtils.getLogger().error("HEADLESS_CLIENT_SMOKE_FAIL", failure);
        } catch (Exception cleanup) { LogUtils.getLogger().error("Headless test report/cleanup failed", cleanup); }
        mc.stop();
    }
}
