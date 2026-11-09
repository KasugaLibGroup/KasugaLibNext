package test.kasuga.modelling.demo.shader;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent;
import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import lib.kasuga.rendering.effect.post.graph.*;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import lib.kasuga.rendering.effect.shader.ShaderLoadState;
import lib.kasuga.rendering.effect.shader.ShaderRegistration;
import lib.kasuga.rendering.effect.shader.ShaderParameterBlock;
import lib.kasuga.rendering.output.*;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import lib.kasuga.rendering.output.mc.HeadlessClient;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
import lib.kasuga.shader.compiler.ShaderResourceCompiler;
import lib.kasuga.shader.graal.GraalShaderCompiler;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in real Minecraft capture. Nothing is installed in ordinary client runs. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ShaderDslShowcase {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.shaderShowcase");
    private static final long START = System.nanoTime();
    private static final PostProcessGraphTarget MAIN = PostProcessGraphTarget.main();
    private static final PostProcessGraphTarget SCENE = PostProcessGraphTarget.managed(id("scene"));
    private static final PostProcessGraphTarget PROBE = PostProcessGraphTarget.managed(id("probe"));
    private static final PostProcessGraphTarget PROBE_RESULT = PostProcessGraphTarget.managed(id("probe_result"));
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static final List<Map<String, Object>> CHECKS = new ArrayList<>();
    private static ShaderRegistration registration;
    private static PostProcessGraphRegistration graph;
    private static RenderShaderHandle shader;
    private static ShaderParameterBlock parameters;
    private static AutoCloseable output;
    private static Process encoder;
    private static OutputStream video;
    private static Throwable failure;
    private static boolean started, finished;
    private static int warmup, primingDraws, frame, lastDraw = -1, programId, width, height;
    private static long generation;
    private static float strength, split;

    private ShaderDslShowcase() {}

    @SubscribeEvent
    public static void register(RegisterRenderPipelinesEvent event) {
        if (!ENABLED) return;
        try {
            check(fps() >= 1 && fps() <= 120, "FPS must be in 1..120");
            check(frames() >= fps() * 16 && frames() <= fps() * 120, "Record 16..120 seconds");
            var program = new GraalShaderCompiler().compile(Path.of(System.getProperty("kasuga.shaderShowcase.script")));
            ShaderResourceCompiler.compile(List.of(program), directory().resolve("client-generated"));
            var registrar = event.registrar(id("owner"));
            registration = registrar.shader(program);
            shader = registration.handle();
            parameters = shader.createParameterBlock();
            graph = registrar.graph(PostProcessGraph.builder(id("graph"))
                    .target(PostProcessTargetDescriptor.builder(id("scene")).screenScale(1).build())
                    .target(PostProcessTargetDescriptor.builder(id("probe")).fixedSize(32, 16)
                            .clearColor(0.3f, 0.5f, 0.7f, 0.5f).build())
                    .target(PostProcessTargetDescriptor.builder(id("probe_result")).fixedSize(32, 16).build())
                    .prepare((context, graphFrame) -> started && !finished && failure == null && shader.isReady())
                    .pass(PostProcessGraphPassDescriptor.builder(id("copy"), ctx -> ctx.copyColor(MAIN, SCENE, false))
                            .reads(MAIN).writes(SCENE).build())
                    .pass(PostProcessGraphPassDescriptor.builder(id("probe_clear"), ctx -> ctx.write(PROBE).clear(Minecraft.ON_OSX))
                            .writes(PROBE).build())
                    .pass(PostProcessGraphPassDescriptor.builder(id("prism"), ShaderDslShowcase::draw)
                            .reads(SCENE, PROBE).writes(MAIN, PROBE_RESULT).build()).build(), 1000);
        } catch (Exception | LinkageError problem) { failure = problem; }
    }

    @SubscribeEvent
    public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        if (failure != null) { finish(failure); return; }
        if (System.nanoTime() - START > 360_000_000_000L) { finish(new IllegalStateException("Showcase timeout")); return; }
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getOverlay() != null) return;
        try {
            if (!started) {
                check(shader != null && shader.status().state() != ShaderLoadState.FAILED, "Shader failed to load");
                mc.setWindowActive(true);
                mc.options.hideGui = true;
                mc.options.bobView().set(false);
                mc.player.setYRot(-45);
                mc.player.yRotO = -45;
                mc.player.setXRot(24);
                mc.player.xRotO = 24;
                if (!shader.isReady() || ++warmup < 480) return;
                mc.setScreen(null);
                generation = shader.generation();
                programId = shader.require().getId();
                check(GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_TRUE, "GPU link failed");
                REPORT.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
                REPORT.put("glVersion", GL11.glGetString(GL11.GL_VERSION));
                REPORT.put("minecraft", SharedConstants.getCurrentVersion().getName());
                REPORT.put("java", System.getProperty("java.version"));
                REPORT.put("backend", HeadlessClient.backend());
                REPORT.put("fps", fps());
                REPORT.put("expectedFrames", frames());
                REPORT.put("gpuProgramId", programId);
                REPORT.put("shaderGeneration", generation);
                output = MinecraftFrameOutputs.open(FrameOutputMode.OFFSCREEN_ONLY, ShaderDslShowcase::capture);
                started = true;
            }
            // Capture time is deterministic even when rendering/encoding is slower than playback.
            float seconds = (float) frame / fps();
            strength = seconds < 2 ? 0 : seconds < 5 ? (seconds - 2) / 3 * 0.9f : 0.9f;
            split = seconds >= 6 && seconds < 14 ? (float) (0.5 + 0.42 * Math.sin((seconds - 6) * 0.9)) : 0;
            parameters.setFloat("Strength", strength);
            mc.player.setYRot(-45 + (float) Math.sin(seconds * 0.15) * 18);
            mc.player.yRotO = mc.player.getYRot();
            mc.player.setXRot(24);
            mc.player.xRotO = mc.player.getXRot();
        } catch (Exception problem) { finish(problem); }
    }

    private static void draw(PostProcessGraphContext context) {
        try {
            check(shader.generation() == generation && shader.require().getId() == programId, "GPU program changed during capture");
            var source = context.read(SCENE);
            // As in the existing Graal client smoke, prime managed target allocation and shader binds.
            if (++primingDraws <= 12) {
                drawTo(context, MAIN, source);
                return;
            }
            boolean inspect = frame == 0 || frame == fps() * 5 || frame == fps() * 10 || frame == fps() * 15;
            byte[] original = inspect ? pixels(source) : null;
            RenderSystem.disableBlend();
            drawTo(context, MAIN, source);
            if (inspect) {
                byte[] filtered = pixels(context.write(MAIN));
                drawTo(context, PROBE_RESULT, context.read(PROBE));
                byte[] probe = pixels(context.read(PROBE));
                byte[] probeResult = pixels(context.write(PROBE_RESULT));
                int maxRgb = 0, changed = 0, alphaErrors = 0;
                Set<Integer> colors = new HashSet<>();
                for (int i = 0; i < original.length; i += 4) {
                    int diff = 0;
                    for (int c = 0; c < 3; c++) diff = Math.max(diff, Math.abs((original[i + c] & 255) - (filtered[i + c] & 255)));
                    maxRgb = Math.max(maxRgb, diff);
                    if (diff > 3) changed++;
                    if (original[i + 3] != filtered[i + 3]) alphaErrors++;
                    if (i % 64 == 0) colors.add((original[i] & 255) << 16 | (original[i + 1] & 255) << 8 | original[i + 2] & 255);
                }
                save("world-" + frame + "-original.png", original, source.width, source.height);
                save("world-" + frame + "-effect.png", filtered, source.width, source.height);
                REPORT.put("lastInspectedWorldColors", colors.size());
                check(colors.size() > 32, "World has no visible detail: " + colors.size());
                check(alphaErrors == 0, "World alpha changed");
                check((probe[3] & 255) >= 127 && (probe[3] & 255) <= 128, "Alpha probe is not translucent");
                for (int i = 3; i < probe.length; i += 4) check(probe[i] == probeResult[i], "Translucent alpha changed");
                if (strength == 0) check(maxRgb <= 1, "Neutral effect differs from source");
                else check(changed > source.width * source.height / 10, "Effect did not change enough pixels");
                CHECKS.add(Map.of("frame", frame, "strength", strength, "split", split, "maxRgbDifference", maxRgb,
                        "changedPixels", changed, "alphaErrors", alphaErrors, "translucentAlphaPreserved", true));
                save("alpha-probe-" + frame + ".png", probeResult, 32, 16);
            }
            check(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error");
            lastDraw = frame;
        } catch (Exception problem) { failure = problem; }
    }

    private static void drawTo(PostProcessGraphContext context, PostProcessGraphTarget destination, RenderTarget source) {
        context.fullscreen(destination, shader).parameters(parameters).colorSampler("SceneSampler", source).uniforms(instance -> {
            instance.getUniform("Time").set((float) frame / fps());
            instance.getUniform("Aspect").set((float) source.width / source.height);
            instance.getUniform("Split").set(split);
        }).draw(false);
    }

    private static void capture(OutputFrame<FrameTexture> outputFrame) {
        if (finished || failure != null || lastDraw != frame) return;
        try {
            if (encoder == null) {
                width = outputFrame.width(); height = outputFrame.height();
                check(width % 2 == 0 && height % 2 == 0 && width <= 4096 && height <= 4096, "Unsupported video dimensions");
                Files.createDirectories(directory());
                encoder = new ProcessBuilder(System.getProperty("kasuga.shaderShowcase.ffmpeg", "ffmpeg"),
                        "-y", "-loglevel", "error", "-f", "rawvideo", "-pixel_format", "rgba",
                        "-video_size", width + "x" + height, "-framerate", String.valueOf(fps()), "-i", "pipe:0",
                        "-an", "-c:v", "libx264", "-preset", "fast", "-crf", "17", "-pix_fmt", "yuv420p",
                        "-movflags", "+faststart", directory().resolve("minecraft.mp4").toString())
                        .redirectError(directory().resolve("ffmpeg-capture.log").toFile())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                video = encoder.getOutputStream();
                REPORT.put("width", width); REPORT.put("height", height);
            }
            check(outputFrame.width() == width && outputFrame.height() == height, "Frame dimensions changed");
            video.write(RgbaReadback.copy(outputFrame.resource()));
            frame++;
            if (frame % fps() == 0) LogUtils.getLogger().info("SHADER_SHOWCASE_FRAME {}/{}", frame, frames());
        } catch (Exception problem) { failure = problem; }
    }

    @SubscribeEvent
    public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished) return;
        if (failure != null) finish(failure);
        else if (started && frame >= frames()) finish(null);
    }

    private static void finish(Throwable problem) {
        if (finished) return;
        finished = true;
        boolean programUnchanged = shader != null && shader.isReady() && shader.generation() == generation
                && shader.require().getId() == programId;
        // Close each owned resource independently: one failed cleanup must not strand the encoder.
        for (AutoCloseable resource : new AutoCloseable[]{output, graph, registration, video}) {
            if (resource == null) continue;
            try { resource.close(); }
            catch (Exception cleanup) { if (problem == null) problem = cleanup; else problem.addSuppressed(cleanup); }
        }
        if (encoder != null) {
            try {
                if (!encoder.waitFor(30, TimeUnit.SECONDS)) { encoder.destroyForcibly(); throw new IllegalStateException("Encoder timeout"); }
                check(encoder.exitValue() == 0, "FFmpeg failed; see ffmpeg-capture.log");
            } catch (Exception cleanup) { if (problem == null) problem = cleanup; else problem.addSuppressed(cleanup); }
        }
        try {
            REPORT.put("capturedFrames", frame);
            REPORT.put("pixelChecks", CHECKS);
            REPORT.put("gpuProgramUnchanged", programUnchanged);
            REPORT.put("registrationsClosed", (graph == null || !graph.isActive()) && (registration == null || !registration.isActive()));
            REPORT.put("passed", problem == null && frame == frames() && CHECKS.size() == 4 && programUnchanged);
            REPORT.put("failure", problem == null ? "" : problem.toString());
            Files.createDirectories(directory());
            Files.writeString(directory().resolve("capture-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (Boolean.TRUE.equals(REPORT.get("passed"))) LogUtils.getLogger().info("SHADER_SHOWCASE_PASS {}", directory());
            else LogUtils.getLogger().error("SHADER_SHOWCASE_FAIL {}", REPORT, problem);
        } catch (Exception reporting) { LogUtils.getLogger().error("Showcase report failed", reporting); }
        Minecraft.getInstance().stop();
    }

    private static byte[] pixels(RenderTarget target) {
        return RgbaReadback.copy(new FrameTexture(target.frameBufferId, target.getColorTextureId(), target.width, target.height));
    }

    private static void save(String name, byte[] rgba, int w, int h) throws Exception {
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = (y * w + x) * 4;
            image.setRGB(x, y, (rgba[i + 3] & 255) << 24 | (rgba[i] & 255) << 16 | (rgba[i + 1] & 255) << 8 | rgba[i + 2] & 255);
        }
        Files.createDirectories(directory());
        ImageIO.write(image, "PNG", directory().resolve(name).toFile());
    }

    private static int fps() { return Integer.getInteger("kasuga.shaderShowcase.fps", 30); }
    private static int frames() { return Integer.getInteger("kasuga.shaderShowcase.frames", 720); }
    private static Path directory() { return Path.of(System.getProperty("kasuga.shaderShowcase.output")); }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("shader_showcase", path); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
