package lib.kasuga.rendering.lighting;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.builtin.light.WorldLights;
import lib.kasuga.scene.SceneModels;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayScene;
import lib.kasuga.rendering.models.mc.scene.display.WorldLightTarget;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import lib.kasuga.rendering.models.mc.api.McModelHandle;
import lib.kasuga.rendering.models.mc.dynamic.ModelLightRuntime;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.OutputFrame;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Disposable-world demo and two-resolution GPU acceptance; opt in with -PkasugaTestWorldLights=true. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class WorldLightDemo {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testWorldLights");
    private static final String[] SCENES = {"baseline", "point-warm", "point-cool", "point-strong", "area", "volume", "combined",
            "point-moved", "scene-moved", "hidden", "restored", "removed", "blockbench-light", "blockbench-bone-moved", "blockbench-closed"};
    private static final long START = System.nanoTime();
    private static final Map<String, byte[]> BASELINES = new LinkedHashMap<>();
    private static final Map<String, byte[]> WARM_FRAMES = new LinkedHashMap<>();
    private static final Map<String, byte[]> MODEL_FRAMES = new LinkedHashMap<>();
    private static final Map<String, Double> DIFFERENCES = new LinkedHashMap<>();
    private static CameraHandle camera;
    private static CameraHandle smallCamera;
    private static WorldLightTarget point;
    private static WorldLightTarget panel;
    private static WorldLightTarget volume;
    private static SceneModels models;
    private static WorldDisplayScene lights;
    private static McModelHandle importedModel;
    private static ModelLightRuntime.LightModule importedModule;
    private static CompletableFuture<Void> room;
    private static int autoTicks;
    private static int frames;
    private static int captured;
    private static int x;
    private static int y;
    private static int z;
    private static int scene;
    private static boolean requested;
    private static boolean finished;

    private WorldLightDemo() {}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!ENABLED || finished) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null || minecraft.getOverlay() != null || requested || ++autoTicks < 100) return;
        requested = true;
        String world = "world-light-demo-" + Long.toString(System.currentTimeMillis(), 36);
        LevelSettings settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL,
                true, new GameRules(), WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(world, settings, WorldOptions.defaultWithRandomSeed(),
                WorldPresets::createNormalWorldDimensions, minecraft.screen);
    }

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        try {
            check(System.nanoTime() - START < 240_000_000_000L, "World light demo timed out");
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null || minecraft.player == null || minecraft.getOverlay() != null || !WorldLights.ready()) return;
            minecraft.options.pauseOnLostFocus = false;
            minecraft.options.hideGui = true;
            minecraft.setScreen(null);
            minecraft.level.setDayTime(18000);
            minecraft.level.setRainLevel(0);
            if (room == null) {
                x = minecraft.player.getBlockX();
                y = minecraft.player.getBlockY() + 4;
                z = minecraft.player.getBlockZ();
                MinecraftServer server = minecraft.getSingleplayerServer();
                check(server != null, "Demo requires its disposable integrated server");
                room = CompletableFuture.runAsync(() -> buildRoom(server), server);
                return;
            }
            if (!room.isDone()) return;
            room.join();
            if (camera == null) {
                WorldCameraView pose = new WorldCameraView(x + .5, y + 4.5, z + 9, 180, 8, 0, 60, 960, 600);
                CameraRenderSettings settings = new CameraRenderSettings(4, CameraRenderSettings.Quality.FANCY,
                        CameraRenderSettings.Shader.disabled());
                camera = MinecraftCameras.create("test:world-lights", pose, settings, WorldLightDemo::capture);
                smallCamera = MinecraftCameras.create("test:world-lights-small",
                        new WorldCameraView(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch(), 0, 60, 480, 300),
                        settings, WorldLightDemo::capture);
                lights = new WorldDisplayScene();
                point = lights.add(new WorldLightTarget(LightSource.point(-3, 4, -3)
                        .range(15).temperature(2700).intensity(0).build()));
                panel = lights.add(new WorldLightTarget(LightSource.area(1, 6, -3, 5, 3)
                        .rotation(0, 90).range(14).temperature(4500).intensity(0).build()));
                volume = lights.add(new WorldLightTarget(LightSource.volumetric(2, 3, -4)
                        .range(4).temperature(9000).medium(.12f, .2f).intensity(0).build()));
                WorldDisplayScene root = new WorldDisplayScene();
                root.add(lights);
                models = SceneModels.open(minecraft.level);
                models.add(root, new Vec3(x, y, z));
            }
            check(camera.state() != CameraState.FAILED && smallCamera.state() != CameraState.FAILED, "Demo camera failed");
            frames++;
            if (frames == 100) setScene(1);
            else if (frames > 100 && (frames - 100) % 40 == 0 && scene < SCENES.length - 1) setScene(scene + 1);
            if (importedModel != null && scene < 14) {
                importedModel.mount();
                if (importedModel.instance() != null) {
                    importedModule = ModelLightRuntime.module(importedModel.instance());
                    check(importedModule != null, "Blockbench model did not install light module");
                    importedModel.instance().getSkeletonInstance().transform("lamp_mount",
                            new Transform().rotate(0, scene == 13 ? 180 : 0, 0, true));
                }
            }
            if (frames == 655) {
                check(captured == SCENES.length * 2, "Missing demo outputs: " + captured);
                check(WorldLights.visibleCount() == 0, "Removed light remained visible");
                check(WorldLights.activeCount() == 0, "Scene close left owned lights active");
                check(importedModule != null && importedModule.closed(), "Blockbench light module did not close with model");
                finish(null);
            }
        } catch (Exception failure) { finish(failure); }
    }

    private static void setScene(int index) {
        scene = index;
        if (index <= 11) point.source(point.source().toBuilder().temperature(index == 2 ? 12000 : 2700)
                .intensity(index == 1 || index == 2 || index >= 6 && index <= 10 ? 36 : index == 3 ? 90 : 0).build());
        if (index <= 11) panel.source(panel.source().toBuilder().intensity(index == 4 || index == 6 ? 10 : 0).build());
        if (index <= 11) volume.source(volume.source().toBuilder().intensity(index == 5 || index == 6 ? 180 : 0).build());
        if (index == 7) point.moveBy(6, 0, 0);
        if (index == 8) lights.transform(new Matrix4f().translation(-4, 0, 0));
        if (index == 9) lights.visible(false);
        if (index == 10) {
            lights.visible(true);
            lights.transform(new Matrix4f());
            point.position(new Vec3(-3, 4, -3));
        }
        if (index == 11) models.close();
        if (index == 12) importedModel = McModelHandle.of(ResourceLocation.parse("kasuga_lib:models/components/light_locator.bbmodel"),
                null, ResourceLocation.parse("kasuga_lib:smoke/blockbench_light"), new Vec3(x, y + 3, z - 4));
        if (index == 14) importedModel.destroy();
    }

    private static void buildRoom(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.setDayTime(18000);
        level.setWeatherParameters(6000, 0, false, false);
        server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -11; dz <= 10; dz++) {
                for (int dy = 0; dy <= 9; dy++) {
                    BlockState block = Blocks.AIR.defaultBlockState();
                    if (dy == 0) block = ((Math.floorDiv(dx, 2) + Math.floorDiv(dz, 2)) & 1) == 0
                            ? Blocks.SMOOTH_QUARTZ.defaultBlockState() : Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
                    else if (dz == -11 || Math.abs(dx) == 8) block = Blocks.SMOOTH_QUARTZ.defaultBlockState();
                    level.setBlock(new BlockPos(x + dx, y + dy, z + dz), block, 3);
                }
            }
        }
        for (int dx : new int[]{-4, 0, 4}) {
            for (int height = 1; height <= (dx == 0 ? 4 : 2); height++) {
                for (int dz = -7; dz <= -5; dz++) {
                    for (int width = 0; width < 2; width++) {
                        level.setBlock(new BlockPos(x + dx + width, y + height, z + dz), Blocks.QUARTZ_BRICKS.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private static void capture(OutputFrame<FrameTexture> output) {
        if (finished || (frames != 90 && (frames < 130 || (frames - 130) % 40 != 0))) return;
        try {
            byte[] pixels = RgbaReadback.copy(output.resource());
            String key = output.viewId() + "/" + SCENES[scene];
            if (DIFFERENCES.containsKey(key)) return;
            if (scene == 0) BASELINES.put(output.viewId(), pixels);
            byte[] baseline = BASELINES.get(output.viewId());
            check(baseline != null && baseline.length == pixels.length, "Missing baseline for " + key);
            double difference = 0;
            for (int index = 0; index < pixels.length; index++) {
                if (index % 4 != 3) difference += Math.abs((pixels[index] & 255) - (baseline[index] & 255));
            }
            difference /= output.width() * output.height() * 3.0 * 255;
            if (scene > 0 && scene != 9 && scene != 11 && scene != 14) {
                check(WorldLights.visibleCount() > 0, "Visible light was filtered from " + key);
                check(difference > .01, "Light did not reach final world output: " + key + " difference=" + difference);
            }
            if (scene == 1) WARM_FRAMES.put(output.viewId(), pixels);
            if (scene == 12) MODEL_FRAMES.put(output.viewId(), pixels);
            if (scene == 13) {
                byte[] modelFrame = MODEL_FRAMES.get(output.viewId());
                check(modelFrame != null, "Missing imported model frame");
                double changed = 0;
                for (int index = 0; index < pixels.length; index++) changed += Math.abs((pixels[index] & 255) - (modelFrame[index] & 255));
                check(changed / pixels.length / 255 > .003, "Bone animation did not move imported light");
            }
            if (scene == 2 || scene == 3 || scene == 7 || scene == 8) {
                byte[] warm = WARM_FRAMES.get(output.viewId());
                double changed = 0;
                for (int index = 0; index < pixels.length; index++) changed += Math.abs((pixels[index] & 255) - (warm[index] & 255));
                check(changed / pixels.length / 255 > .003, "Temperature/intensity/movement did not change pixels: " + key);
            }
            if (scene == 9 || scene == 11 || scene == 14) check(difference < .002, "Hide/close failed to restore scene: " + key + " difference=" + difference);
            if (scene == 10) {
                byte[] warm = WARM_FRAMES.get(output.viewId());
                double changed = 0;
                for (int index = 0; index < pixels.length; index++) changed += Math.abs((pixels[index] & 255) - (warm[index] & 255));
                check(changed / pixels.length / 255 < .002, "Scene restore did not restore the original placement: " + key);
            }
            DIFFERENCES.put(key, difference);
            BufferedImage image = new BufferedImage(output.width(), output.height(), BufferedImage.TYPE_INT_ARGB);
            for (int py = 0; py < output.height(); py++) {
                for (int px = 0; px < output.width(); px++) {
                    int index = (py * output.width() + px) * 4;
                    image.setRGB(px, py, 0xff000000 | (pixels[index] & 255) << 16
                            | (pixels[index + 1] & 255) << 8 | pixels[index + 2] & 255);
                }
            }
            Files.createDirectories(output());
            ImageIO.write(image, "png", output().resolve(SCENES[scene]
                    + (output.width() < 900 ? "-small" : "") + ".png").toFile());
            captured++;
        } catch (Exception failure) { finish(failure); }
    }

    private static Path output() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("debug/world-lights");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void finish(Exception failure) {
        if (finished) return;
        finished = true;
        try {
            if (camera != null) camera.close();
            if (smallCamera != null) smallCamera.close();
            if (models != null) models.close();
            if (importedModel != null) importedModel.destroy();
            WorldLights.clear();
            Files.createDirectories(output());
            Files.writeString(output().resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(
                    Map.of("passed", failure == null, "failure", failure == null ? "" : failure.toString(),
                            "captured", captured, "differences", DIFFERENCES, "renderer", GL11.glGetString(GL11.GL_RENDERER))));
        } catch (Exception cleanup) { LogUtils.getLogger().error("World light demo cleanup failed", cleanup); }
        if (failure == null) LogUtils.getLogger().info("WORLD_LIGHT_SMOKE_PASS {}", output());
        else LogUtils.getLogger().error("WORLD_LIGHT_SMOKE_FAIL", failure);
        Minecraft.getInstance().stop();
    }
}
