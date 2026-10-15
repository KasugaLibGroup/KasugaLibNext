package lib.kasuga.rendering.cloud;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.EffectHandle;
import lib.kasuga.rendering.cloud.gl.CloudVolumeRenderer;
import lib.kasuga.rendering.effect.builtin.cloud.CloudEffects;
import lib.kasuga.rendering.effect.builtin.cloud.CumulonimbusCloud;
import lib.kasuga.rendering.output.*;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
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

/** Disposable-world acceptance: two real camera outputs, tick ownership and instance removal. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CloudSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testCloud");
    private static final long START = System.nanoTime();
    private static CameraHandle front, side;
    private static EffectHandle<CumulonimbusCloud> handle;
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
            if (front == null) {
                if (CloudEffects.isEnabled()) throw new IllegalStateException("Volume clouds were enabled by default");
                originalCloudOption = mc.options.cloudStatus().get();
                originalCloudType = mc.options.getCloudsType();
                mc.options.pauseOnLostFocus = false; mc.setScreen(null);
                var eye = mc.player.getEyePosition();
                // Keep the acceptance volume above vanilla's opaque cloud layer.
                var pose = CloudPose.at(eye.x, Math.max(eye.y + 220, 384), eye.z - 1800);
                handle = CloudEffects.spawn(new CumulonimbusCloud(pose, CloudSettings.CUMULONIMBUS.withDensity(0)));
                CloudEffects.setSkyEnabled(false);
                CloudEffects.setEnabled(true);
                checkVanillaClouds(true);
                var settings = new CameraRenderSettings(8, CameraRenderSettings.Quality.FANCY, CameraRenderSettings.Shader.disabled());
                front = MinecraftCameras.create("test:cloud-front",
                        new WorldCameraView(pose.x(), pose.y() + 500, pose.z() + 2400, 180, -3, 0, 52, 384, 256), settings, CloudSmokeTest::capture);
                side = MinecraftCameras.create("test:cloud-side",
                        new WorldCameraView(pose.x() + 2400, pose.y() + 500, pose.z(), 90, -3, 0, 52, 256, 192), settings, CloudSmokeTest::capture);
            }
            for (var camera : new CameraHandle[]{front, side})
                if (camera.state() == CameraState.FAILED) throw new IllegalStateException("Cloud camera failed", camera.failure().orElse(null));
            sampledTime = Double.NaN;
            if (!CloudVolumeRenderer.prepareNoise().isDone()) return;
            frames++;
            if (frames == 45) handle.effect().settings(CloudSettings.CUMULONIMBUS);
            if (frames == 105) { CloudEffects.setEnabled(false); checkVanillaClouds(false); }
            if (frames == 115) { CloudEffects.setEnabled(true); checkVanillaClouds(true); }
            if (frames == 120) { handle.effect().close(); CloudEffects.setEnabled(false); checkVanillaClouds(false); }
        } catch (Exception failure) { finish(failure); }
    }

    private static void capture(OutputFrame<FrameTexture> output) {
        double time = handle.effect().volume().sample(1).timeSeconds();
        if (Double.isNaN(sampledTime)) sampledTime = time;
        else if (sampledTime != time) throw new IllegalStateException("Rendering advanced cloud time for another camera");
        if (frames != 40 && frames != 100 && frames != 110) return;
        String id = output.viewId();
        byte[] pixels = RgbaReadback.copy(output.resource());
        if (frames == 40) { BASELINES.put(id, pixels); return; }
        byte[] before = BASELINES.get(id);
        if (before == null || before.length != pixels.length) throw new IllegalStateException("Cloud output baseline missing: " + id);
        double difference = difference(before, pixels);
        if (frames == 100) {
            if (difference < .002) throw new IllegalStateException("Cloud did not reach world output: " + id + ", difference=" + difference);
            DIFFERENCES.put(id, difference); CLOUD_PIXELS.put(id, pixels); captured++;
        } else {
            if (!handle.isActive() || !handle.effect().isAlive()) throw new IllegalStateException("Toggle removed the cloud instance");
            if (difference > DIFFERENCES.get(id) * .4 + .001 || difference(CLOUD_PIXELS.get(id), pixels) < .002)
                throw new IllegalStateException("Disabled cloud still reached world output: " + id + ", difference=" + difference);
            DISABLED_DIFFERENCES.put(id, difference);
        }
        try {
            var image = new BufferedImage(output.width(), output.height(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < output.height(); y++) for (int x = 0; x < output.width(); x++) {
                int i = (y * output.width() + x) * 4;
                image.setRGB(x, y, 0xff000000 | (pixels[i] & 255) << 16 | (pixels[i + 1] & 255) << 8 | pixels[i + 2] & 255);
            }
            var path = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/cloud/" + id.replace(':', '_') + (frames == 110 ? "-disabled" : "") + ".png");
            Files.createDirectories(path.getParent()); ImageIO.write(image, "png", path.toFile());
        } catch (Exception failure) { throw new IllegalStateException("Cloud screenshot failed", failure); }
    }

    private static double difference(byte[] first, byte[] second) {
        double sum = 0;
        for (int i = 0; i < first.length; i++) if (i % 4 != 3) sum += Math.abs((first[i] & 255) - (second[i] & 255));
        return sum / (first.length / 4.0 * 3 * 255);
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
            if (captured != 2 || DIFFERENCES.size() != 2 || DISABLED_DIFFERENCES.size() != 2 || CloudEffects.visibleCount() != 0)
                finish(new IllegalStateException("Cloud cameras/removal were not verified"));
            else finish(null);
        }
    }

    private static void finish(Exception failure) {
        if (finished) return; finished = true;
        var mc = Minecraft.getInstance();
        try {
            CloudEffects.setEnabled(false);
            if (front != null) front.close(); if (side != null) side.close();
            if (handle != null) { handle.remove(); handle.effect().close(); }
            var path = mc.gameDirectory.toPath().resolve("debug/cloud/report.json"); Files.createDirectories(path.getParent());
            Files.writeString(path, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                    Map.of("passed", failure == null, "frames", frames, "cameraDifferences", DIFFERENCES, "cameraCount", captured,
                            "sameTimeAcrossViews", true, "removed", CloudEffects.visibleCount() == 0,
                            "disabledCameraDifferences", DISABLED_DIFFERENCES,
                            "vanillaSettingPreserved", mc.options.cloudStatus().get() == originalCloudOption,
                            "vanillaRestored", mc.options.getCloudsType() == originalCloudType)));
            if (failure == null) LogUtils.getLogger().info("CLOUD_SMOKE_PASS {}", path);
            else LogUtils.getLogger().error("CLOUD_SMOKE_FAIL", failure);
        } catch (Exception cleanup) { LogUtils.getLogger().error("Cloud smoke cleanup failed", cleanup); }
        mc.stop();
    }
}
