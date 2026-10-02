package lib.kasuga.rendering.output;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Disposable-world regression for client-predicted player motion and limb phase. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CameraMotionSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testCameraMotion");
    private static final long START = System.nanoTime();
    private static CameraHandle camera;
    private static Vec3 origin;
    private static float yaw;
    private static int warmup, checked;
    private static boolean finished;
    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        if (System.nanoTime() - START > 90_000_000_000L) { finish(new IllegalStateException("Motion test timed out")); return; }
        if (mc.player == null || mc.level == null || mc.getOverlay() != null || ++warmup < 60) return;
        try {
            if (camera == null) {
                mc.options.pauseOnLostFocus = false; mc.setScreen(null);
                origin = mc.player.position(); yaw = mc.player.getYRot();
                camera = MinecraftCameras.create("test:motion", () -> {
                    var eye = mc.player.getEyePosition();
                    return new WorldCameraView(eye.x, eye.y + 2, eye.z + 4, 180, 15, 0, 70, 320, 180);
                }, new CameraRenderSettings(4, CameraRenderSettings.Quality.FANCY, CameraRenderSettings.Shader.disabled()),
                        frame -> verify());
            }
            if (camera.state() == CameraState.FAILED) throw new IllegalStateException("Motion camera failed", camera.failure().orElse(null));
            if (checked > 0) {
                var player = mc.player;
                player.xo = player.getX(); player.yo = player.getY(); player.zo = player.getZ();
                player.yRotO = player.getYRot();
                player.setPos(origin.x + Math.sin(checked * .1) * .8, origin.y, origin.z);
                player.setYRot(yaw + (float) Math.sin(checked * .05) * 30);
                player.walkAnimation.update(.3f, 1);
            }
        } catch (Exception failure) { finish(failure); }
    }
    private static void verify() {
        var mc = Minecraft.getInstance();
        var source = mc.player;
        var replica = mc.level.getEntity(source.getId());
        if (replica == null) return; // Subscription spawn may arrive after initial frames.
        try {
            if (replica == source || !replica.getUUID().equals(source.getUUID()))
                throw new IllegalStateException("Player replica is not independently owned");
            lib.kasuga.rendering.output.mc.stream.CameraEntityVisualsChecks.verifyStableBounds(
                    mc.level, (net.minecraft.client.multiplayer.ClientLevel) source.level(), replica);
            for (float partial : new float[]{0, .25f, .75f, 1}) {
                if (!replica.getPosition(partial).equals(source.getPosition(partial)))
                    throw new IllegalStateException("Player position lagged at " + partial + ": "
                            + replica.getPosition(partial) + " vs " + source.getPosition(partial));
                if (replica.yRotO != source.yRotO || replica.getYRot() != source.getYRot())
                    throw new IllegalStateException("Player rotation history lagged");
                var target = (LivingEntity) replica;
                if (target.walkAnimation.position(partial) != source.walkAnimation.position(partial)
                        || target.walkAnimation.speed(partial) != source.walkAnimation.speed(partial))
                    throw new IllegalStateException("Player limb phase lagged");
            }
            checked++;
        } catch (Exception failure) { finish(failure); }
    }
    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (ENABLED && !finished && checked >= 120) finish(null);
    }
    private static void finish(Exception failure) {
        if (finished) return; finished = true;
        var mc = Minecraft.getInstance();
        try {
            if (camera != null) camera.close();
            if (origin != null && mc.player != null) { mc.player.setPos(origin); mc.player.setYRot(yaw); }
            var report = mc.gameDirectory.toPath().resolve("debug/camera-motion.json");
            java.nio.file.Files.createDirectories(report.getParent());
            java.nio.file.Files.writeString(report, "{\"passed\":" + (failure == null) + ",\"checkedFrames\":" + checked + "}");
            if (failure == null) LogUtils.getLogger().info("CAMERA_MOTION_SMOKE_PASS {}", report);
            else LogUtils.getLogger().error("CAMERA_MOTION_SMOKE_FAIL", failure);
        } catch (Exception cleanup) { LogUtils.getLogger().error("Motion test cleanup failed", cleanup); }
        mc.stop();
    }
}
