package lib.kasuga.rendering.output;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.dynamic.animation.MinecraftAnimationTimelines;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationPlayer;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationTimeline;
import lib.kasuga.rendering.models.uml.dynamic.animation.ClipSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import lib.kasuga.rendering.models.uml.dynamic.math.Easing;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSetInstance;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.rendering.output.camera.CameraAnimationClip;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;

/** Disposable-world acceptance: automatic ticking, interpolated pose/projection and real final-world output. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CameraAnimationSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testCameraAnimation");
    private static final long START = System.nanoTime();
    private static CameraHandle camera;
    private static AnimationPlayer<AnimationClip> model;
    private static AnimationTimeline timeline;
    private static MinecraftAnimationTimelines.Registration driver;
    private static WorldCameraView base;
    private static int warmup, frames, directFrames;
    private static double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
    private static float minFov = Float.POSITIVE_INFINITY, maxFov = Float.NEGATIVE_INFINITY;
    private static float minRoll = Float.POSITIVE_INFINITY, maxRoll = Float.NEGATIVE_INFINITY;
    private static boolean finished, direct;
    private static int modelSamples;
    private static double originX;

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        if (System.nanoTime() - START > 180_000_000_000L) { finish(new IllegalStateException("Camera animation timed out")); return; }
        if (mc.level == null || mc.player == null || mc.getOverlay() != null || ++warmup < 60) return;
        try {
            if (camera == null) {
                mc.options.pauseOnLostFocus = false; mc.setScreen(null);
                var eye = mc.player.getEyePosition();
                base = new WorldCameraView(eye.x, eye.y + 2, eye.z + 4, mc.player.getYRot(), 20, 0, 70, 320, 180);
                originX = base.x();
                var settings = new CameraRenderSettings(4, CameraRenderSettings.Quality.FANCY, CameraRenderSettings.Shader.disabled());
                camera = MinecraftCameras.create("test:animation", base, settings, CameraAnimationSmokeTest::verify);
                var cameraClip = CameraAnimationClip.builder(Id.parse("test:animation"), 2)
                        .move(0, base.x(), base.y(), base.z(), Easing.easeInOutSine())
                        .move(2, base.x() + 2, base.y() + 1, base.z(), null)
                        .rotate(0, base.yaw(), base.pitch(), 0, Easing.easeInOutCubic())
                        .rotate(2, base.yaw() + 20, base.pitch() + 5, 8, null)
                        .zoom(0, 1, Easing.easeInOutSine()).zoom(2, 1.5, null).build();
                var clip = new AnimationClip(cameraClip.id(), 2, List.of(new AnimationClip.BoneTrack("root", List.of(
                        new AnimationClip.Keyframe(0, new TransformDefinition(new Vector3f(), new Vector3f(), new Vector3f(1)), Easing.easeInOutSine()),
                        new AnimationClip.Keyframe(2, new TransformDefinition(new Vector3f(2, 0, 0), new Vector3f(), new Vector3f(1)), null)))),
                        List.of(), List.of(), List.of(), List.of(new AnimationClip.CameraTrack("shot", cameraClip.tracks())));
                timeline = new AnimationTimeline();
                model = new AnimationPlayer<>(testModel());
                model.model().setPoseDriver(model);
                model.follow(ClipSampler.INSTANCE, clip, timeline);
                camera.animation().follow(clip, "shot", timeline);
                timeline.play(2, true);
                driver = MinecraftAnimationTimelines.drive(timeline);
            }
            double time = timeline.currentTime();
            model.model().animate(1f / 20f); camera.animation().tick(1f / 20f);
            if (timeline.currentTime() != time) throw new IllegalStateException("Followers advanced the shared clock twice");
            if (camera.state() == CameraState.FAILED)
                throw new IllegalStateException("Camera animation failed", camera.failure().orElse(null));
            if (!direct && frames >= 180) {
                if (maxX - minX < .5 || maxFov - minFov < .5 || maxRoll - minRoll < .5)
                    throw new IllegalStateException("Camera tracks did not advance in the rendered world");
                camera.moveBy(.5, .25, 0); camera.rotateBy(5, 2, 1); camera.zoom(1.1);
                base = camera.pose();
                if (camera.animation().currentClip() != null) throw new IllegalStateException("Direct controls did not retire tracks");
                direct = true;
            }
        } catch (Exception failure) { finish(failure); }
    }

    private static void verify(OutputFrame<FrameTexture> frame) {
        var mc = Minecraft.getInstance();
        var actual = MinecraftWorldViews.currentView();
        var expected = direct ? base : camera.animation().sample(mc.getTimer().getGameTimeDeltaPartialTick(true));
        model.model().sample(mc.getTimer().getGameTimeDeltaPartialTick(true));
        double modelX = model.model().getSkeletonInstance().getTransforms().values().iterator().next().getPosition().x;
        if (!direct && Math.abs(modelX - (actual.x() - originX)) > 1e-5)
            throw new IllegalStateException("Model and world camera did not sample the same native clip time");
        modelSamples++;
        if (!expected.equals(actual)) throw new IllegalStateException("Rendered camera did not use the interpolated track pose");
        var projection = MinecraftWorldViews.projection(128);
        if (Math.abs(projection.m11() - 1 / Math.tan(Math.toRadians(actual.verticalFov()) / 2)) > 1e-5)
            throw new IllegalStateException("Animated zoom did not reach projection");
        var active = MinecraftWorldViews.currentCamera();
        if (active.getPosition().distanceToSqr(actual.x(), actual.y(), actual.z()) > 1e-10
                || Math.abs(active.getYRot() - actual.yaw()) > 1e-5 || Math.abs(active.getXRot() - actual.pitch()) > 1e-5)
            throw new IllegalStateException("Animated pose did not reach world camera");
        minX = Math.min(minX, actual.x()); maxX = Math.max(maxX, actual.x());
        minFov = Math.min(minFov, actual.verticalFov()); maxFov = Math.max(maxFov, actual.verticalFov());
        minRoll = Math.min(minRoll, actual.roll()); maxRoll = Math.max(maxRoll, actual.roll());
        if (++frames == 120) {
            var colors = new HashSet<Integer>();
            byte[] rgba = RgbaReadback.copy(frame.resource());
            for (int i = 0; i < rgba.length; i += 4)
                colors.add((rgba[i] & 255) << 16 | (rgba[i + 1] & 255) << 8 | (rgba[i + 2] & 255));
            if (colors.size() < 32) throw new IllegalStateException("Animated camera produced no world detail");
        }
        if (direct) directFrames++;
    }

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || camera == null) return;
        if (GL11.glGetError() != GL11.GL_NO_ERROR) { finish(new IllegalStateException("Camera animation GL error")); return; }
        if (directFrames >= 30) finish(null);
    }

    private static void finish(Exception failure) {
        if (finished) return; finished = true;
        var mc = Minecraft.getInstance();
        try {
            if (camera != null) camera.close();
            if (driver != null) driver.close();
            if (model != null) model.stop();
            if (timeline != null) timeline.stop();
            var report = mc.gameDirectory.toPath().resolve("debug/camera-animation.json");
            Files.createDirectories(report.getParent());
            Files.writeString(report, "{\"passed\":" + (failure == null) + ",\"frames\":" + frames
                    + ",\"directFrames\":" + directFrames + ",\"xRange\":" + (maxX - minX)
                    + ",\"fovRange\":" + (maxFov - minFov) + ",\"rollRange\":" + (maxRoll - minRoll)
                    + ",\"sharedTimeline\":true,\"modelSamples\":" + modelSamples + "}");
            if (failure == null) LogUtils.getLogger().info("CAMERA_ANIMATION_SMOKE_PASS {}", report);
            else LogUtils.getLogger().error("CAMERA_ANIMATION_SMOKE_FAIL", failure);
        } catch (Exception cleanup) { LogUtils.getLogger().error("Camera animation cleanup failed", cleanup); }
        mc.stop();
    }

    /** Real model sink with no geometry; world-camera rendering still uses the normal full scene. */
    private static ModelInstance testModel() {
        var root = new Bone("root", new Transform(), null);
        var skeleton = new Skeleton(new Bone[]{root}, root, new Anchor[0], null, new Transform());
        var texture = new Texture("test", 1, 1, null);
        var material = new Material(new Texture[]{texture}, null);
        var materials = new MaterialSet(texture, material);
        var asset = new Model(new Vertex[0], new Mesh[0], new Bone[]{root}, skeleton, materials, MeshMode.TRIANGLES, null, null);
        return new ModelInstance(asset, null, null, null, new MaterialSetInstance(materials), null);
    }
}
