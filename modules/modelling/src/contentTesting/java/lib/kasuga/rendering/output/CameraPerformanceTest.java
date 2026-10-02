package lib.kasuga.rendering.output;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import jdk.jfr.*;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Real-client steady-state JFR comparisons, enabled only in a disposable quick-play directory. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CameraPerformanceTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testCameraPerformance");
    private static final int[] COUNTS = {0, 1, 2, 4, 0};
    private static final List<CameraHandle> CAMERAS = new ArrayList<>();
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static final long START = System.nanoTime();
    private static int warmup, phase;
    private static long phaseStart, frameStart;
    private static long published;
    private static Recording recording;
    private static final List<Long> FRAME_TIMES = new ArrayList<>();
    private static Path directory;
    private static boolean finished;
    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        if (System.nanoTime() - START > 240_000_000_000L) { finish(new IllegalStateException("Camera performance test timed out")); return; }
        if (mc.level == null || mc.player == null || mc.getOverlay() != null) return;
        try {
            if (++warmup < 120) return;
            if (directory == null) {
                directory = mc.gameDirectory.toPath().resolve("debug/camera-performance"); Files.createDirectories(directory);
                mc.options.enableVsync().set(false); mc.options.framerateLimit().set(260); mc.setScreen(null);
                phaseStart = System.nanoTime();
            }
            for (var camera : CAMERAS) if (camera.state() == lib.kasuga.rendering.output.camera.CameraState.FAILED)
                throw new IllegalStateException("Performance camera failed", camera.failure().orElse(null));
            long now = System.nanoTime();
            if (recording == null && now - phaseStart > 8_000_000_000L) {
                recording = new Recording(Configuration.getConfiguration("profile"));
                recording.setName("Kasuga cameras " + COUNTS[phase]);
                recording.enable("kasuga.CameraRenderPass").withThreshold(Duration.ZERO);
                recording.enable("kasuga.CameraChunkSnapshot").withThreshold(Duration.ZERO);
                recording.enable("jdk.ObjectAllocationSample");
                recording.enable("jdk.ThreadAllocationStatistics").withPeriod(Duration.ofSeconds(1));
                recording.start(); phaseStart = now; FRAME_TIMES.clear(); published = 0;
            } else if (recording != null && now - phaseStart > 12_000_000_000L) {
                recording.stop(); recording.dump(directory.resolve("cameras-" + COUNTS[phase] + (phase == COUNTS.length - 1 ? "-repeat" : "") + ".jfr")); recording.close(); recording = null;
                var sorted = FRAME_TIMES.stream().mapToDouble(n -> n / 1_000_000.0).sorted().toArray();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("extraCameras", COUNTS[phase]); result.put("frames", sorted.length); result.put("publishedFrames", published);
                result.put("meanRenderMs", Arrays.stream(sorted).average().orElse(0));
                result.put("p50RenderMs", sorted[sorted.length / 2]); result.put("p95RenderMs", sorted[(int)(sorted.length * 0.95)]);
                result.put("p99RenderMs", sorted[(int)(sorted.length * 0.99)]);
                result.put("framesPerSecond", sorted.length * 1e9 / (now - phaseStart)); RESULTS.add(result);
                LogUtils.getLogger().info("CAMERA_PERFORMANCE_PHASE {}", result);
                if (++phase == COUNTS.length) { finish(null); return; }
                while (CAMERAS.size() > COUNTS[phase]) CAMERAS.removeLast().close();
                while (CAMERAS.size() < COUNTS[phase]) {
                    int index = CAMERAS.size();
                    var eye = mc.player.getEyePosition();
                    var pose = new WorldCameraView(eye.x, eye.y + 3, eye.z, mc.player.getYRot() + index * 90, 20, 0, 70, 1280, 720);
                    var settings = new CameraRenderSettings(4, CameraRenderSettings.Quality.FANCY, CameraRenderSettings.Shader.disabled());
                    CAMERAS.add(MinecraftCameras.create("perf:camera-" + index, () -> pose, settings, frame -> published++));
                }
                phaseStart = now;
            }
            frameStart = recording == null ? 0 : System.nanoTime();
        } catch (Exception failure) { finish(failure); }
    }
    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (ENABLED && !finished && frameStart != 0) { FRAME_TIMES.add(System.nanoTime() - frameStart); frameStart = 0; }
    }
    private static void finish(Exception failure) {
        if (finished) return; finished = true;
        try {
            if (recording != null) { recording.stop(); recording.close(); }
            for (var camera : CAMERAS) camera.close();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("passed", failure == null); report.put("cameraResolution", "1280x720");
            report.put("windowResolution", Minecraft.getInstance().getWindow().getWidth() + "x" + Minecraft.getInstance().getWindow().getHeight());
            report.put("gpu", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER)); report.put("results", RESULTS);
            if (failure != null) report.put("failure", failure.toString());
            if (directory != null) Files.writeString(directory.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
            if (failure == null) LogUtils.getLogger().info("CAMERA_PERFORMANCE_PASS {}", directory);
            else LogUtils.getLogger().error("CAMERA_PERFORMANCE_FAIL", failure);
        } catch (Exception cleanup) { LogUtils.getLogger().error("Camera performance cleanup failed", cleanup); }
        Minecraft.getInstance().stop();
    }
}
