package test.kasuga.modelling;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent;
import lib.kasuga.rendering.effect.debug.ShaderParameterScreen;
import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraph;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphContext;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphPassDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistration;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphTarget;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import lib.kasuga.rendering.effect.shader.RenderShaderRegistry;
import lib.kasuga.rendering.effect.shader.ShaderLoadState;
import lib.kasuga.rendering.effect.shader.ShaderRegistration;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import lib.kasuga.shader.ShaderParameterType;
import lib.kasuga.shader.compiler.ShaderResourceCompiler;
import lib.kasuga.shader.graal.GraalShaderCompiler;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in full Minecraft test of the shipped JS definition, GPU output and real parameter widgets. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class GraalShaderClientSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testGraalShader");
    private static final long START = System.nanoTime();
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static final List<Map<String, Object>> CAPTURES = new ArrayList<>();
    private static final String[] LOOKS = {"default-warm", "neutral", "blue", "reset-warm"};
    private static final PostProcessGraphTarget MAIN = PostProcessGraphTarget.main();
    private static final PostProcessGraphTarget SCENE = PostProcessGraphTarget.managed(id("scene"));
    private static final PostProcessGraphTarget PROBE = PostProcessGraphTarget.managed(id("probe"));
    private static final PostProcessGraphTarget RESULT = PostProcessGraphTarget.managed(id("probe_result"));
    private static ShaderRegistration registration;
    private static PostProcessGraphRegistration graph;
    private static RenderShaderHandle shader;
    private static ShaderParameterScreen screen;
    private static Throwable failure;
    private static boolean started, finished, captured;
    private static int warmup, stage, drawFrames, uiFrames, programId;
    private static long generation;

    private GraalShaderClientSmokeTest() {}

    @SubscribeEvent
    public static void register(RegisterRenderPipelinesEvent event) {
        if (!ENABLED) return;
        try {
            Path source = Path.of(System.getProperty("kasuga.testGraalShader.script"));
            long start = System.nanoTime();
            var program = new GraalShaderCompiler().compile(source);
            REPORT.put("scriptCompileNanos", System.nanoTime() - start);
            REPORT.put("source", source.toString());
            REPORT.put("sourceSha256", HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
            check(program.id().equals("example:script_tint"), "Unexpected script program ID");
            var tint = program.parameterSchema().require("Tint");
            check(tint.type() == ShaderParameterType.COLOR_RGB, "Script lost RGB parameter type");
            check(tint.description().equals("画面色调"), "Script lost UTF-8 description");
            check(tint.defaultValues().size() == 3, "Script lost RGB defaults");
            REPORT.put("schema", Map.of("name", tint.name(), "type", tint.type().name(),
                    "description", tint.description(), "defaults", tint.defaultValues()));
            var resources = ShaderResourceCompiler.compile(List.of(program), output().resolve("generated"));
            check(resources.resourceCount() == 3, "Expected vertex, fragment and JSON resources");
            REPORT.put("generatedResources", List.copyOf(resources.resources().keySet()));
            var registrar = event.registrar(id("owner"));
            registration = registrar.shader(program);
            shader = registration.handle();
            graph = registrar.graph(createGraph(), 1000);
        } catch (Exception | LinkageError problem) {
            failure = problem;
        }
    }

    private static PostProcessGraph createGraph() {
        return PostProcessGraph.builder(id("graph"))
                .target(PostProcessTargetDescriptor.builder(id("scene")).screenScale(1).build())
                .target(PostProcessTargetDescriptor.builder(id("probe")).fixedSize(32, 16)
                        .clearColor(0.8f, 0.4f, 0.2f, 0.5f).build())
                .target(PostProcessTargetDescriptor.builder(id("probe_result")).fixedSize(32, 16).build())
                .prepare((context, frame) -> started && !finished && failure == null && shader.isReady())
                .pass(PostProcessGraphPassDescriptor.builder(id("capture"), context ->
                                context.copyColor(MAIN, SCENE, false))
                        .reads(MAIN).writes(SCENE).build())
                .pass(PostProcessGraphPassDescriptor.builder(id("paint_probe"), context ->
                                context.write(PROBE).clear(Minecraft.ON_OSX))
                        .writes(PROBE).build())
                .pass(PostProcessGraphPassDescriptor.builder(id("filter"), GraalShaderClientSmokeTest::draw)
                        .reads(SCENE, PROBE).writes(MAIN, RESULT).build())
                .build();
    }

    @SubscribeEvent
    public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        if (failure != null) { finish(failure); return; }
        if (System.nanoTime() - START > 180_000_000_000L) {
            finish(new IllegalStateException("Graal shader client timeout at stage " + stage)); return;
        }
        if (started || mc.level == null || mc.player == null || mc.getOverlay() != null) return;
        if (shader == null || shader.status().state() == ShaderLoadState.FAILED) {
            finish(new IllegalStateException("Script shader did not load: "
                    + (shader == null ? "not registered" : shader.status()))); return;
        }
        if (!shader.isReady() || ++warmup < 100) return;
        try {
            mc.setWindowActive(true);
            mc.setScreen(null);
            mc.options.hideGui = true;
            mc.player.setYRot(-45);
            mc.player.setXRot(18);
            shader.parameters().resetAll();
            expectTint(1, 0.5, 0.25);
            generation = shader.generation();
            programId = shader.require().getId();
            check(generation > 0 && GL20.glIsProgram(programId), "Shader has no GPU program");
            check(GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_TRUE, "GPU link failed");
            REPORT.put("shaderId", shader.id().toString());
            REPORT.put("gpuProgramId", programId);
            REPORT.put("shaderGeneration", generation);
            REPORT.put("minecraft", SharedConstants.getCurrentVersion().getName());
            REPORT.put("java", System.getProperty("java.version"));
            REPORT.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
            REPORT.put("glVersion", GL11.glGetString(GL11.GL_VERSION));
            started = true;
        } catch (Exception problem) { finish(problem); }
    }

    private static void draw(PostProcessGraphContext context) {
        try {
            boolean capture = stage % 2 == 0 && !captured && ++drawFrames >= 12
                    && Minecraft.getInstance().screen == null;
            RenderTarget scene = context.read(SCENE);
            byte[] original = capture ? pixels(scene) : null;
            RenderSystem.disableBlend();
            context.fullscreen(MAIN, shader).colorSampler("SceneSampler", scene).draw(false);
            context.fullscreen(RESULT, shader).colorSampler("SceneSampler", context.read(PROBE)).draw(false);
            if (!capture) return;

            double[] tint = shader.parameters().values("Tint");
            byte[] filtered = pixels(context.write(MAIN));
            byte[] probeSource = pixels(context.read(PROBE));
            byte[] probeResult = pixels(context.write(RESULT));
            var result = new LinkedHashMap<String, Object>();
            String name = LOOKS[stage / 2];
            result.put("look", name);
            result.put("tint", tint);
            result.put("world", compare(original, filtered, tint));
            result.put("alphaProbe", compare(probeSource, probeResult, tint));
            check((probeSource[3] & 255) >= 127 && (probeSource[3] & 255) <= 128,
                    "Probe did not contain translucent source alpha");
            result.put("probeInputRgba", rgba(probeSource));
            result.put("probeOutputRgba", rgba(probeResult));
            float[] uploaded = new float[3];
            GL20.glGetUniformfv(programId, GL20.glGetUniformLocation(programId, "Tint"), uploaded);
            for (int i = 0; i < 3; i++) check(Math.abs(uploaded[i] - tint[i]) < 0.000001,
                    "GPU Tint uniform differs from parameter panel");
            result.put("gpuUniform", uploaded);
            result.put("parameterVersion", shader.parameters().version());
            save(name + "-input", original, scene.width, scene.height);
            save(name, filtered, scene.width, scene.height);
            save(name + "-alpha-probe", probeResult, 32, 16);
            CAPTURES.add(result);
            check(shader.generation() == generation && shader.require().getId() == programId,
                    "Parameter change recompiled the shader");
            check(GL11.glGetError() == GL11.GL_NO_ERROR, "Shader draw/readback raised a GL error");
            captured = true;
            LogUtils.getLogger().info("GRAAL_SHADER_CAPTURE {} {}", name, result);
        } catch (Exception problem) { failure = problem; }
    }

    @SubscribeEvent
    public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || !started) return;
        if (failure != null) { finish(failure); return; }
        var mc = Minecraft.getInstance();
        try {
            if (stage % 2 == 0) {
                if (!captured) return;
                if (++stage == 7) { finish(null); return; }
                screen = new ShaderParameterScreen(null);
                mc.setScreen(screen);
                selectScriptShader();
                uiFrames = 0;
                return;
            }
            check(mc.screen == screen, "Parameter screen was replaced unexpectedly");
            if (++uiFrames == 2) {
                if (stage == 1) {
                    saveMain("parameters-default");
                    dragTint(1, 1, 1);
                } else if (stage == 3) dragTint(0.2, 0.5, 1);
                else click(button("Reset all"));
            }
            if (uiFrames == 5) {
                if (stage == 1) expectTint(1, 1, 1);
                else if (stage == 3) expectTint(0.2, 0.5, 1);
                else expectTint(1, 0.5, 0.25);
                check(button("Reset all").active == shader.parameters().hasOverrides(),
                        "Reset all button did not follow the live parameter overrides");
                check(button("Reset").active == !shader.parameters().isDefault("Tint"),
                        "Parameter reset button did not follow the live Tint value");
                REPORT.put("realScreenMouseEvents", true);
                REPORT.put("resetButtonsFollowOverrides", true);
                saveMain("parameters-" + LOOKS[(stage + 1) / 2]);
            }
            if (uiFrames == 7) {
                click(button("Done"));
                check(mc.screen == null, "Done did not close the parameter screen");
                screen = null;
                stage++;
                captured = false;
                drawFrames = 0;
            }
        } catch (Exception problem) { finish(problem); }
    }

    private static void dragTint(double red, double green, double blue) {
        double[] values = {red, green, blue};
        for (int i = 0; i < 3; i++) {
            String label = "RGB".substring(i, i + 1) + ":";
            var slider = screen.children().stream().filter(AbstractSliderButton.class::isInstance)
                    .map(AbstractSliderButton.class::cast)
                    .filter(widget -> widget.getMessage().getString().startsWith(label))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Missing Tint slider " + label));
            double left = slider.getX() + 4;
            double x = left + values[i] * (slider.getWidth() - 8);
            double y = slider.getY() + slider.getHeight() / 2.0;
            check(screen.mouseClicked(left, y, 0), "Slider click was not handled");
            check(screen.mouseDragged(x, y, 0, x - left, 0), "Slider drag was not handled");
            screen.mouseReleased(x, y, 0);
        }
    }

    private static void selectScriptShader() {
        var ids = RenderShaderRegistry.registeredIds().stream()
                .filter(id -> RenderShaderRegistry.get(id).orElseThrow().parameters().schema().size() > 0)
                .toList();
        int index = ids.indexOf(shader.id());
        check(index >= 0, "Script shader missing from the parameter editor");
        for (int i = 0; i < index; i++) click(button("Next >"));
        REPORT.put("parameterScreenNextClicks", index);
    }

    private static Button button(String label) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(widget -> widget.getMessage().getString().equals(label))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing button " + label));
    }

    private static void click(Button button) {
        check(button.active, "Button disabled: " + button.getMessage().getString());
        double x = button.getX() + button.getWidth() / 2.0;
        double y = button.getY() + button.getHeight() / 2.0;
        check(screen.mouseClicked(x, y, 0), "Button click was not handled");
        screen.mouseReleased(x, y, 0);
    }

    private static void expectTint(double red, double green, double blue) {
        double[] expected = {red, green, blue};
        double[] actual = shader.parameters().values("Tint");
        for (int i = 0; i < 3; i++) check(Math.abs(actual[i] - expected[i]) < 0.000001,
                "Tint component " + i + " expected " + expected[i] + " but was " + actual[i]);
    }

    private static Map<String, Object> compare(byte[] input, byte[] actual, double[] tint) {
        check(input.length == actual.length, "Framebuffer sizes differ");
        int maximum = 0, changed = 0;
        for (int p = 0; p < input.length; p++) {
            int channel = p % 4;
            int source = input[p] & 255;
            int expected = channel == 3 ? source : (int) Math.round(source * tint[channel]);
            int value = actual[p] & 255;
            int error = Math.abs(value - expected);
            maximum = Math.max(maximum, error);
            if (value != source) changed++;
            check(error <= 1, "Pixel channel " + p + " expected " + expected + " but was " + value);
            if (channel == 3) check(value == source, "Shader changed alpha");
        }
        return Map.of("pixels", input.length / 4, "maxChannelError", maximum,
                "changedChannels", changed, "alphaUnchanged", true);
    }

    private static List<Integer> rgba(byte[] pixels) {
        return List.of(pixels[0] & 255, pixels[1] & 255, pixels[2] & 255, pixels[3] & 255);
    }

    private static byte[] pixels(RenderTarget target) {
        return RgbaReadback.copy(new FrameTexture(target.frameBufferId,
                target.getColorTextureId(), target.width, target.height));
    }

    private static void saveMain(String name) throws Exception {
        var target = Minecraft.getInstance().getMainRenderTarget();
        save(name, pixels(target), target.width, target.height);
    }

    private static void save(String name, byte[] rgba, int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int p = (y * width + x) * 4;
            image.setRGB(x, y, (rgba[p + 3] & 255) << 24 | (rgba[p] & 255) << 16
                    | (rgba[p + 1] & 255) << 8 | rgba[p + 2] & 255);
        }
        Files.createDirectories(output());
        ImageIO.write(image, "PNG", output().resolve(name + ".png").toFile());
    }

    private static Path output() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("debug/graal-shader");
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("graal_shader_smoke", path);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void finish(Throwable problem) {
        if (finished) return;
        finished = true;
        try {
            REPORT.put("captures", CAPTURES);
            REPORT.put("stage", stage);
            REPORT.put("shaderGenerationAfter", shader == null ? 0 : shader.generation());
            REPORT.put("gpuProgramUnchanged", shader != null && shader.isReady()
                    && shader.require().getId() == programId);
            if (graph != null) graph.close();
            if (registration != null) registration.close();
            REPORT.put("registrationsClosed", (graph == null || !graph.isActive())
                    && (registration == null || !registration.isActive()));
            REPORT.put("passed", problem == null);
            REPORT.put("failure", problem == null ? "" : problem.toString());
            Files.createDirectories(output());
            Path report = output().resolve("report.json");
            Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (problem == null) LogUtils.getLogger().info("GRAAL_SHADER_CLIENT_SMOKE_PASS {}", report);
            else LogUtils.getLogger().error("GRAAL_SHADER_CLIENT_SMOKE_FAIL", problem);
        } catch (Exception cleanup) { LogUtils.getLogger().error("Graal shader test cleanup/report failed", cleanup); }
        Minecraft.getInstance().stop();
    }
}
