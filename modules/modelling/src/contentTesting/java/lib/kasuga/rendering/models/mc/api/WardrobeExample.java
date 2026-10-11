package lib.kasuga.rendering.models.mc.api;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.client.loading.LoadingIndicator;
import lib.kasuga.rendering.models.mc.backend.BackendInstance;
import lib.kasuga.rendering.models.mc.backend.MCBridge;
import lib.kasuga.rendering.models.mc.compat.iris.IrisCompat;
import lib.kasuga.rendering.models.mc.dynamic.fsm.KasugaModelPipelines;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.loaders.assembly.*;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.*;
import lib.kasuga.rendering.output.*;
import lib.kasuga.rendering.output.camera.*;
import lib.kasuga.rendering.output.gl.FramePreviewWindow;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import lib.kasuga.rendering.output.mc.*;
import lib.kasuga.rendering.output.mc.iris.CameraIrisSession;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Local PMX assets enter through the normal resource loader; the example uses only common Model APIs. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class WardrobeExample {
    private static final String VIEW = "kasuga_wardrobe:preview";
    private static final boolean SMOKE = Boolean.getBoolean("kasuga.wardrobeShaderSmoke");
    private static final boolean SHADER_PREVIEW = Boolean.getBoolean("kasuga.wardrobeShaderPreview");
    private static final boolean AUTO = Boolean.getBoolean("kasuga.wardrobeDemo") || SMOKE;
    private static final boolean CAPTURE = Boolean.getBoolean("kasuga.wardrobeCapture");
    private static final String SHADER_PACK = System.getProperty("kasuga.wardrobeShaderPack", "");
    private static final long START = System.nanoTime();
    private static Session session;
    private static boolean started;
    private static int warmup;

    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        var command = Commands.literal("wardrobe")
                .then(Commands.literal("start").executes(context -> {
                    try {
                        stop(); session = new Session(false); started = true;
                        context.getSource().sendSuccess(() -> Component.literal("Wardrobe preview ready; texture blue|rose, model short|long|ayuchan, item sword|pickaxe|none"), false);
                        return 1;
                    } catch (Exception failure) {
                        context.getSource().sendFailure(Component.literal(failure.getMessage())); return 0;
                    }
                }))
                .then(Commands.literal("stop").executes(context -> { stop(); started = true; return 1; }))
                .then(Commands.literal("capture").executes(context -> action(s -> s.beginSequence())))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(session == null ? "Wardrobe inactive" : session.status()), false);
                    return session == null ? 0 : 1;
                }));
        for (String operation : List.of("texture", "model", "item")) command.then(Commands.literal(operation)
                .then(Commands.argument("value", StringArgumentType.word()).executes(context -> {
                    String value = StringArgumentType.getString(context, "value");
                    try {
                        return action(s -> { switch (operation) {
                            case "texture" -> s.texture(value);
                            case "model" -> s.outfit(value);
                            case "item" -> s.item(value);
                        }});
                    } catch (RuntimeException failure) {
                        context.getSource().sendFailure(Component.literal(failure.getMessage())); return 0;
                    }
                })));
        event.getDispatcher().register(Commands.literal("ksglib").then(command));
    }
    private static int action(Consumer<Session> action) {
        if (session == null) return 0;
        action.accept(session); return 1;
    }
    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (session != null && (mc.level != session.level || LoadingIndicator.snapshot().active())) stop();
        if (AUTO && !started && System.nanoTime() - START > 180_000_000_000L) {
            started = true; failed(new IllegalStateException("Wardrobe resources/world not ready within 180 seconds")); return;
        }
        if (mc.level == null || mc.player == null || mc.getOverlay() != null || LoadingIndicator.snapshot().active()) return;
        try {
            if (AUTO && !started && ++warmup >= 60) {
                mc.setScreen(null); mc.options.pauseOnLostFocus = false;
                session = new Session(CAPTURE || SMOKE); started = true;
            }
            if (session != null) session.before();
        } catch (Exception failure) { failed(failure); }
    }
    @SubscribeEvent public static void equipment(RenderLevelStageEvent event) {
        if (session == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES
                || !VIEW.equals(MinecraftWorldViews.currentViewId())) return;
        try {
            if (SMOKE) {
                var iris = CameraIrisSession.current();
                if (iris == null || iris.pack() == null || !IrisCompat.isUsingShaderPack()
                        || !(iris.pipelines().getPipeline().orElse(null) instanceof IrisRenderingPipeline))
                    throw new IllegalStateException("Wardrobe did not enter an active Iris shader pipeline");
            }
            var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
            session.itemsRendered += session.gear.render(event.getPoseStack(), buffers, event.getCamera().getPosition(),
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            buffers.endBatch();
        } catch (Exception failure) { failed(failure); }
    }
    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (session == null) return;
        try {
            if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new IllegalStateException("Wardrobe GL error");
            if (session.complete) {
                if (SMOKE) LogUtils.getLogger().info("WARDROBE_SHADER_SMOKE_PASS pack={} preview={} (no timing)", SHADER_PACK, SHADER_PREVIEW);
                else { session.report(null); LogUtils.getLogger().info("WARDROBE_EXAMPLE_PASS {}", session.output); }
                if (CAPTURE || SMOKE) { stop(); Minecraft.getInstance().stop(); }
                else session.complete = false;
            }
        } catch (Exception failure) { failed(failure); }
    }
    private static void failed(Exception failure) {
        LogUtils.getLogger().error(SMOKE ? "WARDROBE_SHADER_SMOKE_FAIL" : "WARDROBE_EXAMPLE_FAIL", failure);
        try {
            if (SMOKE) { /* Shader smoke logs only; preserve the original capture and benchmark reports. */ }
            else if (session != null) session.report(failure);
            else {
                Path output = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/wardrobe-example");
                Files.createDirectories(output);
                Files.writeString(output.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create()
                        .toJson(Map.of("passed", false, "error", failure.toString())));
            }
        } catch (Exception reportFailure) { LogUtils.getLogger().error("Wardrobe failure report", reportFailure); }
        stop(); started = true;
        if (CAPTURE || SMOKE) Minecraft.getInstance().stop();
    }
    private static void stop() {
        if (session == null) return;
        Session previous = session; session = null; previous.close();
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("kasuga_wardrobe", path); }
    private static Model source(String name) {
        var source = KasugaModelPipelines.publishedSource(new McModelAssemblies.Reference(id("models/" + name + ".mmd.zip"), "model.pmx"));
        Model model = source == null ? null : source.pipeline().getModel(source.key());
        if (model == null) throw new IllegalStateException("Enable the resource pack made by scripts/prepare-wardrobe-example.py; missing " + name);
        return model;
    }
    private static Model copy(Model model) {
        return new ModelAssemblyBuilder("source", model, 1, part -> part.includeDynamics(false)).assemble().model();
    }
    /** Build material variants before publication, keeping the private copy's vertices, bones and morph references. */
    private static Model textureVariants(Model original, Model alternate) {
        Model model = copy(original);
        Material dress = model.getMaterialSet().getMaterials()[0];
        dress.addSprite(alternate.getMaterialSet().getMaterials()[0].getSprites().getFirst());
        List<Texture> textures = new ArrayList<>(List.of(model.getMaterialSet().getTextures()));
        textures.addAll(List.of(alternate.getMaterialSet().getTextures()));
        return new Model(model.getVertices(), model.getMeshes(), model.getBones(), model.getSkeleton(),
                new MaterialSet(textures, List.of(model.getMaterialSet().getMaterials())), model.getMeshMode(), null, model.getMorph());
    }

    private static final class Session implements AutoCloseable {
        final ClientLevel level = Minecraft.getInstance().level;
        final Path output = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/wardrobe-example");
        final ModelPipeLine<Object, BackendInstance, ResourceLocation, ResourceLocation, Object> sources;
        final McModelAssemblies assemblies;
        final McModelHandle handle;
        final McModelEquipment gear;
        final ArmorStand owner;
        CameraHandle camera;
        MinecraftFrameWindows.WindowOutput window;
        Model firstModel;
        byte[] blue, sword;
        final Map<String, Object> evidence = new LinkedHashMap<>();
        final List<Map<String, Object>> operations = new ArrayList<>();
        int phase, phaseFrames, frames, itemsRendered;
        long captureInitialBuilds;
        boolean sequence, complete;
        String outfit = "short", texture = "blue", item = "none";
        static final String[] SHOTS = {"01-short-blue", "02-short-rose", "03-long-mist", "04-short-blue-cached", "05-mc-sword", "06-wrist-rotated"};

        Session(boolean automated) throws Exception {
            if (SMOKE && (SHADER_PACK.isBlank() || CAPTURE))
                throw new IllegalArgumentException("Shader smoke requires kasugaWardrobeShaderPack and cannot enable capture");
            // These fixture rigs differ: preserve RibbonDress's independent bind bones explicitly.
            // The compatible Ayuchan outfit below uses the ordinary strict shared-bone path.
            Model body = copy(source("body"));
            var skeleton = body.getSkeleton();
            skeleton.defineAnchor("right_grip", Objects.requireNonNull(skeleton.getBoneMap().get("右手首")),
                    new Transform().translate(-.035f, -.03f, 0).rotate(0, 0, -38, true));
            skeleton.defineAnchor("left_grip", Objects.requireNonNull(skeleton.getBoneMap().get("左手首")),
                    new Transform().translate(.035f, -.03f, 0).rotate(0, 0, 38, true));
            Model shirt = textureVariants(source("short"), source("short_rose"));
            sources = new ModelPipeLine.Builder<Object, BackendInstance, ResourceLocation, ResourceLocation, Object>()
                    .withBridge("mc_bridge", new MCBridge()).withBackend("mc_backend", PipelineRegistry.backend()).buildForPublishedModels();
            sources.publishModels(Map.of(id("body"), body, id("short"), shirt,
                    id("long"), source("long"), id("ayuchan"), source("ayuchan"), id("hair"), source("hair")));
            var outputPipeline = new ModelPipeLine.Builder<Object, BackendInstance, ResourceLocation, ResourceLocation, Object>()
                    .withBridge("mc_bridge", new MCBridge()).withBackend("mc_backend", PipelineRegistry.backend()).buildForPublishedModels();
            assemblies = new McModelAssemblies(outputPipeline,
                    reference -> new McModelAssemblies.PublishedSource(sources, reference.model()), new ModelAssemblyCache());
            for (String cloth : List.of("short", "long", "ayuchan")) assemblies.register(id(cloth),
                    ModelAssemblyDefinition.builder("body", new McModelAssemblies.Reference(id("body")), part -> part.includeDynamics(false))
                            .part("garment", new McModelAssemblies.Reference(id(cloth)), part ->
                                    part.matchBodyBones(cloth.equals("ayuchan")).includeDynamics(false))
                            .part("hair", new McModelAssemblies.Reference(id("hair")), part -> part.matchBodyBones(false).includeDynamics(false)).build());
            handle = assemblies.handle(id("short"), id("actor"));
            try {
                if (!handle.mount()) throw new IllegalStateException("Outfit failed to mount");
                var origin = Minecraft.getInstance().player.position().add(0, 40, 0);
                handle.instance().getSkeletonInstance().enableFloatingOrigin(new Vector3d(origin.x, origin.y, origin.z));
                handle.setPos(origin);
                owner = new ArmorStand(level, 0, 0, 0); // Inventory isolated from the actual player.
                gear = new McModelEquipment(handle, () -> owner).bindHands("right_grip", "left_grip",
                        new Transform().scale(.6f, .6f, .6f), new Transform().scale(.6f, .6f, .6f));
                camera = MinecraftCameras.createFree(VIEW, new WorldCameraView(origin.x, origin.y + 1.03, origin.z - 3.3,
                        0, 0, 0, 42, 960, 720), new CameraRenderSettings(2, CameraRenderSettings.Quality.FANCY,
                        SHADER_PACK.isBlank() ? CameraRenderSettings.Shader.disabled()
                                : CameraRenderSettings.Shader.pack(Path.of(SHADER_PACK))), this::frame);
                if (!automated) window = MinecraftFrameWindows.open(VIEW, new FramePreviewWindow.Options("Kasuga wardrobe - local PMX fixtures", 960, 720));
                evidence.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
                evidence.put("ribbonRig", "Independent bind bones, no automatic retargeting or body fitting");
                evidence.put("hair", "User VRoid Overall Hair Presets / 13.pmx; included in every cached outfit");
                firstModel = handle.instance().getModel();
                if (automated) beginSequence();
            } catch (Exception failure) { close(); throw failure; }
        }
        void beginSequence() {
            outfit("short"); texture("blue"); item("none");
            phase = phaseFrames = frames = itemsRendered = 0; complete = false; sequence = true;
            blue = sword = null; operations.clear();
            firstModel = handle.instance().getModel();
            captureInitialBuilds = assemblies.cacheStats().builds();
        }
        void before() {
            if (camera.state() == CameraState.FAILED) throw new IllegalStateException("Wardrobe camera failed", camera.failure().orElse(null));
            if (System.nanoTime() - START > 240_000_000_000L && (CAPTURE || SMOKE) && !complete)
                throw new IllegalStateException("Wardrobe sequence timed out");
            if (sequence && phaseFrames >= 30) {
                phaseFrames = 0; phase++;
                switch (phase) {
                    case 1 -> texture("rose");
                    case 2 -> {
                        outfit("long");
                        if (handle.instance().getModel() == firstModel) throw new IllegalStateException("Clothing model did not change");
                    }
                    case 3 -> {
                        long builds = assemblies.cacheStats().builds(); outfit("short"); texture("blue");
                        if (handle.instance().getModel() != firstModel || assemblies.cacheStats().builds() != builds)
                            throw new IllegalStateException("Returning to short outfit rebuilt shared geometry");
                        evidence.put("returnedModelReused", true);
                    }
                    case 4 -> item("sword");
                    case 5 -> handle.instance().getSkeletonInstance().rotate("右手首", new Quaternionf().rotateZ(.6f));
                    default -> { sequence = false; complete = true; }
                }
            }
            handle.instance().update();
        }
        void texture(String choice) {
            if (!choice.equals("blue") && !choice.equals("rose")) throw new IllegalArgumentException("texture: blue|rose");
            if (!outfit.equals("short")) throw new IllegalArgumentException("Select model short to use the two texture variants");
            var before = handle.instance().getModel(); long builds = assemblies.cacheStats().builds(), start = SMOKE ? 0 : System.nanoTime();
            var material = assemblies.resolve(id("short")).part("garment").material(sources.getModel(id("short")).getMaterialSet().getMaterials()[0]);
            handle.instance().getMaterialInstance().setCurrentMatFrame(material, choice.equals("rose") ? 1 : 0);
            texture = choice;
            if (!SMOKE) operations.add(Map.of("operation", "texture-" + choice, "cpuMicros", (System.nanoTime() - start) / 1000.0));
            if (handle.instance().getModel() != before || assemblies.cacheStats().builds() != builds)
                throw new IllegalStateException("Texture swap rebuilt geometry");
            evidence.put("textureReusesModel", true);
        }
        void outfit(String choice) {
            if (!List.of("short", "long", "ayuchan").contains(choice)) throw new IllegalArgumentException("model: short|long|ayuchan");
            long start = SMOKE ? 0 : System.nanoTime();
            if (!choice.equals(outfit) && !handle.switchAssembly(assemblies, id(choice))) throw new IllegalStateException("Outfit not ready");
            outfit = choice; texture = choice.equals("long") ? "mist" : "blue";
            if (!SMOKE) operations.add(Map.of("operation", "model-" + choice, "cpuMicrosIncludingInstanceAndBackend", (System.nanoTime() - start) / 1000.0));
            if (choice.equals("short")) texture("blue");
        }
        void item(String choice) {
            var stack = switch (choice) {
                case "sword" -> new ItemStack(Items.DIAMOND_SWORD);
                case "pickaxe" -> new ItemStack(Items.IRON_PICKAXE);
                case "none" -> ItemStack.EMPTY;
                default -> throw new IllegalArgumentException("item: sword|pickaxe|none");
            };
            owner.setItemSlot(EquipmentSlot.MAINHAND, stack); item = choice;
        }
        String status() { return "model=" + outfit + ", texture=" + texture + ", item=" + item + ", cache=" + assemblies.cacheStats(); }
        void frame(OutputFrame<FrameTexture> frame) {
            if (!sequence || ++phaseFrames != 30) return;
            frames += 30;
            if (SMOKE) {
                if (SHADER_PREVIEW && phase == 4) {
                    try { saveFrame(frame, "shader-preview"); }
                    catch (Exception failure) { throw new IllegalStateException("Cannot save requested shader preview", failure); }
                }
                if (phase == 5 && (itemsRendered < 40 || assemblies.cacheStats().builds() > captureInitialBuilds + 1))
                    throw new IllegalStateException("Shader sequence skipped equipment or rebuilt a cached outfit");
                LogUtils.getLogger().info("WARDROBE_SHADER_SMOKE_PHASE {} complete", SHOTS[phase]);
                return;
            }
            try {
                byte[] rgba = saveFrame(frame, SHOTS[phase]);
                if (phase == 0) blue = rgba;
                if (phase == 1) evidence.put("textureChangedPixels", changed(blue, rgba, frame.width(), frame.height()));
                if (phase == 2) evidence.put("modelChangedPixels", changed(blue, rgba, frame.width(), frame.height()));
                if (phase == 4) { sword = rgba; evidence.put("equipmentChangedPixels", changed(blue, rgba, frame.width(), frame.height())); }
                if (phase == 5) {
                    evidence.put("wristChangedPixels", changed(sword, rgba, frame.width(), frame.height()));
                    if (itemsRendered < 40 || assemblies.cacheStats().builds() > captureInitialBuilds + 1)
                        throw new IllegalStateException("Unexpected item/cache count");
                    evidence.put("newBuildsDuringCapture", assemblies.cacheStats().builds() - captureInitialBuilds);
                    for (String metric : List.of("textureChangedPixels", "modelChangedPixels", "equipmentChangedPixels", "wristChangedPixels"))
                        if (((Number) evidence.get(metric)).intValue() < 40) throw new IllegalStateException("No visible change for " + metric);
                }
            } catch (Exception failure) { throw new IllegalStateException("Cannot capture/validate wardrobe phase " + phase, failure); }
        }
        byte[] saveFrame(OutputFrame<FrameTexture> frame, String name) throws Exception {
            byte[] rgba = RgbaReadback.copy(frame.resource());
            Files.createDirectories(output);
            BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0, i = 0; y < frame.height(); y++) for (int x = 0; x < frame.width(); x++, i += 4)
                image.setRGB(x, y, ((rgba[i + 3] & 255) << 24) | ((rgba[i] & 255) << 16) | ((rgba[i + 1] & 255) << 8) | (rgba[i + 2] & 255));
            ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
            return rgba;
        }
        static int changed(byte[] a, byte[] b, int width, int height) {
            int changed = 0;
            // Central subject area; sky edges cannot satisfy the visual change check.
            for (int y = height / 8; y < height * 7 / 8; y++) for (int x = width / 4; x < width * 3 / 4; x++) {
                int i = (y * width + x) * 4, delta = 0;
                for (int channel = 0; channel < 3; channel++) delta += Math.abs((a[i + channel] & 255) - (b[i + channel] & 255));
                if (delta > 60) changed++;
            }
            return changed;
        }
        void report(Exception failure) throws Exception {
            var report = new LinkedHashMap<String, Object>();
            report.put("passed", failure == null); report.put("frames", frames); report.put("itemsRendered", itemsRendered);
            report.put("cache", assemblies.cacheStats()); report.put("evidence", evidence); report.put("operations", operations);
            report.put("timingScope", "Single observed CPU operations, not warmed benchmark, GPU time or FPS; use modelAssemblyProbe for performance results");
            if (failure != null) report.put("error", failure.toString());
            Files.createDirectories(output); Files.writeString(output.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
        @Override public void close() {
            for (AutoCloseable resource : new AutoCloseable[]{window, camera, gear, handle::destroy, assemblies,
                    () -> sources.replaceModels(Map.of())}) {
                try { if (resource != null) resource.close(); }
                catch (Exception failure) { LogUtils.getLogger().error("Wardrobe cleanup", failure); }
            }
        }
    }
}
