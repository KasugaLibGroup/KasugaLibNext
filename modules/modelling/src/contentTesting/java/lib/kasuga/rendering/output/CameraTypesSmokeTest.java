package lib.kasuga.rendering.output;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.camera.*;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import lib.kasuga.rendering.output.mc.MinecraftCameraTargets;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;

/** Real-client acceptance for native player output, independent free views and recoverable entity following. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CameraTypesSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testCameraTypes");
    private static final long START = System.nanoTime();
    private static final CameraRenderSettings INITIAL = new CameraRenderSettings(4, CameraRenderSettings.Quality.FANCY, CameraRenderSettings.Shader.disabled());
    private static final CameraRenderSettings UPDATED = new CameraRenderSettings(2, CameraRenderSettings.Quality.FAST, CameraRenderSettings.Shader.disabled());
    private static final AtomicReference<Entity> ENTITY = new AtomicReference<>();
    private static CameraHandle player, free, fixed;
    private static FrameOutputRouter<FrameTexture>.Registration mirror;
    private static OutputFrame<FrameTexture> playerFrame;
    private static OutputFrame<FrameTexture> mirrorFrame;
    private static int warmup, frames, playerFrames, mirrorFrames, freeFrames, fixedFrames, phase, waitStart, pausedFrames, pausedMirrors;
    private static boolean finished, worldDetail, settingsChangedInCallback;

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        if (System.nanoTime() - START > 180_000_000_000L) { finish(new IllegalStateException("Camera types timed out")); return; }
        if (mc.level == null || mc.player == null || mc.getOverlay() != null || ++warmup < 60) return;
        try {
            if (player == null) {
                mc.options.pauseOnLostFocus = false; mc.setScreen(null);
                ENTITY.set(mc.player);
                player = MinecraftCameras.createPlayer(CameraTypesSmokeTest::verifyPlayer);
                mirror = MinecraftFrameOutputs.open(FrameOutputMode.MIRROR, frame -> {
                    if (playerFrame != null && playerFrame.frameNumber() == frame.frameNumber() && playerFrame != frame)
                        throw new IllegalStateException("Player did not share native output storage");
                    mirrorFrame = frame;
                    mirrorFrames++;
                });
                var eye = mc.player.getEyePosition();
                free = MinecraftCameras.createFree("test:free", new WorldCameraView(eye.x, eye.y + 2, eye.z + 4,
                        mc.player.getYRot(), 20, 0, 70, 320, 180), INITIAL, CameraTypesSmokeTest::verifyFree);
                fixed = MinecraftCameras.createFixed("test:fixed", MinecraftCameraTargets.entity(ENTITY::get),
                        new CameraProjection(65, 320, 180), new CameraFollowSettings(0, 2, 4, 0, 10, 0, true),
                        INITIAL, CameraTypesSmokeTest::verifyFixed);
                if (player.type() != CameraType.PLAYER || free.type() != CameraType.FREE || fixed.type() != CameraType.FIXED)
                    throw new IllegalStateException("Wrong camera classification");
                if (player.renderSettings().isPresent()) throw new IllegalStateException("Player settings did not remain native");
            }
            if (phase == 0 && fixedFrames >= 30) {
                ENTITY.set(null); player.pause(); phase = 1; waitStart = frames;
                pausedFrames = playerFrames; pausedMirrors = mirrorFrames;
            } else if (phase == 1 && frames - waitStart >= 20) {
                if (playerFrames != pausedFrames || mirrorFrames <= pausedMirrors)
                    throw new IllegalStateException("Player pause affected another native subscriber");
                if (fixed.state() != CameraState.READY || fixedFrames != 30)
                    throw new IllegalStateException("Missing entity did not skip frames and retain the camera");
                fixed.follow(MinecraftCameraTargets.entity(() -> mc.player));
                fixed.setVerticalFov(55); free.setVerticalFov(60); player.resume(); phase = 2;
            }
            mc.player.setYRot(mc.player.getYRot() + .1f);
            for (var camera : new CameraHandle[]{player, free, fixed})
                if (camera.state() == CameraState.FAILED) throw new IllegalStateException("Camera failed", camera.failure().orElse(null));
            if (mirror.isClosed()) throw new IllegalStateException("Native mirror was detached");
            frames++;
        } catch (Exception failure) { finish(failure); }
    }

    private static void verifyPlayer(OutputFrame<FrameTexture> frame) {
        if (MinecraftWorldViews.currentView() != null || !frame.viewId().equals(MinecraftFrameOutputs.MAIN_VIEW))
            throw new IllegalStateException("Player output did not use the native final view");
        var pose = player.pose();
        if (pose.width() != frame.width() || pose.height() != frame.height()) throw new IllegalStateException("Native player size mismatch");
        if (mirrorFrame != null && mirrorFrame.frameNumber() == frame.frameNumber() && mirrorFrame != frame)
            throw new IllegalStateException("Player did not share native output after resume");
        playerFrame = frame; playerFrames++;
    }
    private static void verifyFree(OutputFrame<FrameTexture> frame) {
        var view = MinecraftWorldViews.currentView();
        if (!free.pose().equals(view) || view.verticalFov() != (phase >= 2 ? 60 : 70))
            throw new IllegalStateException("Free controls did not reach rendering");
        freeFrames++;
    }
    private static void verifyFixed(OutputFrame<FrameTexture> frame) {
        var mc = Minecraft.getInstance();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(true);
        var expected = fixed.followSettings().apply(fixed.followTarget().orElseThrow().sample(partial),
                new CameraProjection(phase >= 2 ? 55 : 65, 320, 180));
        var actual = MinecraftWorldViews.currentView();
        if (!expected.equals(actual)) throw new IllegalStateException("Interpolated entity pose did not reach rendering");
        var camera = MinecraftWorldViews.currentCamera();
        if (camera.getPosition().distanceToSqr(actual.x(), actual.y(), actual.z()) > 1e-10
                || Math.abs(camera.getYRot() - actual.yaw()) > 1e-5)
            throw new IllegalStateException("Entity follow did not reach native camera");
        if (!MinecraftWorldViews.currentSettings().equals(settingsChangedInCallback ? UPDATED : INITIAL))
            throw new IllegalStateException("Changed camera settings did not reach the new render session");
        fixedFrames++;
        if (phase == 2 && !settingsChangedInCallback) {
            fixed.updateRenderSettings(UPDATED);
            if (!MinecraftWorldViews.currentSettings().equals(INITIAL)) throw new IllegalStateException("Settings changed during an active frame");
            settingsChangedInCallback = true;
        }
        if (fixedFrames >= 90 && !worldDetail) {
            var colors = new HashSet<Integer>();
            byte[] rgba = RgbaReadback.copy(frame.resource());
            for (int i = 0; i < rgba.length; i += 4) colors.add((rgba[i] & 255) << 16 | (rgba[i + 1] & 255) << 8 | (rgba[i + 2] & 255));
            if (colors.size() < 32) throw new IllegalStateException("Follow camera produced no world detail");
            worldDetail = true;
        }
    }
    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || player == null) return;
        if (GL11.glGetError() != GL11.GL_NO_ERROR) { finish(new IllegalStateException("Camera types GL error")); return; }
        if (phase == 2 && worldDetail && freeFrames >= 100 && playerFrames >= 100) finish(null);
    }
    private static void finish(Exception failure) {
        if (finished) return;
        finished = true;
        var mc = Minecraft.getInstance();
        try {
            for (var camera : new CameraHandle[]{player, free, fixed}) if (camera != null) camera.close();
            if (mirror != null) mirror.close();
            var report = mc.gameDirectory.toPath().resolve("debug/camera-types.json");
            Files.createDirectories(report.getParent());
            Files.writeString(report, "{\"passed\":" + (failure == null) + ",\"playerFrames\":" + playerFrames
                    + ",\"freeFrames\":" + freeFrames + ",\"fixedFrames\":" + fixedFrames
                    + ",\"targetRecovered\":" + (phase == 2) + ",\"settingsRebuilt\":" + settingsChangedInCallback + "}");
            if (failure == null) LogUtils.getLogger().info("CAMERA_TYPES_SMOKE_PASS {}", report);
            else LogUtils.getLogger().error("CAMERA_TYPES_SMOKE_FAIL", failure);
        } catch (Exception cleanup) { LogUtils.getLogger().error("Camera types cleanup failed", cleanup); }
        mc.stop();
    }
}
