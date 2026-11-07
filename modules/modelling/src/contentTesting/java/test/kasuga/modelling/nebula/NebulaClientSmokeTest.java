package test.kasuga.modelling.nebula;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Opt-in real client checks, including native Screen mouse events, GPU pixels, reload and cleanup. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class NebulaClientSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testNebula");
    private static final long START = System.nanoTime();
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static int stage, frames, recorded;
    private static long zeroCount, generation, stoppedDraws;
    private static boolean finished;
    private static CompletableFuture<Void> reload;
    private static Process encoder;
    private static OutputStream video;
    private static int videoWidth, videoHeight;
    private static NebulaDemo.ReturnState origin;
    private static boolean originalHideGui;
    private static byte[] interactionBaseline;
    private static byte[] cameraBaseline;
    private static float[] cameraMatrixBefore;
    private static long recordEmittedStart;
    private static float recordTimeStart;

    private NebulaClientSmokeTest() {}

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished) return;
        try {
            check(System.nanoTime() - START < 300_000_000_000L, "Client smoke timed out at stage " + stage);
            var mc = Minecraft.getInstance();
            if (stage == 11) {
                if (mc.level != null) return; // disconnect renders nested progress frames before clearing the world
                check(NebulaDemo.idle(), "Disconnect left the session active");
                REPORT.put("activeDisconnectCleanup", true);
                finish(null);
                return;
            }
            if (mc.getOverlay() != null || mc.player == null) return;
            if (stage == 0) {
                if (!NebulaDemo.active() || !(mc.screen instanceof NebulaScreen)) return;
                frames++;
                if (frames == 18) NebulaDemo.measureGpuTime(true);
                long samples = NebulaDemo.completedGpuQueries();
                if (frames < 40 || samples < 15 && frames < 80) return;
                NebulaDemo.measureGpuTime(false);
                check(samples >= 15, "Too few completed asynchronous GPU queries: " + samples);
                double meanMs = NebulaDemo.gpuPassTotalMs() / samples;
                check(Double.isFinite(meanMs) && meanMs > 0, "GPU timer returned invalid durations");
                REPORT.put("gpuAsyncSamples", samples);
                REPORT.put("gpuPassMeanMs", meanMs);
                REPORT.put("gpuPassMaxMs", NebulaDemo.gpuPassMaxMs());
                check(mc.level.dimension().equals(Level.END), "Not in the End");
                check(mc.player.getY() > 500, "Not at the clean observation point");
                check(NebulaDemo.drawCount() > 10, "Graph did not draw");
                check(GL20.glGetProgrami(NebulaDemo.shader().require().getId(), GL20.GL_LINK_STATUS) == GL11.GL_TRUE, "GPU shader did not link");
                REPORT.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
                REPORT.put("glVersion", GL11.glGetString(GL11.GL_VERSION));
                REPORT.put("dimension", mc.level.dimension().location().toString());
                REPORT.put("position", mc.player.position().toString());
                REPORT.put("nebulaWorldSize", "1344 x 768 x 960 blocks");
                REPORT.put("worldScale", NebulaDemo.WORLD_SCALE);
                check(NebulaDemo.renderWidth()==mc.getMainRenderTarget().width
                        && NebulaDemo.renderHeight()==mc.getMainRenderTarget().height,"Volume is not rendered at full resolution");
                REPORT.put("fullResolutionVolume",true);
                REPORT.put("shipScale",NebulaFlight.SHIP_SCALE);
                REPORT.put("defaultFrequencyHz", NebulaDemo.simulation().frequency());
                check(NebulaDemo.simulation().frequency() == .25f, "Default frequency was not reduced");
                origin = NebulaDemo.originalState();
                originalHideGui = NebulaDemo.originalHideGui();
                save("nebula");
                drag(0);
                check(NebulaDemo.simulation().frequency() == 0, "Left slider endpoint is not zero");
                zeroCount = NebulaDemo.simulation().emitted();
                next();
            } else if (stage == 1) {
                if (++frames < 65) return;
                check(NebulaDemo.simulation().emitted() == zeroCount, "Zero Hz emitted lightning");
                check(NebulaDemo.simulation().bolts().isEmpty(), "Old lightning did not expire");
                save("zero-frequency");
                NebulaDemo.simulation().paused(true);
                stage = 12; frames = 0;
            } else if (stage == 12) {
                if (++frames < 3) return;
                save("interaction-on");
                interactionBaseline = pixels();
                NebulaDemo.interaction(false);
                stage = 13; frames = 0;
            } else if (stage == 13) {
                if (++frames < 3) return;
                save("interaction-off");
                var wakeDiff = difference(pixels(), interactionBaseline);
                int totalPixels = mc.getMainRenderTarget().width * mc.getMainRenderTarget().height;
                check(((Number) wakeDiff.get("changedPixels")).intValue() >= totalPixels / 16,
                        "Wake did not disturb a broad region of cloud: " + wakeDiff);
                check(((Number) wakeDiff.get("brightenedPixels")).intValue() >= 8_000
                                && ((Number) wakeDiff.get("darkenedPixels")).intValue() >= 8_000,
                        "Wake did not both part ambient cloud and pile up rolling ridges: " + wakeDiff);
                REPORT.put("wakePixelDifference", wakeDiff);
                NebulaDemo.interaction(true);
                NebulaDemo.craftVisible(false);
                stage = 14; frames = 0;
            } else if (stage == 14) {
                if (++frames < 3) return;
                save("cuboid-hidden");
                REPORT.put("cuboidPixelDifference", difference(interactionBaseline,pixels()));
                interactionBaseline = null;
                NebulaDemo.craftVisible(true);
                stage = 15; frames = 0;
            } else if (stage == 15) {
                if (++frames < 3) return;
                save("core-on");
                interactionBaseline = pixels();
                NebulaDemo.core(false);
                stage = 16; frames = 0;
            } else if (stage == 16) {
                if (++frames < 3) return;
                save("core-off",false);
                REPORT.put("corePixelDifference",difference(interactionBaseline,pixels()));
                interactionBaseline = null;
                NebulaDemo.core(true);
                stage = 17; frames = 0;
            } else if (stage == 17) {
                if (++frames<3) return;
                save("detail-on");
                interactionBaseline=pixels();
                NebulaDemo.detail(false);
                stage=18;frames=0;
            } else if (stage == 18) {
                if (++frames<3) return;
                save("detail-off");
                REPORT.put("detailPixelDifference",difference(interactionBaseline,pixels()));
                NebulaDemo.detail(true);
                NebulaDemo.flash(0);
                stage=19;frames=0;
            } else if (stage == 19) {
                if (++frames<3) return;
                save("global-flash-off");
                interactionBaseline=pixels();
                NebulaDemo.flash(.85f);
                stage=20;frames=0;
            } else if (stage == 20) {
                if (++frames<3) return;
                save("global-flash");
                var global=difference(interactionBaseline,pixels());
                check(((Number)global.get("changedPixels")).intValue()>=mc.getMainRenderTarget().width*mc.getMainRenderTarget().height/4,
                        "Global lightning did not illuminate enough of the frame");
                REPORT.put("globalFlashPixelDifference",global);
                interactionBaseline=null;
                NebulaDemo.flash(-1);
                NebulaDemo.simulation().paused(false);
                drag(1);
                check(NebulaDemo.simulation().frequency() == 2, "Right slider endpoint is not two Hz");
                stage = 2; frames = 0;
            } else if (stage == 2) {
                frames++;
                if (frames > 15 && NebulaDemo.renderedPeak() > .65f) {
                    save("lightning");
                    drag(NebulaSimulation.DEFAULT_FREQUENCY / NebulaSimulation.MAX_FREQUENCY);
                    // Start the presentation at the canyon entrance, independently of how long acceptance checks took.
                    NebulaDemo.simulation().reset();
                    NebulaDemo.capture(true);
                    REPORT.put("realSliderMouseEvents", true);
                    next();
                }
            } else if (stage == 3) {
                int target = Integer.getInteger("kasuga.nebula.recordFrames", 0);
                if (recorded < target) { record(); return; }
                closeVideo();
                NebulaDemo.capture(false);
                generation = NebulaDemo.shader().generation();
                reload = mc.reloadResourcePacks();
                next();
            } else if (stage == 4) {
                if (!reload.isDone() || !NebulaDemo.shader().isReady() || ++frames < 20) return;
                reload.join();
                check(NebulaDemo.shader().generation() > generation, "Reload did not replace shader generation");
                check(mc.screen instanceof NebulaScreen, "Resource reload replaced the control screen");
                save("after-reload");
                REPORT.put("reloadGeneration", NebulaDemo.shader().generation());
                NebulaDemo.simulation().paused(true);
                // Exercise actual framebuffer resize and Screen re-init, not just a changed uniform.
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 800, 500);
                next();
            } else if (stage == 5) {
                if (++frames < 20) return;
                save("resized");
                check(mc.screen instanceof NebulaScreen, "Resize lost controls");
                drag(.5);
                check(Math.abs(NebulaDemo.simulation().frequency() - 1f) < .01, "Resized slider did not update simulation");
                REPORT.put("resizeAndDrag", true);
                cameraBaseline = pixels();
                cameraMatrixBefore = cameraMatrix();
                check(mc.screen.mouseClicked(mc.screen.width - 20, mc.screen.height - 20, 1), "Camera drag click was not handled");
                check(mc.screen.mouseDragged(mc.screen.width - 60, mc.screen.height - 20, 1, -40, 0), "Camera drag was not handled");
                mc.screen.mouseReleased(mc.screen.width - 60, mc.screen.height - 20, 1);
                next();
            } else if (stage == 6) {
                if (++frames < 15) return;
                save("camera-parallax");
                // The volume now fills the image, so its centroid need not move. Compare actual paused pixels and GPU view.
                REPORT.put("cameraOrbitPixelDifference", difference(cameraBaseline,pixels()));
                float[] after = cameraMatrix();
                double matrixChange = 0;
                for (int i = 0; i < 16; i++) matrixChange += Math.abs(after[i]-cameraMatrixBefore[i]);
                check(matrixChange > .01, "Camera drag did not change the GPU view matrix");
                REPORT.put("cameraMatrixChange",matrixChange);
                cameraBaseline = null; cameraMatrixBefore = null;
                REPORT.put("worldSpaceCameraDrag", true);
                check(mc.screen.keyPressed(256, 0, 0), "Escape was not handled");
                stoppedDraws = NebulaDemo.drawCount();
                next();
            } else if (stage == 7) {
                if (++frames < 30) return;
                check(NebulaDemo.idle(), "Escape left the session active");
                check(NebulaDemo.drawCount() == stoppedDraws, "Stopped graph still draws");
                check(!(mc.screen instanceof NebulaScreen), "Escape left the controls open");
                check(NebulaDemo.lastQueryDeleteVerified(), "Escape did not delete GPU timer queries");
                checkRestored();
                REPORT.put("escapeRestoresPlayer", true);
                check(NebulaDemo.start(), "Restart failed");
                next();
            } else if (stage == 8) {
                if (!NebulaDemo.active() || ++frames < 15) return;
                NebulaDemo.stop(); NebulaDemo.stop();
                next();
            } else if (stage == 9) {
                if (++frames < 20) return;
                check(NebulaDemo.idle(), "Repeated stop is not idempotent");
                check(NebulaDemo.lastQueryDeleteVerified(), "Repeated stop left GPU timer queries allocated");
                REPORT.put("timerQueriesReleasedOnStop", true);
                checkRestored();
                REPORT.put("restartAndIdempotentStop", true);
                check(NebulaDemo.start(), "Start before disconnect failed");
                next();
            } else if (stage == 10) {
                if (!NebulaDemo.active() || ++frames < 15) return;
                stage = 11;
                mc.level.disconnect();
                mc.disconnect();
            }
        } catch (Throwable problem) { finish(problem); }
    }

    private static void next() { stage++; frames = 0; }

    private static float[] cameraMatrix() {
        int program = NebulaDemo.shader().require().getId();
        var matrix = new float[16];
        GL20.glGetUniformfv(program,GL20.glGetUniformLocation(program,"InverseView"),matrix);
        return matrix;
    }

    private static Map<String,Object> difference(byte[] before, byte[] after) {
        check(before.length == after.length, "A/B framebuffer dimensions changed");
        int changed = 0, brightened = 0, darkened = 0, max = 0;
        long total = 0;
        for (int i = 0; i < before.length; i += 4) {
            int delta = 0, lumaDelta = 0;
            for (int channel = 0; channel < 3; channel++) {
                int b = before[i+channel] & 255, a = after[i+channel] & 255;
                int d = Math.abs(b - a);
                delta = Math.max(delta, d); total += d;
                lumaDelta += a - b;
            }
            max = Math.max(max, delta);
            if (delta >= 3) {
                changed++;
                if (lumaDelta > 0) brightened++;
                else if (lumaDelta < 0) darkened++;
            }
        }
        check(changed >= 100 && max >= 8, "No observable A/B pixel difference: changed="+changed+", max="+max);
        return Map.of("changedPixels", changed, "brightenedPixels", brightened, "darkenedPixels", darkened,
                "maxChannelDifference", max, "meanAbsoluteDifference", total / (before.length * .75));
    }

    private static void checkRestored() {
        var mc = Minecraft.getInstance();
        check(mc.level.dimension().equals(origin.dimension()), "Original dimension was not restored");
        check(mc.player.position().distanceTo(origin.position()) < .001, "Original position was not restored");
        check(mc.gameMode.getPlayerMode() == origin.gameMode(), "Original game mode was not restored");
        check(mc.player.getAbilities().flying == origin.flying(), "Original flight flag was not restored");
        check(mc.options.hideGui == originalHideGui, "Original HUD visibility was not restored");
    }

    private static void drag(double fraction) {
        var mc = Minecraft.getInstance();
        var screen = (NebulaScreen) mc.screen;
        var slider = screen.slider();
        double left = slider.getX() + 4, y = slider.getY() + slider.getHeight() / 2.0;
        double x = left + fraction * (slider.getWidth() - 8);
        check(screen.mouseClicked(left, y, 0), "Slider click was not routed");
        check(screen.mouseDragged(x, y, 0, x - left, 0), "Slider drag was not routed");
        screen.mouseReleased(x, y, 0);
    }

    private static byte[] pixels() {
        var target = Minecraft.getInstance().getMainRenderTarget();
        return RgbaReadback.copy(new FrameTexture(target.frameBufferId,
                target.getColorTextureId(), target.width, target.height));
    }

    private static void save(String name) throws Exception {
        save(name,true);
    }

    private static void save(String name, boolean expectVisibleCloud) throws Exception {
        var target = Minecraft.getInstance().getMainRenderTarget();
        byte[] rgba = pixels();
        int colored = 0, transparent = 0, highlights = 0;
        long sumX = 0;
        for (int i = 0; i < rgba.length; i += 4) {
            int r = rgba[i] & 255, g = rgba[i+1] & 255, b = rgba[i+2] & 255;
            if (r > 240 && g > 240 && b > 240) highlights++;
            if (Math.max(r, Math.max(g, b)) > 35 && b > r * .8 && i / 4 / target.width > target.height / 4) {
                colored++;
                sumX += i / 4 % target.width;
            }
            if ((rgba[i+3] & 255) != 255) transparent++;
        }
        if (expectVisibleCloud) check(colored > target.width * target.height / 60, "No visible nebula pixels");
        check(transparent == 0, "Output contains transparent pixels");
        double highlightFraction = highlights/(double)(target.width*target.height);
        if (name.equals("lightning") || name.equals("global-flash")) check(highlightFraction < .15,
                "Lightning washed out the cloud structure: white fraction="+highlightFraction);
        var image = new BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < target.height; y++) for (int x = 0; x < target.width; x++) {
            int k = (y * target.width + x) * 4;
            image.setRGB(x, y, 0xFF000000 | (rgba[k] & 255) << 16 | (rgba[k+1] & 255) << 8 | rgba[k+2] & 255);
        }
        Files.createDirectories(directory());
        ImageIO.write(image, "PNG", directory().resolve(name + ".png").toFile());
        REPORT.put(name, Map.of("width", target.width, "height", target.height, "coloredPixels", colored,
                "transparentPixels", transparent, "emitted", NebulaDemo.simulation().emitted(), "centerX", sumX / (double) colored,
                "whiteHighlightFraction",highlightFraction));
    }

    private static void record() throws Exception {
        var target = Minecraft.getInstance().getMainRenderTarget();
        if (video == null) {
            Files.createDirectories(directory());
            videoWidth = target.width; videoHeight = target.height;
            check(NebulaDemo.simulation().frequency() == .25f, "Recording did not restore the sparse default frequency");
            recordEmittedStart = NebulaDemo.simulation().emitted();
            recordTimeStart = NebulaDemo.simulation().time(1);
            REPORT.put("recordFrequencyHz",NebulaDemo.simulation().frequency());
            encoder = new ProcessBuilder("ffmpeg", "-y", "-f", "rawvideo", "-pixel_format", "rgba", "-video_size",
                    videoWidth + "x" + videoHeight, "-framerate", "30", "-i", "pipe:0",
                    "-c:v", "libx264", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p",
                    directory().resolve("nebula-sim.mp4").toString())
                    .redirectError(directory().resolve("ffmpeg.log").toFile()).start();
            video = encoder.getOutputStream();
        }
        check(target.width == videoWidth && target.height == videoHeight, "Video framebuffer changed size");
        video.write(pixels()); recorded++;
    }

    private static void closeVideo() throws Exception {
        if (video == null) return;
        video.close(); video = null;
        check(encoder.waitFor(15, TimeUnit.SECONDS) && encoder.exitValue() == 0, "Video encoder failed");
        encoder = null;
        REPORT.put("videoFrames", recorded);
        REPORT.put("recordSimulatedSeconds",NebulaDemo.simulation().time(1)-recordTimeStart);
        REPORT.put("recordLightningCount",NebulaDemo.simulation().emitted()-recordEmittedStart);
    }

    private static Path directory() { return Path.of(System.getProperty("kasuga.nebula.output", "debug/nebula-sim")); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private static void finish(Throwable failure) {
        if (finished) return;
        finished = true;
        try {
            closeVideo();
            REPORT.put("passed", failure == null);
            if (failure != null) REPORT.put("failure", failure.toString());
            REPORT.put("stage", stage);
            Files.createDirectories(directory());
            Files.writeString(directory().resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
        } catch (Exception outputFailure) { LogUtils.getLogger().error("NEBULA_REPORT_FAILED", outputFailure); }
        NebulaDemo.stop();
        if (failure == null) LogUtils.getLogger().info("NEBULA_SMOKE_PASS {}", REPORT);
        else LogUtils.getLogger().error("NEBULA_SMOKE_FAIL stage=" + stage, failure);
        Minecraft.getInstance().stop();
    }
}
