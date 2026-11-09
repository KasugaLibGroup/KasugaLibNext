package test.kasuga.modelling;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.dynamic.MinecraftModelRuntime;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollDeployments;
import lib.kasuga.rendering.models.mc.dynamic.physics.RagdollDeployment;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.ModelPhysicsScene;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in real PMX/glTF render and standard skeleton-state smoke; never runs in normal clients. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class SkeletonRelaxationSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.smokeSkeletonRelaxation");
    private static final long START = System.nanoTime();
    private static final List<RagdollDeployment> MODELS = new ArrayList<>();
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static final int[] EVALUATIONS = new int[2];
    private static final int[] SAMPLES = new int[2];
    private static ModelPhysicsScene scene;
    private static Vector3d origin;
    private static int warmup, frame;
    private static boolean started, finished;
    private static int offscreenStart;

    private SkeletonRelaxationSmokeTest() {}

    @SubscribeEvent
    public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished) return;
        try {
            var mc = Minecraft.getInstance();
            check(System.nanoTime() - START < 240_000_000_000L, "client smoke timeout");
            if (mc.level == null || mc.player == null || mc.getOverlay() != null || ++warmup < 60) return;
            if (!started) {
                mc.setScreen(null);
                mc.player.setYRot(0f);
                mc.player.setXRot(12f);
                origin = new Vector3d(mc.player.getX(), mc.player.getY(), mc.player.getZ() + 5);
                deploy("models/pmx/test3.mmd.zip", "tda bunny miku 2.0.pmx", "ragdolls/tda_bunny_miku.json", -1.5);
                deploy("models/gltf/maribel.glb", "", null, 1.5);
                REPORT.put("models", MODELS.stream().map(m -> m.modelResource().toString()).toList());
                REPORT.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
                REPORT.put("vendor", GL11.glGetString(GL11.GL_VENDOR));
                REPORT.put("origin", List.of(origin.x, origin.y, origin.z));
                started = true;
                return;
            }
            frame++;
            for (var model : MODELS) {
                for (var transform : model.instance().getSkeletonInstance().getAbsoluteTransforms().values()) {
                    check(transform.transform().isFinite(), "non-finite rendered bone matrix");
                }
            }
            if (frame == 25) capture("relaxed");
            if (frame == 30) {
                for (var m : MODELS) {
                    m.instance().getSkeletonInstance().resumeAnimation();
                    check(m.instance().getSkeletonInstance().getMotionState() == SkeletonInstance.MotionState.ANIMATED,
                            "resume did not select animation");
                }
            }
            if (frame == 50) capture("animated");
            if (frame == 60) {
                for (var m : MODELS) {
                    m.instance().getSkeletonInstance().drivePose(new SkeletonInstance.PoseDrive(2f, 1f, 200f, 50f, 0.7f));
                    check(m.instance().getSkeletonInstance().getMotionState() == SkeletonInstance.MotionState.DRIVEN,
                            "pose drive state missing");
                }
            }
            if (frame == 85) capture("driven");
            if (frame == 90) {
                scene = new ModelPhysicsScene(origin, 4);
                scene.world().addGroundPlane(-0.5f, 0.8f, 0f);
                for (var m : MODELS) {
                    m.instance().getSkeletonInstance().relax();
                    m.ragdoll().moveTo(scene);
                    MinecraftModelRuntime.unregister(m.instance());
                }
                MinecraftModelRuntime.register(scene);
                check(scene.ragdolls().size() == 2, "shared migration lost a participant");
            }
            if (frame == 105) {
                offscreenStart = EVALUATIONS[0];
                mc.player.setYRot(180f);
            }
            if (frame == 125) {
                check(EVALUATIONS[0] - offscreenStart == 20, "culled model did not evaluate exactly once each frame");
                mc.player.setYRot(0f);
            }
            if (frame == 145) {
                capture("shared");
                for (int i = 0; i < 2; i++) {
                    check(EVALUATIONS[i] == frame, "pipeline repeated or skipped: " + EVALUATIONS[i] + " / " + frame);
                    check(SAMPLES[i] == frame, "post-IK target was not published once per frame");
                }
                REPORT.put("evaluations", EVALUATIONS);
                REPORT.put("postIkTargets", SAMPLES);
                REPORT.put("offscreenFrames", 20);
                finish(null);
            }
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private static void deploy(String path, String name, String config, double offset) throws Exception {
        int index = MODELS.size();
        var request = new MinecraftRagdollDeployments.Request(id(path), name, id("smoke/relaxation/" + index),
                config == null ? null : id(config), new Transform(), false, new Vector3d(origin).add(offset, 0, 0));
        var deployment = MinecraftRagdollDeployments.deploy(request).orElseThrow(() ->
                new IllegalStateException("smoke asset unavailable: " + path));
        MODELS.add(deployment);
        deployment.ragdoll().setCollisionEnvironment(null);
        deployment.ragdoll().addGroundPlane(-0.5f, 0.8f, 0f);
        var loop = deployment.instance().getTickLoop();
        loop.addPostIk("smoke-target", new ModelTickLoopModule() {
            @Override public void tick(Model model, PendingTransform[] transforms, ModelTickLoop pipeline, float dt) {
                check(pipeline.getInstance().getSkeletonInstance().hasCurrentPoseTarget(), "post-IK target missing");
                SAMPLES[index]++;
            }
            @Override public void destroy(Model model) {}
        });
        loop.addPostPhysics("smoke-count", new ModelTickLoopModule() {
            @Override public void tick(Model model, PendingTransform[] transforms, ModelTickLoop pipeline, float dt) {
                EVALUATIONS[index]++;
            }
            @Override public void destroy(Model model) {}
        });
    }

    private static void capture(String name) throws Exception {
        var target = Minecraft.getInstance().getMainRenderTarget();
        byte[] rgba = RgbaReadback.copy(new FrameTexture(target.frameBufferId,
                target.getColorTextureId(), target.width, target.height));
        var image = new BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < target.height; y++) for (int x = 0; x < target.width; x++) {
            int p = (y * target.width + x) * 4;
            image.setRGB(x, y, (rgba[p + 3] & 255) << 24 | (rgba[p] & 255) << 16
                    | (rgba[p + 1] & 255) << 8 | rgba[p + 2] & 255);
        }
        Files.createDirectories(output());
        ImageIO.write(image, "PNG", output().resolve(name + ".png").toFile());
    }

    private static Path output() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("debug/skeleton-relaxation");
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, path); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private static void finish(Throwable failure) {
        if (finished) return;
        finished = true;
        var mc = Minecraft.getInstance();
        try {
            if (scene != null) MinecraftModelRuntime.unregister(scene);
            for (var model : MODELS) model.close();
            if (scene != null) scene.close();
            REPORT.put("passed", failure == null);
            REPORT.put("frames", frame);
            REPORT.put("failure", failure == null ? "" : failure.toString());
            Files.createDirectories(output());
            Files.writeString(output().resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure == null) LogUtils.getLogger().info("SKELETON_RELAXATION_SMOKE_PASS {}", output());
            else LogUtils.getLogger().error("SKELETON_RELAXATION_SMOKE_FAIL", failure);
        } catch (Throwable cleanup) {
            LogUtils.getLogger().error("Skeleton relaxation smoke cleanup failed", cleanup);
        }
        mc.stop();
    }
}
