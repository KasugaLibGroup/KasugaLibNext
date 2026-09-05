package test.kasuga.modelling;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollConfig;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Quaternionf;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Locale;

/** Client-only smoke models included exclusively in the contentTesting source set. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ModelSmokeTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation TEST_MMD_PHYSICS = ResourceLocation.fromNamespaceAndPath(
            KasugaLib.MODID, "ragdolls/tda_bunny_miku.json");
    private static boolean missingMmdLogged;
    private static boolean missingBbmodelLogged;
    private static boolean missingFmtLogged;
    private static boolean modeLogged;
    private static ModelInstance currentMmdInstance;

    private ModelSmokeTests() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()) return;
        testModel();
    }

    private static void testModel() {
        if (!Boolean.parseBoolean(System.getProperty("kasuga.renderTestModels", "true"))) return;
        String selectedModel = System.getProperty("kasuga.testModel",
                System.getProperty("kasugaTestModel", "bbmodel"));
        if (!modeLogged) {
            modeLogged = true;
            LOGGER.info("Model smoke test mode: {} (set -PkasugaTestModel=<bbmodel|mtb|bob|fmf|obj|be|je|mmd>)", selectedModel);
        }
        switch (selectedModel.toLowerCase(Locale.ROOT)) {
            case "bbmodel" -> testBbModels();
            case "mtb", "fmtb" -> testFmtModels();
            case "bob" -> testFmtBinaryModels("bob");
            case "beo" -> testFmtBinaryModels("beo");
            case "fmf" -> testFmtBinaryModels("fmf");
            case "obj" -> testObj();
            case "be" -> testBe();
            case "je" -> testJe();
            case "ling", "ling_singer" -> testLingSinger();
            default -> testMmd();
        }
    }

    private static void testBbModels() {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 playerPosition = minecraft.player.position();
        Vec3 lookDirection = minecraft.player.getLookAngle();
        String selected = System.getProperty("kasuga.testBbmodel");
        if (selected != null && !selected.isBlank()) {
            Vec3 position = playerPosition.add(lookDirection.scale(3.0)).add(0.0, 1.0, 0.0);
            testBbmodel(normalizeBbmodelPath(selected), "test_bbmodel", position);
            return;
        }

        Vec3 headPosition = playerPosition.add(lookDirection.scale(3.0)).add(0.0, 1.0, 0.0);
        Vec3 bogeyPosition = playerPosition.add(lookDirection.scale(8.0)).add(0.0, 1.0, 0.0);
        testBbmodel("models/block/test/blockbench/df11g_head.bbmodel", "test_bbmodel_head", headPosition);
        testBbmodel("models/block/test/blockbench/qj_bogey_main.bbmodel", "test_bbmodel_bogey", bogeyPosition);
    }

    private static String normalizeBbmodelPath(String path) {
        String trimmed = path.trim().replace('\\', '/');
        if (trimmed.contains(":")) return trimmed;
        return trimmed.startsWith("models/") ? trimmed : "models/block/test/blockbench/" + trimmed;
    }

    private static void testBbmodel(String modelPath, String instanceName, Vec3 position) {
        ResourceLocation modelLoc = ResourceLocation.tryBuild(KasugaLib.MODID, modelPath);
        ResourceLocation instanceLoc = ResourceLocation.tryBuild(KasugaLib.MODID, instanceName);
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline = PipelineRegistry.bbmodel();
        if (!pipeline.hasModel(modelLoc)) {
            if (!missingBbmodelLogged) {
                missingBbmodelLogged = true;
                LOGGER.warn("Test bbmodel '{}' is unavailable after resource reload; check contentTesting assets/{}/models/model_proxy.json and restart the client",
                        modelLoc, KasugaLib.MODID);
            }
            return;
        }
        missingBbmodelLogged = false;
        if (pipeline.hasInstance(modelLoc, instanceLoc)) return;
        ModelInstance instance = pipeline.createInstance(modelLoc, instanceLoc, new Transform().translate(
                (float) position.x, (float) position.y, (float) position.z), null, null);
        if (instance != null) pipeline.addToRenderer(modelLoc, instanceLoc, "mc_bridge", "mc_backend");
    }

    private static void testFmtModels() {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 playerPosition = minecraft.player.position();
        Vec3 lookDirection = minecraft.player.getLookAngle();
        String selected = System.getProperty("kasuga.testFmtModel",
                System.getProperty("kasugaTestFmtModel"));
        if (selected != null && !selected.isBlank()) {
            testFmtModel(normalizeFmtPath(selected), "test_fmt_model", playerPosition.add(lookDirection.scale(3.0)).add(0.0, 1.0, 0.0));
            return;
        }
        // These locomotives are roughly 8 blocks long. Keep enough depth
        // between instances so the smoke test does not make valid geometry
        // look like a single intersecting/duplicated train.
        testFmtModel("models/fmt/traincraft/0-8-0_box_tank.mtb", "test_fmt_080", playerPosition.add(lookDirection.scale(6.0)).add(0.0, 1.0, 0.0));
        testFmtModel("models/fmt/traincraft/060_pannier.mtb", "test_fmt_pannier", playerPosition.add(lookDirection.scale(20.0)).add(0.0, 1.0, 0.0));
        testFmtModel("models/fmt/traincraft/emd_f3_a.mtb", "test_fmt_emd_f3", playerPosition.add(lookDirection.scale(34.0)).add(0.0, 1.0, 0.0));
        testFmtBinaryModel("models/fmt/traincraft/1.bob", "test_fmt_bob", playerPosition.add(lookDirection.scale(48.0)).add(0.0, 1.0, 0.0));
        testFmtBinaryModel("models/fmt/traincraft/1.fmf", "test_fmt_fmf", playerPosition.add(lookDirection.scale(54.0)).add(0.0, 1.0, 0.0));
    }

    private static String normalizeFmtPath(String path) {
        String trimmed = path.trim().replace('\\', '/');
        String normalized = trimmed.startsWith("models/") ? trimmed : "models/fmt/traincraft/" + trimmed;
        // ResourceLocation paths are strictly lowercase in Minecraft 1.21;
        // Traincraft's original filenames contain uppercase characters.
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static void testFmtModel(String modelPath, String instanceName, Vec3 position) {
        ResourceLocation modelLoc = ResourceLocation.tryBuild(KasugaLib.MODID, modelPath);
        ResourceLocation instanceLoc = ResourceLocation.tryBuild(KasugaLib.MODID, instanceName);
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline = PipelineRegistry.fmtArchive();
        if (!pipeline.hasModel(modelLoc)) {
            if (!missingFmtLogged) {
                missingFmtLogged = true;
                LOGGER.warn("Test FMT model '{}' is unavailable after resource reload; check contentTesting model_proxy.json and restart the client", modelLoc);
            }
            return;
        }
        missingFmtLogged = false;
        if (pipeline.hasInstance(modelLoc, instanceLoc)) return;
        ModelInstance instance = pipeline.createInstance(modelLoc, instanceLoc, new Transform().translate(
                (float) position.x, (float) position.y, (float) position.z), null, null);
        if (instance != null) pipeline.addToRenderer(modelLoc, instanceLoc, "mc_bridge", "mc_backend");
    }

    private static void testFmtBinaryModels(String extension) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 position = minecraft.player.position().add(minecraft.player.getLookAngle().scale(6.0)).add(0.0, 1.0, 0.0);
        testFmtBinaryModel("models/fmt/traincraft/1." + extension, "test_fmt_" + extension, position);
    }

    private static void testFmtBinaryModel(String modelPath, String instanceName, Vec3 position) {
        ResourceLocation modelLoc = ResourceLocation.tryBuild(KasugaLib.MODID, modelPath);
        ResourceLocation instanceLoc = ResourceLocation.tryBuild(KasugaLib.MODID, instanceName);
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline = PipelineRegistry.fmtBinary();
        if (!pipeline.hasModel(modelLoc)) {
            if (!missingFmtLogged) {
                missingFmtLogged = true;
                LOGGER.warn("Test FMT binary model '{}' is unavailable after resource reload; check contentTesting model_proxy.json and restart the client", modelLoc);
            }
            return;
        }
        missingFmtLogged = false;
        if (pipeline.hasInstance(modelLoc, instanceLoc)) return;
        ModelInstance instance = pipeline.createInstance(modelLoc, instanceLoc, new Transform().translate(
                (float) position.x, (float) position.y, (float) position.z), null, null);
        if (instance != null) pipeline.addToRenderer(modelLoc, instanceLoc, "mc_bridge", "mc_backend");
    }

    private static void testMmd() {
        testMmdModel("test3.mmd.zip", "tda bunny miku 2.0.pmx", "test_mmd", new Transform(), true);
        testLingSinger();
    }

    private static void testLingSinger() {
        testMmdModel("ling_singer.mmd.zip", "ling_singer.pmx", "test_mmd_ling_singer",
                new Transform().translate(2.5f, 0.0f, 0.0f), false);
    }

    private static void testMmdModel(String fileName, String modelName, String instanceName,
                                     Transform rootTransform, boolean enablePhysics) {
        ResourceLocation modelLoc = PipelineRegistry.pmxLoader().getLocByFileAndName(
                ResourceLocation.tryBuild(KasugaLib.MODID, "models/pmx/" + fileName), modelName);
        if (modelLoc == null) {
            if (!missingMmdLogged) {
                missingMmdLogged = true;
                LOGGER.warn("Test MMD model '{}' is unavailable after resource reload; check contentTesting models/model_proxy.json",
                        modelName);
            }
            return;
        }
        missingMmdLogged = false;
        ResourceLocation instanceLoc = ResourceLocation.tryBuild(KasugaLib.MODID, instanceName);
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline = PipelineRegistry.pmx();
        if (pipeline.hasInstance(modelLoc, instanceLoc)) {
            if (enablePhysics) attachMmdPhysics(pipeline.getInstance(modelLoc, instanceLoc));
            return;
        }
        ModelInstance instance = pipeline.createInstance(modelLoc, instanceLoc, rootTransform, null, null);
        if (instance == null) return;
        if ("ling_singer.pmx".equals(modelName)) applyLingSingerTpose(instance);
        if (enablePhysics) attachMmdPhysics(instance);
        pipeline.addToRenderer(modelLoc, instanceLoc, "mc_bridge", "mc_backend");
    }

    /** The supplied Ling Singer PMX is authored in a relaxed pose; keep this smoke instance static and T-shaped. */
    private static void applyLingSingerTpose(ModelInstance instance) {
        float shoulderAngle = (float) Math.toRadians(38.65f);
        var skeleton = instance.getSkeletonInstance();
        boolean right = skeleton.rotate("右肩P", new Quaternionf().rotateZ(-shoulderAngle));
        boolean left = skeleton.rotate("左肩P", new Quaternionf().rotateZ(shoulderAngle));
        instance.updateImmediate();
        LOGGER.info("Applied static Ling Singer T-pose (right shoulder={}, left shoulder={})", right, left);
    }

    private static void attachMmdPhysics(ModelInstance instance) {
        if (instance == null || currentMmdInstance == instance) return;
        currentMmdInstance = instance;
        if (!Boolean.parseBoolean(System.getProperty("kasuga.testModelPhysics", "true"))) return;
        try {
            MinecraftRagdollConfig config = MinecraftRagdollConfig.load(
                    Minecraft.getInstance().getResourceManager(), TEST_MMD_PHYSICS);
            config.attach(instance, () -> Minecraft.getInstance().level,
                    Boolean.parseBoolean(System.getProperty("kasuga.testModelPhysicsDrop", "true")));
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Failed to attach test ragdoll config {}", TEST_MMD_PHYSICS, exception);
        }
    }

    private static void testObj() {
        ResourceLocation modelLoc = ResourceLocation.tryBuild(KasugaLib.MODID, "models/obj/df5_frame.obj");
        ResourceLocation instanceLoc = ResourceLocation.tryBuild(KasugaLib.MODID, "test_wheel");
        addStaticInstance(PipelineRegistry.obj(), modelLoc, instanceLoc, null);
    }

    private static void testBe() {
        ResourceLocation modelLoc = ResourceLocation.tryBuild(KasugaLib.MODID, "geometry.unknown");
        ResourceLocation instanceLoc = ResourceLocation.tryBuild(KasugaLib.MODID, "test_model");
        addStaticInstance(PipelineRegistry.be(), modelLoc, instanceLoc, null);
    }

    private static void testJe() {
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline = PipelineRegistry.je();
        addStaticInstance(pipeline, ResourceLocation.tryBuild(KasugaLib.MODID, "models/je/test_parent_a.json"),
                ResourceLocation.tryBuild(KasugaLib.MODID, "test_je_a"), null);
        addStaticInstance(pipeline, ResourceLocation.tryBuild(KasugaLib.MODID, "models/je/test_parent_b.json"),
                ResourceLocation.tryBuild(KasugaLib.MODID, "test_je_b"), new Transform().translate(2, 0, 0));
    }

    private static void addStaticInstance(ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline,
                                          ResourceLocation modelLoc, ResourceLocation instanceLoc, Transform transform) {
        if (!pipeline.hasModel(modelLoc) || pipeline.hasInstance(modelLoc, instanceLoc)) return;
        if (pipeline.createInstance(modelLoc, instanceLoc, transform, null, null) != null) {
            pipeline.addToRenderer(modelLoc, instanceLoc, "mc_bridge", "mc_backend");
        }
    }
}
