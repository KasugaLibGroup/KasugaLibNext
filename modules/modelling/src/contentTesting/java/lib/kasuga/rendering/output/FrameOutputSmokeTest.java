package lib.kasuga.rendering.output;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL30;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in, menu-only integration test; use an isolated kasugaClientDirectory. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class FrameOutputSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testFrameOutput");
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static FrameOutputRouter<FrameTexture>.Registration capture, observer;
    private static FrameTexture beforeResize, lastFrame;
    private static int frames, mirrors, offscreens, stage;
    private static final long START = System.nanoTime();
    private static boolean finished;

    @SubscribeEvent
    public static void beforeFrame(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        Minecraft mc = Minecraft.getInstance();
        if (System.nanoTime() - START > 120_000_000_000L) {
            REPORT.put("stage", stage);
            REPORT.put("screen", mc.screen == null ? "none" : mc.screen.getClass().getName());
            REPORT.put("overlay", mc.getOverlay() == null ? "none" : mc.getOverlay().getClass().getName());
            REPORT.put("mirrorFrames", mirrors);
            finish(new IllegalStateException("Frame output integration timed out")); return;
        }
        if (mc.screen == null || mc.getOverlay() != null || mc.gameRenderer.blitShader == null) return;
        frames++;
        if (stage == 0) {
            capture = MinecraftFrameOutputs.open(FrameOutputMode.MIRROR, FrameOutputSmokeTest::consume);
            observer = MinecraftFrameOutputs.open(FrameOutputMode.MIRROR, frame -> {
                try {
                    if (!GL11.glIsTexture(frame.resource().textureId())) {
                        throw new IllegalStateException("Subscribers did not share a live frame resource");
                    }
                    if (capture.latest().isPresent() && capture.latest().orElseThrow() != frame) {
                        throw new IllegalStateException("Subscribers did not share the same completed frame");
                    }
                } catch (Exception failure) { finish(failure); }
            });
            LogUtils.getLogger().info("Frame-output integration starts after loading: {}", mc.screen.getClass().getName());
            stage = 1;
        } else if (stage == 4) {
            // The previous callback closed its output. It must be released before
            // the next frame, and normal presentation can resume without a route.
            if (!capture.isClosed() || capture.latest().isPresent() || GL11.glIsTexture(lastFrame.textureId())
                    || GL30.glIsFramebuffer(lastFrame.framebufferId())) {
                finish(new IllegalStateException("Closed capture retains live resources")); return;
            }
            capture = MinecraftFrameOutputs.open(FrameOutputMode.OFFSCREEN_ONLY, frame -> {
                lastFrame = frame.resource();
                stage = 5;
                throw new IllegalStateException("Intentional output-consumer failure for fallback test");
            });
        } else if (stage == 5) {
            if (!capture.isClosed() || GL11.glIsTexture(lastFrame.textureId())) {
                finish(new IllegalStateException("Failed output was not detached and released")); return;
            }
            REPORT.put("consumerFailureDetached", true);
            stage = 6;
        } else if (stage == 6) {
            // One complete ordinary frame has rendered after failure.
            REPORT.put("mirrorFrames", mirrors);
            REPORT.put("offscreenFrames", offscreens);
            REPORT.put("resized", true);
            REPORT.put("guiRgbIdentical", true);
            finish(null);
        }
    }

    private static void consume(OutputFrame<FrameTexture> frame) {
        try {
            if (observer.latest().orElseThrow() != frame) {
                throw new IllegalStateException("Peer latest handle was not refreshed before delivery");
            }
            compareCompletedGui(frame, (stage == 1 && mirrors == 0) || (stage == 3 && offscreens == 0));
            lastFrame = frame.resource();
            if (stage == 1 && ++mirrors == 2) {
                beforeResize = frame.resource();
                var window = Minecraft.getInstance().getWindow();
                int[] logicalWidth = {0}, logicalHeight = {0};
                GLFW.glfwGetWindowSize(window.getWindow(), logicalWidth, logicalHeight);
                window.setWindowed(logicalWidth[0] + 106, logicalHeight[0] + 40);
                stage = 2;
            } else if (stage == 2 && (frame.width() != beforeResize.width() || frame.height() != beforeResize.height())) {
                if (GL11.glIsTexture(beforeResize.textureId()) || GL30.glIsFramebuffer(beforeResize.framebufferId())) {
                    throw new IllegalStateException("Resize did not retire previous FBO");
                }
                REPORT.put("outputWidth", frame.width());
                REPORT.put("outputHeight", frame.height());
                capture.close();
                capture = MinecraftFrameOutputs.open(FrameOutputMode.OFFSCREEN_ONLY, FrameOutputSmokeTest::consume);
                stage = 3;
            } else if (stage == 3 && ++offscreens == 3) {
                capture.close();
                if (!GL11.glIsTexture(frame.resource().textureId())) {
                    throw new IllegalStateException("Closing primary retired observer's shared texture");
                }
                observer.close();
                if (!GL11.glIsTexture(frame.resource().textureId())) {
                    throw new IllegalStateException("Closing last subscriber retired the active borrowed frame");
                }
                REPORT.put("sharedReadonlyFrame", true);
                REPORT.put("lastSubscriberRetiresTarget", true);
                stage = 4;
            }
        } catch (Exception failure) { finish(failure); }
    }

    private static void compareCompletedGui(OutputFrame<FrameTexture> frame, boolean save) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try (NativeImage expected = Screenshot.takeScreenshot(mc.getMainRenderTarget());
             NativeImage actual = new NativeImage(frame.width(), frame.height(), false)) {
            RenderSystem.bindTexture(frame.resource().textureId());
            actual.downloadTexture(0, true);
            actual.flipY();
            if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) {
                throw new IllegalStateException("Completed screen and output dimensions differ");
            }
            for (int y = 0; y < frame.height(); y++) {
                for (int x = 0; x < frame.width(); x++) {
                    if ((expected.getPixelRGBA(x, y) & 0xFFFFFF) != (actual.getPixelRGBA(x, y) & 0xFFFFFF)) {
                        throw new IllegalStateException("Final GUI RGB differs at " + x + "," + y);
                    }
                }
            }
            if (save) {
                var directory = mc.gameDirectory.toPath().resolve("debug/frame-output");
                Files.createDirectories(directory);
                expected.writeToFile(directory.resolve("source-" + stage + ".png"));
                actual.writeToFile(directory.resolve("output-" + stage + ".png"));
            }
        } finally { RenderSystem.bindTexture(previousTexture); }
    }

    private static void finish(Exception failure) {
        if (finished) return;
        finished = true;
        REPORT.put("passed", failure == null);
        if (failure != null) REPORT.put("failure", failure.toString());
        try {
            var file = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/frame-output/report.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure == null) LogUtils.getLogger().info("FRAME_OUTPUT_SMOKE_PASS {}", file);
            else LogUtils.getLogger().error("FRAME_OUTPUT_SMOKE_FAIL", failure);
        } catch (Exception writeFailure) { LogUtils.getLogger().error("Cannot save frame-output test report", writeFailure); }
        Minecraft.getInstance().stop();
    }
}
