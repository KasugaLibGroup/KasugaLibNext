package test.kasuga.modelling.demo.nebula;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
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
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(
            Long.getLong("kasuga.nebula.timeoutSeconds", 600L));
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static int stage, frames, recorded;
    private static long zeroCount, generation, stoppedDraws;
    private static boolean finished;
    private static CompletableFuture<Void> reload;
    private static CompletableFuture<Boolean> disconnectReady;
    private static int generationReadySince = -1;
    private static Process encoder;
    private static OutputStream video;
    private static int videoWidth, videoHeight;
    private static NebulaDemo.ReturnState origin;
    private static boolean originalHideGui;
    private static byte[] interactionBaseline;
    private static byte[] warpCruiseBaseline;
    private static byte[] warpCraftBaseline;
    private static byte[] cameraBaseline;
    private static byte[] shipMask;
    private static byte[] shipLightingBaseline;
    private static float[] cameraMatrixBefore;
    private static long recordEmittedStart;
    private static float recordTimeStart;
    private static double recordOpeningDistance;
    private static double gpuPassSumMs, gpuPassMaxMs;
    private static int gpuTimedFrames;

    private NebulaClientSmokeTest() {}

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished) return;
        try {
            // Java 21 on this host is an x86 build under Rosetta; a cold client plus full shader reload
            // can consume most of five minutes before the disconnect phase even begins.
            check(System.nanoTime() - START < TIMEOUT_NANOS, "Client smoke timed out at stage " + stage);
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
                // Async queries can complete together or several frames later. Count actual completed
                // samples, retaining the same minimum and performance threshold without waiting on GL.
                gpuTimedFrames = (int) NebulaDemo.completedGpuQueries();
                gpuPassSumMs = NebulaDemo.gpuPassTotalMs();
                gpuPassMaxMs = NebulaDemo.gpuPassMaxMs();
                if (frames < 40 || gpuTimedFrames < 15 && frames < 120) return;
                NebulaDemo.measureGpuTime(false);
                check(gpuTimedFrames >= 15, "GPU timer query did not collect enough samples: " + gpuTimedFrames);
                double gpuMeanMs = gpuPassSumMs / gpuTimedFrames;
                REPORT.put("gpuPassMeanMs", gpuMeanMs);
                REPORT.put("gpuAsyncSamples", gpuTimedFrames);
                REPORT.put("gpuPassMaxMs", gpuPassMaxMs);
                boolean enforceGpuBudget = Boolean.parseBoolean(
                        System.getProperty("kasuga.nebula.enforceGpuBudget", "true"));
                REPORT.put("gpuBudgetEnforced", enforceGpuBudget);
                if (enforceGpuBudget) check(gpuMeanMs < 45.0,
                        "GPU volume pass remained too slow (" + gpuMeanMs + " ms)");
                check(NebulaDemo.texturesAllocated(), "Prebaked and frame textures were not allocated");
                check(mc.level.dimension().equals(Level.END), "Not in the End");
                check(mc.player.getY() > 500, "Not at the clean observation point");
                check(NebulaDemo.drawCount() > 10, "Graph did not draw");
                check(GL20.glGetProgrami(NebulaDemo.shader().require().getId(), GL20.GL_LINK_STATUS) == GL11.GL_TRUE, "GPU shader did not link");
                int samplerLocation = GL20.glGetUniformLocation(NebulaDemo.shader().require().getId(), "DataSampler");
                check(samplerLocation >= 0 && GL20.glGetUniformi(NebulaDemo.shader().require().getId(), samplerLocation) == 7,
                        "Texture buffer sampler was not assigned its dedicated unit");
                var gbuffer = NebulaDemo.gbuffer();
                check(gbuffer instanceof lib.kasuga.rendering.effect.post.MultiAttachmentRenderTarget mrt
                        && mrt.colorAttachmentCount() == 3 && gbuffer.useDepth
                        && gbuffer.viewWidth == NebulaDemo.renderWidth() && gbuffer.viewHeight == NebulaDemo.renderHeight(),
                        "Graph did not allocate the full-resolution three-attachment G-buffer and depth texture");
                REPORT.put("dataSamplerUnit", 7);
                REPORT.put("graphManagedMrt", true);
                check(NebulaDemo.starshipTriangleCount() >= 1_000,
                        "Standalone OBJ was not parsed into a GPU mesh");
                check(NebulaDemo.starshipPbrSlotsReady(),
                        "Albedo/normal/ORM/emissive PBR slots were not uploaded");
                REPORT.put("starshipObjTriangles", NebulaDemo.starshipTriangleCount());
                REPORT.put("starshipPbrSlots", 4);
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
                check(mc.screen.children().isEmpty(), "Nebula screen still exposes a top-left widget");
                origin = NebulaDemo.originalState();
                originalHideGui = NebulaDemo.originalHideGui();
                save("nebula");
                NebulaDemo.simulation().frequency(0);
                check(NebulaDemo.simulation().frequency() == 0, "Zero frequency state is not available");
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
                if (++frames < 3) return;
                save("global-flash");
                var global = difference(interactionBaseline, pixels());
                check(((Number) global.get("changedPixels")).intValue() >= mc.getMainRenderTarget().width * mc.getMainRenderTarget().height / 4,
                        "Global lightning did not illuminate enough of the frame");
                REPORT.put("globalFlashPixelDifference", global);
                interactionBaseline = null;
                NebulaDemo.flash(-1);
                NebulaDemo.warpTimeProbe(2.0f);
                stage = 21; frames = 0;
            } else if (stage == 21) {
                if (++frames < 3) return;
                warpCruiseBaseline = pixels();
                NebulaDemo.warpTimeProbe(8.55f);
                stage = 22; frames = 0;
            } else if (stage == 22) {
                if (++frames < 3) return;
                save("warp-stretch");
                checkCameraUniform(NebulaDemo.simulation().time(1), 8.55f);
                NebulaDemo.warpTimeProbe(10.2f);
                stage = 23; frames = 0;
            } else if (stage == 23) {
                if (++frames < 3) return;
                save("warp-cruise");
                checkCameraUniform(NebulaDemo.simulation().time(1), 10.2f);
                var warpCruiseDiff = difference(warpCruiseBaseline, pixels());
                int totalPixels = mc.getMainRenderTarget().width * mc.getMainRenderTarget().height;
                check(((Number) warpCruiseDiff.get("changedPixels")).intValue() >= totalPixels / 4,
                        "Warp cruise did not change a large area of the frame: " + warpCruiseDiff);
                REPORT.put("warpCruisePixelDifference", warpCruiseDiff);
                warpCruiseBaseline = null;
                warpCraftBaseline = pixels();
                NebulaDemo.craftVisible(false);
                stage = 24; frames = 0;
            } else if (stage == 24) {
                if (++frames < 3) return;
                var warpCraftDiff = difference(warpCraftBaseline, pixels());
                check(((Number) warpCraftDiff.get("changedPixels")).intValue() >= 100
                                && ((Number) warpCraftDiff.get("maxChannelDifference")).intValue() >= 8,
                        "Starship was not visible during warp cruise: " + warpCraftDiff);
                REPORT.put("warpCraftPixelDifference", warpCraftDiff);
                warpCraftBaseline = null;
                NebulaDemo.craftVisible(true);
                NebulaDemo.warpTimeProbe(2);
                // Keep the acceptance view reproducible even when startup/test rendering takes longer.
                NebulaDemo.cameraTimeProbe(5);
                NebulaDemo.craftDebug(1);
                stage = 30; frames = 0;
            } else if (stage == 30 || stage == 33) {
                if (++frames < 3) return;
                shipMask = pixels();
                save(stage == 30 ? "ship-mask" : "warp-ship-mask", false);
                NebulaDemo.craftDebug(2);
                next();
            } else if (stage == 31 || stage == 34) {
                if (++frames < 3) return;
                save(stage == 31 ? "ship-surface" : "warp-ship-surface", false);
                REPORT.put(stage == 31 ? "shipSurface" : "warpShipSurface", shipMetrics(pixels(), stage == 31));
                NebulaDemo.craftDebug(0);
                next();
            } else if (stage == 32) {
                if (++frames < 3) return;
                save("ship-refined");
                REPORT.put("shipInScene", shipMetrics(pixels(), true));
                NebulaDemo.warpTimeProbe(10.2f);
                NebulaDemo.craftDebug(1);
                // Warp stretch changes the projected hull footprint. Rebuild the debug mask
                // at the same warp state before measuring the refined image.
                stage = 33; frames = 0;
            } else if (stage == 35) {
                if (++frames < 3) return;
                save("warp-ship-refined");
                REPORT.put("warpShipInScene", shipMetrics(pixels(), false));
                NebulaDemo.craftDebug(2);
                next();
            } else if (stage == 36) {
                if (++frames < 3) return;
                shipLightingBaseline = pixels();
                NebulaDemo.core(false);
                next();
            } else if (stage == 37) {
                if (++frames < 3) return;
                save("ship-environment-off", false);
                REPORT.put("shipEnvironmentLighting", shipLightingDifference(shipLightingBaseline, pixels()));
                NebulaDemo.core(true);
                NebulaDemo.indirect(1);
                NebulaDemo.hullTexture(1);
                stage = 40; frames = 0;
            } else if (stage == 40) {
                if (++frames < 3) return;
                save("imagegen-material", false);
                shipLightingBaseline = pixels();
                NebulaDemo.hullTexture(0);
                next();
            } else if (stage == 41) {
                if (++frames < 3) return;
                save("hull-texture-off", false);
                REPORT.put("imagegenTextureDifference", shipLightingDifference(shipLightingBaseline, pixels(), "Imagegen hull texture"));
                NebulaDemo.hullTexture(1);
                NebulaDemo.indirect(0);
                next();
            } else if (stage == 42) {
                if (++frames < 3) return;
                save("diffuse-gi-off", false);
                REPORT.put("diffuseGiDifference", shipLightingDifference(shipLightingBaseline, pixels(), "Diffuse GI approximation"));
                shipLightingBaseline = null;
                NebulaDemo.indirect(1);
                NebulaDemo.craftDebug(0);
                NebulaDemo.smoke(1);
                next();
            } else if (stage == 43) {
                if (++frames < 3) return;
                save("warp-smoke-on");
                cameraBaseline = pixels();
                NebulaDemo.smoke(0);
                next();
            } else if (stage == 44) {
                if (++frames < 3) return;
                save("warp-smoke-off");
                // The baseline is captured with smoke on; reverse the comparison so
                // brightenedPixels describes the smoke contribution itself.
                var smokeDiff = difference(pixels(), cameraBaseline);
                int totalPixels = mc.getMainRenderTarget().width * mc.getMainRenderTarget().height;
                check(((Number) smokeDiff.get("changedPixels")).intValue() >= totalPixels / 20,
                        "Warp smoke did not change a meaningful volume: " + smokeDiff);
                check(((Number) smokeDiff.get("brightenedPixels")).intValue() >= totalPixels / 200,
                        "Warp smoke did not add a readable scattering trail: " + smokeDiff);
                REPORT.put("warpSmokeDifference", smokeDiff);
                cameraBaseline = null;
                shipMask = null;
                NebulaDemo.smoke(1);
                NebulaDemo.warpTimeProbe(2);
                NebulaDemo.cameraTimeProbe(0);
                stage = 25; frames = 0;
            } else if (stage == 25) {
                if (++frames < 3) return;
                save("camera-drift-start");
                checkCameraUniform(0, 2);
                cameraBaseline = pixels();
                cameraMatrixBefore = cameraMatrix();
                NebulaDemo.cameraTimeProbe(5);
                stage = 26; frames = 0;
            } else if (stage == 26) {
                if (++frames < 3) return;
                save("camera-drift-end");
                checkCameraUniform(5, 2);
                var drift = difference(cameraBaseline, pixels());
                check(((Number) drift.get("changedPixels")).intValue()
                                >= mc.getMainRenderTarget().width * mc.getMainRenderTarget().height / 20,
                        "Automatic camera motion did not produce visible parallax: " + drift);
                var after = cameraMatrix();
                double translation = 0;
                for (int i = 12; i < 15; i++) translation += Math.pow(after[i] - cameraMatrixBefore[i], 2);
                check(Math.sqrt(translation) > .5, "Automatic camera did not translate around the frozen ship");
                REPORT.put("automaticCameraPixelDifference", drift);
                REPORT.put("automaticCameraTravel", Math.sqrt(translation));
                REPORT.put("cameraPoseUniformMatches", true);
                cameraBaseline = null; cameraMatrixBefore = null;
                NebulaDemo.cameraTimeProbe(-1);
                NebulaDemo.warpTimeProbe(-1);
                NebulaDemo.simulation().paused(false);
                NebulaDemo.simulation().frequency(NebulaSimulation.MAX_FREQUENCY);
                check(NebulaDemo.simulation().frequency() == 2, "Maximum frequency state is not two Hz");
                stage = 2; frames = 0;
            } else if (stage == 2) {
                frames++;
                if (frames > 15 && NebulaDemo.renderedPeak() > .65f) {
                    save("lightning");
                    NebulaDemo.simulation().frequency(NebulaSimulation.DEFAULT_FREQUENCY);
                    // Start the presentation at the canyon entrance, independently of how long acceptance checks took.
                    NebulaDemo.simulation().reset();
                    NebulaDemo.capture(true);
                    REPORT.put("frequencyStatePreserved", NebulaDemo.simulation().frequency() == .25f);
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
                check(NebulaDemo.starshipAssetGeneration() == NebulaDemo.shader().generation(), "OBJ/PBR generation was not reloaded");
                REPORT.put("starshipReloadGeneration", NebulaDemo.starshipAssetGeneration());
                NebulaDemo.simulation().paused(true);
                // Exercise actual framebuffer resize and Screen re-init, not just a changed uniform.
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 800, 500);
                next();
            } else if (stage == 5) {
                if (++frames < 20) return;
                save("resized");
                check(mc.screen instanceof NebulaScreen, "Resize lost the Nebula control screen");
                check(mc.screen.children().isEmpty(), "Resize recreated a top-left widget");
                check(NebulaDemo.simulation().frequency() == .25f, "Resize changed the preserved default frequency");
                REPORT.put("resizeAndUiHidden", true);
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
                check(!NebulaDemo.texturesAllocated() && NebulaDemo.lastTextureDeleteVerified(),
                        "Escape did not release custom GL textures");
                checkRestored();
                REPORT.put("escapeRestoresPlayer", true);
                check(NebulaDemo.start(), "Restart failed");
                next();
            } else if (stage == 8) {
                if (!NebulaDemo.active() || ++frames < 15) return;
                check(NebulaDemo.texturesAllocated(), "Restart did not recreate custom GL textures");
                NebulaDemo.stop(); NebulaDemo.stop();
                next();
            } else if (stage == 9) {
                if (++frames < 20) return;
                check(NebulaDemo.idle(), "Repeated stop is not idempotent");
                check(!NebulaDemo.texturesAllocated() && NebulaDemo.lastTextureDeleteVerified(),
                        "Repeated stop left custom GL textures allocated");
                REPORT.put("texturesReleasedOnStop", true);
                check(!NebulaDemo.graphTargetsAllocated(), "Stopped graph retained its managed targets");
                REPORT.put("graphTargetsReleasedOnStop", true);
                check(NebulaDemo.lastQueryDeleteVerified(), "Repeated stop left GPU timer queries allocated");
                REPORT.put("timerQueriesReleasedOnStop", true);
                checkRestored();
                REPORT.put("restartAndIdempotentStop", true);
                check(NebulaDemo.start(), "Start before disconnect failed");
                next();
            } else if (stage == 10) {
                if (!NebulaDemo.active() || ++frames < 15) return;
                if (!generationSettled(mc)) return;
                REPORT.put("disconnectGenerationSettled", true);
                stage = 11;
                mc.level.disconnect();
                mc.disconnect();
            }
        } catch (Throwable problem) { finish(problem); }
    }

    private static void next() { stage++; frames = 0; }

    /** Do not race vanilla's shutdown unload loop against a just-teleported player's generation tasks. */
    private static boolean generationSettled(Minecraft mc) {
        if (disconnectReady != null) {
            if (!disconnectReady.isDone()) return false;
            boolean ready = disconnectReady.join();
            disconnectReady = null;
            if (ready) return true;
        }
        var server = mc.getSingleplayerServer();
        check(server != null, "Integrated server vanished before disconnect");
        disconnectReady = CompletableFuture.supplyAsync(() -> {
            try {
                // Test-only inspection: getChunks is protected; no production accessor/mixin is needed.
                var getChunks = ChunkMap.class.getDeclaredMethod("getChunks");
                getChunks.setAccessible(true);
                boolean ready = true;
                for (var level : server.getAllLevels()) {
                    var source = level.getChunkSource();
                    @SuppressWarnings("unchecked")
                    var holders = (Iterable<ChunkHolder>) getChunks.invoke(source.chunkMap);
                    for (var holder : holders) ready &= holder.isReadyForSaving();
                    ready &= source.getPendingTasksCount() == 0 && !source.getLightEngine().hasLightWork();
                }
                if (!ready) generationReadySince = -1;
                else if (generationReadySince < 0) generationReadySince = server.getTickCount();
                return ready && server.getTickCount() - generationReadySince >= 20;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot inspect chunk generation before disconnect", failure);
            }
        }, server);
        return false;
    }

    private static float[] cameraMatrix() {
        int program = NebulaDemo.shader().require().getId();
        var matrix = new float[16];
        GL20.glGetUniformfv(program,GL20.glGetUniformLocation(program,"InverseView"),matrix);
        return matrix;
    }

    private static void checkCameraUniform(float cameraTime, float warpTime) {
        var mc = Minecraft.getInstance();
        var target = mc.getMainRenderTarget();
        var pose = NebulaFlight.sample(NebulaDemo.simulation().time(1));
        var camera = NebulaFlight.camera(pose, NebulaFlight.sampleWarp(warpTime), cameraTime,
                target.width, target.height, 0, 0);
        float[] expected = NebulaFlight.inverseView(camera, new org.joml.Matrix4f()).get(new float[16]);
        float[] actual = cameraMatrix();
        for (int i = 0; i < 16; i++) check(Math.abs(expected[i] - actual[i]) < .0001f,
                "GPU camera discarded part of the sampled pose at matrix element " + i);
    }

    private static Map<String, Object> shipMetrics(byte[] rgba, boolean cruise) {
        var target = Minecraft.getInstance().getMainRenderTarget();
        int w = target.width, h = target.height;
        int count = 0, highlights = 0, minX = w, minY = h, maxX = 0, maxY = 0;
        double luminance = 0;
        // The exact white SDF mask separates the entire hull from cloud/particles and the darker native GUI.
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int k = (y * w + x) * 4;
            if ((shipMask[k] & 255) < 250 || (shipMask[k + 1] & 255) < 250 || (shipMask[k + 2] & 255) < 250) continue;
            int r = rgba[k] & 255, g = rgba[k + 1] & 255, b = rgba[k + 2] & 255;
            luminance += r * .2126 + g * .7152 + b * .0722;
            if (Math.max(r, Math.max(g, b)) >= 235) highlights++;
            count++;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        double coverage = count / (double) (w * h);
        check(coverage >= (cruise ? .01 : .004), "Close shot still leaves the ship too small: " + coverage);
        double mean = luminance / count, highlightFraction = highlights / (double) count;
        check(mean > (cruise ? 22 : 20) && mean < 100, "Ship is invisible or too bright: mean luma=" + mean);
        check(highlightFraction < .05, "Ship has too many local highlights: " + highlightFraction);
        check(minX > w * .2 && maxX < w * .8 && minY > h * .25 && maxY < h * .85,
                "Ship silhouette escaped the central frame");
        return Map.of("pixels", count, "coverage", coverage, "meanLuminance", mean,
                "highlightFraction", highlightFraction, "bounds", java.util.List.of(minX, minY, maxX, maxY));
    }

    private static Map<String, Object> shipLightingDifference(byte[] on, byte[] off) {
        return shipLightingDifference(on, off, "Nebula source");
    }

    private static Map<String, Object> shipLightingDifference(byte[] on, byte[] off, String effect) {
        int count = 0, changed = 0;
        double onLuma = 0, offLuma = 0;
        for (int k = 0; k < shipMask.length; k += 4) {
            if ((shipMask[k] & 255) < 250 || (shipMask[k + 1] & 255) < 250 || (shipMask[k + 2] & 255) < 250) continue;
            count++;
            int max = 0;
            for (int c = 0; c < 3; c++) max = Math.max(max, Math.abs((on[k + c] & 255) - (off[k + c] & 255)));
            if (max >= 3) changed++;
            onLuma += (on[k] & 255) * .2126 + (on[k + 1] & 255) * .7152 + (on[k + 2] & 255) * .0722;
            offLuma += (off[k] & 255) * .2126 + (off[k + 1] & 255) * .7152 + (off[k + 2] & 255) * .0722;
        }
        check(count > 0 && changed >= count / 10, effect + " did not change the actual hull");
        if (!effect.equals("Imagegen hull texture"))
            check(onLuma > offLuma, effect + " did not illuminate the isolated hull");
        return Map.of("hullPixels", count, "changedPixels", changed,
                "sourceOnMeanLuminance", onLuma / count, "sourceOffMeanLuminance", offLuma / count);
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
        if (name.equals("warp-stretch") || name.equals("warp-cruise")) check(highlightFraction < .18,
                "Warp visual washed out the screen: white fraction=" + highlightFraction);
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
            REPORT.put("recordFps", captureFps());
            encoder = new ProcessBuilder("ffmpeg", "-y", "-f", "rawvideo", "-pixel_format", "rgba", "-video_size",
                    videoWidth + "x" + videoHeight, "-framerate", Integer.toString(captureFps()), "-i", "pipe:0",
                    "-c:v", "libx264", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p",
                    directory().resolve("nebula-sim.mp4").toString())
                    .redirectError(directory().resolve("ffmpeg.log").toFile()).start();
            video = encoder.getOutputStream();
        }
        check(target.width == videoWidth && target.height == videoHeight, "Video framebuffer changed size");
        video.write(pixels()); recorded++;
        int frameTarget = Integer.getInteger("kasuga.nebula.recordFrames", 0);
        int progressStep = Math.max(1, frameTarget / 100);
        if (frameTarget > 0 && (recorded == 1 || recorded == frameTarget || recorded % progressStep == 0)) {
            int percent = recorded == frameTarget ? 100 : Math.max(1, recorded * 100 / frameTarget);
            LogUtils.getLogger().info("NEBULA_RENDER_PROGRESS {}% ({}/{})", percent, recorded, frameTarget);
        }
        if (recorded == 1) {
            save("opening-shot");
            recordOpeningDistance = renderedCameraDistance();
            REPORT.put("openingCameraDistance", recordOpeningDistance);
        }
        if (recorded == Integer.getInteger("kasuga.nebula.recordFrames", 0)) {
            save("ending-shot");
            double distance = renderedCameraDistance();
            REPORT.put("endingCameraDistance", distance);
            if (recorded >= 450) {
                check(distance >= recordOpeningDistance * 3, "Recorded ending did not pull back from the opening");
                REPORT.put("recordedNearToWide", true);
            }
        }
    }

    private static double renderedCameraDistance() {
        int program = NebulaDemo.shader().require().getId();
        float[] eye = new float[3], craft = new float[3];
        GL20.glGetUniformfv(program, GL20.glGetUniformLocation(program, "Eye"), eye);
        GL20.glGetUniformfv(program, GL20.glGetUniformLocation(program, "CraftPosition"), craft);
        double sum = 0;
        for (int i = 0; i < 3; i++) sum += Math.pow(eye[i] - craft[i], 2);
        return Math.sqrt(sum);
    }

    private static int captureFps() {
        int fps = Integer.getInteger("kasuga.nebula.captureFps", 30);
        check(fps > 0, "Capture frame rate must be positive");
        return fps;
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
            if (recorded > 0) REPORT.put("renderProgressPercent", 100);
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
