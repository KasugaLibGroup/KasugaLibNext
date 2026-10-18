package lib.kasuga.rendering.cloud;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.cloud.gl.CloudVolumeRenderer;
import lib.kasuga.rendering.effect.builtin.cloud.CloudEffects;
import lib.kasuga.rendering.output.*;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CloudStatus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** Disposable-world acceptance: whole sky at two world positions, toggle restoration and shared clock. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class SkyCloudSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testSkyCloud");
    private static final long START = System.nanoTime();
    private static CameraHandle front, side;
    private static FrameOutputRouter<FrameTexture>.Registration mainOutput;
    private static final Map<String, byte[]> BASELINES = new HashMap<>();
    private static final Map<String, Double> DIFFERENCES = new HashMap<>();
    private static final Map<String, byte[]> CLOUD_PIXELS = new HashMap<>();
    private static final Map<String, Double> DISABLED_DIFFERENCES = new HashMap<>();
    private static CloudStatus originalCloudOption, originalCloudType;
    private static int warmup, frames, captured;
    private static double sampledTime = Double.NaN;
    private static boolean finished;

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        if (System.nanoTime() - START > 180_000_000_000L) { finish(new IllegalStateException("Cloud smoke timed out")); return; }
        if (mc.level == null || mc.player == null || mc.getOverlay() != null || ++warmup < 60) return;
        try {
            // This harness uses a disposable world. Freeze lighting to compare the toggle itself.
            mc.player.setYRot(180); mc.player.setXRot(-12);
            mc.level.setDayTime(6000); mc.level.setRainLevel(0); mc.level.setThunderLevel(0);
            if (front == null) {
                if (CloudEffects.isEnabled()) throw new IllegalStateException("Volume clouds were enabled by default");
                originalCloudOption = mc.options.cloudStatus().get();
                originalCloudType = mc.options.getCloudsType();
                mc.options.pauseOnLostFocus = false; mc.options.hideGui = true; mc.setScreen(null);
                var server = mc.getSingleplayerServer();
                if (server != null) server.execute(() -> {
                    server.overworld().setDayTime(6000);
                    server.overworld().setWeatherParameters(6000, 0, false, false);
                    server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
                    server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server);
                });
                var eye = mc.player.getEyePosition();
                CloudEffects.setSkyEnabled(true);
                CloudEffects.sky().settings(SkyCloudSettings.DEFAULT);
                CloudEffects.setEnabled(false);
                checkVanillaClouds(false);
                var settings = new CameraRenderSettings(8, CameraRenderSettings.Quality.FANCY, CameraRenderSettings.Shader.disabled());
                front = MinecraftCameras.create("test:sky-near",
                        new WorldCameraView(eye.x, 300, eye.z, 180, -12, 0, 70, 512, 320), settings, SkyCloudSmokeTest::capture);
                mainOutput = MinecraftFrameOutputs.open(FrameOutputMode.MIRROR, SkyCloudSmokeTest::capture);
                side = MinecraftCameras.create("test:sky-far",
                        new WorldCameraView(eye.x + 40000, 300, eye.z - 40000, 0, -12, 0, 70, 384, 256), settings, SkyCloudSmokeTest::capture);
            }
            for (var camera : new CameraHandle[]{front, side})
                if (camera.state() == CameraState.FAILED) throw new IllegalStateException("Cloud camera failed", camera.failure().orElse(null));
            sampledTime = Double.NaN;
            if (!CloudVolumeRenderer.prepareNoise().isDone()) return;
            frames++;
            if (frames == 45) { CloudEffects.setEnabled(true); checkVanillaClouds(true); }
            if (frames == 105) { CloudEffects.setEnabled(false); checkVanillaClouds(false); }
            if (frames == 115) { CloudEffects.setEnabled(true); checkVanillaClouds(true); }
            if (frames == 120) { CloudEffects.setEnabled(false); checkVanillaClouds(false); }
        } catch (Exception failure) { finish(failure); }
    }

    private static void capture(OutputFrame<FrameTexture> output) {
        double observerX, observerY, observerZ;
        if (output.viewId().equals(MinecraftFrameOutputs.MAIN_VIEW)) {
            var observer = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            observerX = observer.x; observerY = observer.y; observerZ = observer.z;
        } else {
            var observer = output.viewId().equals("test:sky-near") ? front.pose() : side.pose();
            observerX = observer.x(); observerY = observer.y(); observerZ = observer.z();
        }
        var sample = CloudEffects.sky().sample(observerX, observerY, observerZ, 1, 0);
        double time = sample.timeSeconds();
        if (frames >= 45 && sample.weatherCellCount() < 200) throw new IllegalStateException("Sky field is not populated");
        if (Double.isNaN(sampledTime)) sampledTime = time;
        else if (sampledTime != time) throw new IllegalStateException("Rendering advanced cloud time for another camera");
        if (frames != 40 && frames != 100 && frames != 110) return;
        String id = output.viewId();
        byte[] pixels = RgbaReadback.copy(output.resource());
        if (frames == 40) { BASELINES.put(id, pixels); return; }
        byte[] before = BASELINES.get(id);
        if (before == null || before.length != pixels.length) throw new IllegalStateException("Cloud output baseline missing: " + id);
        double difference = difference(before, pixels, output);
        if (frames == 100) {
            if (difference < .002) throw new IllegalStateException("Cloud did not reach world output: " + id + ", difference=" + difference);
            DIFFERENCES.put(id, difference); CLOUD_PIXELS.put(id, pixels); captured++;
        } else {
            if (difference > DIFFERENCES.get(id) * .4 + .001 || difference(CLOUD_PIXELS.get(id), pixels, output) < .002)
                throw new IllegalStateException("Disabled cloud still reached world output: " + id + ", difference=" + difference);
            DISABLED_DIFFERENCES.put(id, difference);
        }
        try {
            var image = new BufferedImage(output.width(), output.height(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < output.height(); y++) for (int x = 0; x < output.width(); x++) {
                int i = (y * output.width() + x) * 4;
                image.setRGB(x, y, 0xff000000 | (pixels[i] & 255) << 16 | (pixels[i + 1] & 255) << 8 | pixels[i + 2] & 255);
            }
            var path = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/sky-cloud/" + id.replace(':', '_') + (frames == 110 ? "-disabled" : "") + ".png");
            Files.createDirectories(path.getParent()); ImageIO.write(image, "png", path.toFile());
        } catch (Exception failure) { throw new IllegalStateException("Cloud screenshot failed", failure); }
    }

    private static double difference(byte[] first, byte[] second, OutputFrame<FrameTexture> output) {
        // Main output includes animated water, entities and hands; compare its central sky only.
        boolean main = output.viewId().equals(MinecraftFrameOutputs.MAIN_VIEW);
        int left = main ? output.width() / 10 : 0, right = main ? output.width() * 9 / 10 : output.width();
        int top = main ? output.height() * 35 / 100 : 0, bottom = main ? output.height() * 65 / 100 : output.height();
        double sum = 0;
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) for (int c = 0; c < 3; c++) {
            int i = (y * output.width() + x) * 4 + c;
            sum += Math.abs((first[i] & 255) - (second[i] & 255));
        }
        return sum / ((bottom - top) * (right - left) * 3.0 * 255);
    }

    private static void checkVanillaClouds(boolean hidden) {
        var options = Minecraft.getInstance().options;
        if (options.cloudStatus().get() != originalCloudOption) throw new IllegalStateException("Test changed saved vanilla cloud setting");
        if (options.getCloudsType() != (hidden ? CloudStatus.OFF : originalCloudType))
            throw new IllegalStateException("Vanilla cloud toggle was not applied/restored");
    }

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || front == null) return;
        if (GL11.glGetError() != GL11.GL_NO_ERROR) { finish(new IllegalStateException("Cloud render GL error")); return; }
        if (frames >= 130) {
            if (captured != 3 || DIFFERENCES.size() != 3 || DISABLED_DIFFERENCES.size() != 3 || CloudEffects.visibleCount() != 0)
                finish(new IllegalStateException("Cloud cameras/removal were not verified"));
            else finish(null);
        }
    }

    private static void finish(Exception failure) {
        if (finished) return; finished = true;
        var mc = Minecraft.getInstance();
        try {
            CloudEffects.setEnabled(false);
            if (mainOutput != null) mainOutput.close();
            if (front != null) front.close(); if (side != null) side.close();
            var path = mc.gameDirectory.toPath().resolve("debug/sky-cloud/report.json"); Files.createDirectories(path.getParent());
            Files.writeString(path, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                    Map.of("passed", failure == null, "frames", frames, "cameraDifferences", DIFFERENCES, "viewCount", captured,
                            "sameTimeAcrossViews", true, "weatherCellsPerWindow", CloudEffects.sky().sample(0, 300, 0, 1, 0).weatherCellCount(),
                            "disabledCameraDifferences", DISABLED_DIFFERENCES,
                            "vanillaSettingPreserved", mc.options.cloudStatus().get() == originalCloudOption,
                            "vanillaRestored", mc.options.getCloudsType() == originalCloudType)));
            if (failure == null) LogUtils.getLogger().info("SKY_CLOUD_SMOKE_PASS {}", path);
            else LogUtils.getLogger().error("SKY_CLOUD_SMOKE_FAIL", failure);
        } catch (Exception cleanup) { LogUtils.getLogger().error("Cloud smoke cleanup failed", cleanup); }
        mc.stop();
    }
}
