package lib.kasuga.rendering.output;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FogType;
import lib.kasuga.mixins.modelling.WorldViewFogAccessor;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.mc.iris.CameraIrisSession;
import net.irisshaders.iris.Iris;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in quick-play test. Run only in a disposable directory containing a copied world. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class WorldViewsSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testWorldViews");
    private static final boolean IRIS = Boolean.getBoolean("kasuga.testCameraIris");
    private static final Map<String, Object> IRIS_SESSIONS = new LinkedHashMap<>();
    private static Object mainIrisManager, mainIrisCaptured, mainRenderer, mainModels, mainTextures;
    private static final Map<String, Object> ENTITY_RENDERERS = new LinkedHashMap<>();
    private static final java.util.Set<String> PACKS_CHECKED = new java.util.HashSet<>();
    private static final long START = System.nanoTime();
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static MinecraftWorldViews.Registration front, back, water, bad;
    private static FrameOutputRouter<FrameTexture>.Registration frontOutput, backOutput, waterOutput, mainOutput, badOutput;
    private static WorldCameraView frontView, backView, waterView;
    private static FrameTexture oldFront, lastFront, lastBack;
    private static int warmup, frame, frontFrames, backFrames, stage, lastFrontFrame, lastBackFrame;
    private static com.mojang.blaze3d.pipeline.RenderTarget closedCameraTarget;
    private static int mainWaterFogTarget;
    private static int samplesThisFrame, samplesTotal;
    private static CameraHandle managed, distant;
    private static WorldCameraView distantView;
    private static net.minecraft.client.multiplayer.ClientLevel mainLevel;
    private static boolean distantReady, entityResetRequested, entityResetVerified;
    private static java.util.concurrent.CompletableFuture<Integer> distantEntity;
    private static int distantWarmup;
    private static WorldCameraView managedView;
    private static int managedFrames, managedPausedAt;
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "models/fsm/test_cube.obj");
    private static final ResourceLocation INSTANCE = ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "world_view_smoke_cube");
    private static long frontHash, backHash;
    private static boolean frontStage, backStage, mainStage, finished;
    private static Vec3 mainPosition;
    private static Object sharedToken;
    private static com.mojang.blaze3d.pipeline.RenderTarget originalTarget;

    @SubscribeEvent
    public static void beforeFrame(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        Minecraft mc = Minecraft.getInstance();
        if (System.nanoTime() - START > 180_000_000_000L) {
            finish(new IllegalStateException("Multi-view test timed out at stage " + stage)); return;
        }
        if (mc.level == null || mc.player == null || mc.getOverlay() != null) return;
        mc.options.pauseOnLostFocus = false;
        if (++warmup < 60) return;
        frame++;
        samplesThisFrame = 0;
        if (stage == 0) {
            mc.setScreen(null);
            mc.options.bobView().set(false);
            Vec3 eye = mc.player.getEyePosition();
            Vec3 modelPosition = eye.add(mc.player.getLookAngle().scale(6));
            var pipeline = PipelineRegistry.obj();
            var model = pipeline.createInstance(MODEL, INSTANCE, new Transform().translate(
                    (float) modelPosition.x, (float) modelPosition.y, (float) modelPosition.z), null, null);
            if (model == null) { finish(new IllegalStateException("Missing built-in cube model fixture")); return; }
            model.setPoseDriver(new PoseDriver() {
                @Override public void sample(float partialTick) { samplesThisFrame++; samplesTotal++; }
            });
            pipeline.addToRenderer(MODEL, INSTANCE, "mc_bridge", "mc_backend");
            frontView = new WorldCameraView(eye.x, eye.y + 3, eye.z, mc.player.getYRot(), 20, 0, 65, 320, 180);
            backView = new WorldCameraView(eye.x, eye.y + 3, eye.z, mc.player.getYRot() + 180, 20, 0, 90, 256, 256);
            // Find nearby loaded water independently of the saved player's heading.
            BlockPos waterBlock = null;
            BlockPos center = BlockPos.containing(eye);
            for (int radius = 2; radius <= 32 && waterBlock == null; radius++) {
                for (int dx = -radius; dx <= radius && waterBlock == null; dx++)
                    for (int dz = -radius; dz <= radius && waterBlock == null; dz++) {
                        if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                        for (int dy = -1; dy >= -6; dy--) {
                            BlockPos candidate = center.offset(dx, dy, dz);
                            if (mc.level.hasChunkAt(candidate) && mc.level.getFluidState(candidate).is(FluidTags.WATER)) {
                                waterBlock = candidate; break;
                            }
                        }
                    }
            }
            if (waterBlock == null) { finish(new IllegalStateException("Fixture needs nearby loaded water")); return; }
            waterView = new WorldCameraView(waterBlock.getX() + 0.5, waterBlock.getY() + 0.5, waterBlock.getZ() + 0.5,
                    backView.yaw(), 0, 0, 70, 240, 180);
            originalTarget = mc.getMainRenderTarget();
            try {
                if (IRIS) {
                    var mainPack = shaderFixture("camera-smoke-main", "0.3, 1.0, 0.3");
                    loadMainShaderFixture(mainPack);
                    mainIrisManager = Iris.getPipelineManager();
                    mainIrisCaptured = net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE;
                    mainModels = mc.getModelManager(); mainTextures = mc.getTextureManager();
                    front = MinecraftWorldViews.register("test:front", () -> frontView,
                            new CameraRenderSettings(4, CameraRenderSettings.Quality.FAST,
                                    CameraRenderSettings.Shader.pack(shaderFixture("camera-smoke-red", "1.0, 0.1, 0.1")),
                                    java.util.List.of(resourceFixture("camera-smoke-red", 0xff3030f0)), 0, false));
                    back = MinecraftWorldViews.register("test:back", () -> backView,
                            new CameraRenderSettings(6, CameraRenderSettings.Quality.FANCY,
                                    CameraRenderSettings.Shader.pack(shaderFixture("camera-smoke-blue", "0.1, 0.1, 1.0")),
                                    java.util.List.of(resourceFixture("camera-smoke-blue", 0xfff03030)), 0, true));
                } else {
                    front = MinecraftWorldViews.register("test:front", () -> frontView);
                    back = MinecraftWorldViews.register("test:back", () -> backView);
                }
                mainRenderer = mc.levelRenderer;
            } catch (Exception failure) { finish(failure); return; }
            water = MinecraftWorldViews.register("test:water", () -> waterView);
            waterOutput = MinecraftFrameOutputs.open("test:water", FrameOutputMode.OFFSCREEN_ONLY, output -> {
                if (finished) return;
                try {
                    if (MinecraftWorldViews.currentCamera().getFluidInCamera() != FogType.WATER
                            || WorldViewFogAccessor.kasuga$changedAt() < 0)
                        throw new IllegalStateException("Underwater camera has no water fog history");
                    if (frame == 12) save(output, "underwater");
                } catch (Exception failure) { finish(failure); }
            });
            bad = MinecraftWorldViews.register("test:bad", () -> { throw new IllegalStateException("Intentional camera-provider failure"); });
            badOutput = MinecraftFrameOutputs.open("test:bad", FrameOutputMode.MIRROR,
                    output -> finish(new IllegalStateException("Broken camera produced a frame")));
            frontOutput = MinecraftFrameOutputs.open("test:front", FrameOutputMode.OFFSCREEN_ONLY, WorldViewsSmokeTest::front);
            backOutput = MinecraftFrameOutputs.open("test:back", FrameOutputMode.OFFSCREEN_ONLY, WorldViewsSmokeTest::back);
            mainOutput = MinecraftFrameOutputs.open(FrameOutputMode.MIRROR, WorldViewsSmokeTest::main);
            managedView = frontView;
            managed = MinecraftCameras.create("test:managed", managedView, output -> {
                if (finished) return;
                try {
                    managedFrames++;
                    if (managedFrames == 2) {
                        managedView = backView;
                        managed.updatePose(managedView);
                    } else if (managedFrames == 3) {
                        managed.pause(); managedPausedAt = frame;
                    } else if (managedFrames == 5) managed.close();
                } catch (Exception failure) { finish(failure); }
            });
            mainLevel = mc.level;
            distantView = new WorldCameraView(eye.x + 1024, 95, eye.z, frontView.yaw(), 30, 0, 70, 320, 180);
            distant = MinecraftCameras.create("test:distant", () -> distantView,
                    new CameraRenderSettings(2, CameraRenderSettings.Quality.FAST, CameraRenderSettings.Shader.disabled()), output -> {
                        try {
                            var level = Minecraft.getInstance().level;
                            int x = net.minecraft.core.SectionPos.blockToSectionCoord(distantView.x());
                            int z = net.minecraft.core.SectionPos.blockToSectionCoord(distantView.z());
                            if (mainLevel.getChunkSource().getChunk(x, z, false) != null)
                                throw new IllegalStateException("Camera subscription polluted main chunk cache");
                            if (level == mainLevel) throw new IllegalStateException("Distant camera reused main world");
                            if (level.getChunkSource().getChunk(x, z, false) != null) {
                                int height = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                                        net.minecraft.util.Mth.floor(distantView.x()), net.minecraft.util.Mth.floor(distantView.z()));
                                if (distantView.y() != height + 12) {
                                    distantView = new WorldCameraView(distantView.x(), height + 12, distantView.z(), distantView.yaw(),
                                            55, 0, 70, 320, 180);
                                    distant.updatePose(distantView); distantWarmup = 0;
                                }
                            }
                            if (level.getChunkSource().getChunk(x, z, false) != null && distantEntity == null) {
                                distantEntity = Minecraft.getInstance().getSingleplayerServer().submit(() -> {
                                    var serverLevel = Minecraft.getInstance().getSingleplayerServer().overworld();
                                    int y = serverLevel.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                                            net.minecraft.util.Mth.floor(distantView.x()), net.minecraft.util.Mth.floor(distantView.z()));
                                    var entity = new net.minecraft.world.entity.decoration.ArmorStand(serverLevel, distantView.x(), y + 1, distantView.z());
                                    entity.setInvisible(true); serverLevel.addFreshEntity(entity); return entity.getId();
                                });
                            }
                            if (distantEntity != null && distantEntity.isDone()) {
                                int entityId = distantEntity.join(); var entity = level.getEntity(entityId);
                                if (entity != null) {
                                    if (mainLevel.getEntity(entityId) != null) throw new IllegalStateException("Camera entity polluted main world");
                                    if (entity.isInvisible() && !entityResetRequested) {
                                        entityResetRequested = true;
                                        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
                                            var remote = Minecraft.getInstance().getSingleplayerServer().overworld().getEntity(entityId);
                                            if (remote != null) remote.setInvisible(false);
                                        });
                                    } else if (entityResetRequested && !entity.isInvisible()) entityResetVerified = true;
                                }
                            }
                            if (level.getChunkSource().getChunk(x, z, false) != null
                                    && level.getChunkSource().getLoadedChunksCount() >= 25 && entityResetVerified && ++distantWarmup >= 40) {
                                if (!distantReady) save(output, "distant");
                                distantReady = true;
                            }
                        } catch (Exception failure) { finish(failure); }
                    });
            stage = 1;
        } else if (stage == 2) {
            oldFront = lastFront;
            frontView = new WorldCameraView(frontView.x(), frontView.y(), frontView.z(), frontView.yaw(),
                    frontView.pitch(), 12, 50, 480, 270);
            stage = 3;
        } else if (stage == 4) {
            front.setEnabled(false);
            stage = 5;
        } else if (stage == 6) {
            try { front.close(); back.close(); water.close(); frontOutput.close(); backOutput.close(); waterOutput.close(); badOutput.close(); }
            catch (Exception failure) { finish(failure); return; }
            // Front is retired here, before any allocation can recycle its
            // texture name. Back was closed a frame earlier; its old numeric
            // name may legitimately belong to a model target by now.
            if (GL11.glIsTexture(lastFront.textureId()) || !backOutput.isClosed() || backOutput.latest().isPresent()) {
                finish(new IllegalStateException("Closed outputs retain textures")); return;
            }
            PipelineRegistry.obj().removeInstance(MODEL, INSTANCE);
            if (!bad.isClosed() || closedCameraTarget.getColorTextureId() != -1 || closedCameraTarget.frameBufferId != -1) {
                finish(new IllegalStateException("Camera close/provider failure retained resources")); return;
            }
            stage = 7;
        } else if (stage == 8) {
            if (!distantReady) {
                if (distant.state() == CameraState.FAILED || distant.state() == CameraState.CLOSED)
                    finish(new IllegalStateException("Distant camera failed", distant.failure().orElse(null)));
                return;
            }
            try { distant.close(); } catch (Exception failure) { finish(failure); return; }
            REPORT.put("distantChunksLoadedWithoutMainCache", true);
            REPORT.put("independentDistantEntitiesAndDefaultMetadataReset", entityResetVerified);
            if (distantEntity != null && distantEntity.isDone()) Minecraft.getInstance().getSingleplayerServer().execute(() -> {
                var entity = Minecraft.getInstance().getSingleplayerServer().overworld().getEntity(distantEntity.join());
                if (entity != null) entity.discard();
            });
            if (frontHash == backHash) { finish(new IllegalStateException("Opposite world views produced the same image")); return; }
            if (managedFrames != 5 || managed.state() != CameraState.CLOSED) {
                finish(new IllegalStateException("Owned camera lifecycle did not complete")); return;
            }
            REPORT.put("frontFrames", frontFrames); REPORT.put("backFrames", backFrames);
            REPORT.put("distinctWorldImages", frontHash != backHash);
            REPORT.put("perViewStagesAndCamera", true); REPORT.put("sharedFrameToken", true);
            REPORT.put("resizeAndRelease", true); REPORT.put("disabledViewSkipped", true);
            REPORT.put("mainViewRestored", true); REPORT.put("finalMainRgbIdentical", true);
            REPORT.put("callbackCloseAndProviderFailure", true);
            REPORT.put("underwaterFogHistoryIsolated", true);
            REPORT.put("modelSampledOncePerHostFrame", samplesTotal >= 12);
            REPORT.put("ownedCameraLifecycle", true);
            finish(null);
        }
        if (managed != null && managed.state() == CameraState.PAUSED && frame >= managedPausedAt + 2) managed.resume();
        frontStage = backStage = mainStage = false;
        sharedToken = null;
        mainWaterFogTarget = WorldViewFogAccessor.kasuga$target();
    }

    @SubscribeEvent
    public static void levelStage(RenderLevelStageEvent event) {
        if (!ENABLED || finished || stage == 0 || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        try {
            String id = MinecraftWorldViews.currentViewId();
            if (IRIS) {
                var session = CameraIrisSession.current();
                if (id.equals(MinecraftFrameOutputs.MAIN_VIEW)) {
                    if (session != null || Iris.getPipelineManager() != mainIrisManager
                            || net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE != mainIrisCaptured
                            || Minecraft.getInstance().levelRenderer != mainRenderer
                            || Minecraft.getInstance().getModelManager() != mainModels || Minecraft.getInstance().getTextureManager() != mainTextures)
                        throw new IllegalStateException("Main Iris/render session not restored");
                } else {
                    if (session == null || Iris.getPipelineManager() == mainIrisManager
                            || net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE == mainIrisCaptured
                            || Minecraft.getInstance().levelRenderer == mainRenderer)
                        throw new IllegalStateException("Camera shares main Iris/render state");
                    var manager = IRIS_SESSIONS.putIfAbsent(id, Iris.getPipelineManager());
                    if (manager != null && manager != Iris.getPipelineManager())
                        throw new IllegalStateException("Camera pipeline recreated between frames");
                    for (var entry : IRIS_SESSIONS.entrySet())
                        if (!entry.getKey().equals(id) && entry.getValue() == Iris.getPipelineManager())
                            throw new IllegalStateException("Cameras share Iris pipeline manager");
                    if (Minecraft.getInstance().getModelManager() == mainModels || Minecraft.getInstance().getTextureManager() == mainTextures)
                        throw new IllegalStateException("Camera shares main mutable asset caches");
                    var dispatcher = ENTITY_RENDERERS.putIfAbsent(id, Minecraft.getInstance().getEntityRenderDispatcher());
                    if (dispatcher != null && dispatcher != Minecraft.getInstance().getEntityRenderDispatcher())
                        throw new IllegalStateException("Camera entity dispatcher recreated");
                    for (var entry : ENTITY_RENDERERS.entrySet()) if (!entry.getKey().equals(id)
                            && entry.getValue() == Minecraft.getInstance().getEntityRenderDispatcher())
                        throw new IllegalStateException("Camera entity dispatchers shared");
                    if ((id.equals("test:front") || id.equals("test:back")) && PACKS_CHECKED.add(id)) {
                        int expected = id.equals("test:front") ? 0xff3030f0 : 0xfff03030;
                        int actual = atlasSandPixel();
                        if (actual != expected) throw new IllegalStateException("Camera resource-pack atlas pixel differs: "
                                + Integer.toHexString(actual) + " expected " + Integer.toHexString(expected));
                        REPORT.put("independentResourcePackAtlases", PACKS_CHECKED.size() == 2);
                    }
                    boolean expectedShaders = id.equals("test:front") || id.equals("test:back");
                    if (Iris.isPackInUseQuick() != expectedShaders)
                        throw new IllegalStateException("Camera shader enable state differs");
                    if (expectedShaders && !Iris.getCurrentPackName().equals(id.equals("test:front")
                            ? "camera-smoke-red" : "camera-smoke-blue"))
                        throw new IllegalStateException("Camera shader pack differs");
                    if (Minecraft.getInstance().options.getEffectiveRenderDistance()
                            != MinecraftWorldViews.currentSettings().renderDistance())
                        throw new IllegalStateException("Camera distance differs");
                }
                REPORT.put("independentIrisSessions", true);
            }
            Object token = MinecraftWorldViews.currentFrameToken();
            if (stage < 7) {
                if (token == null) throw new IllegalStateException("Missing group frame token");
                if (sharedToken == null) sharedToken = token;
                else if (sharedToken != token) throw new IllegalStateException("Views advanced the frame token");
            }
            if (id.equals("test:front")) { verifyView(event, frontView); frontStage = true; }
            else if (id.equals("test:back")) { verifyView(event, backView); backStage = true; }
            else if (id.equals("test:water")) { verifyView(event, waterView); }
            else if (id.equals("test:distant")) { verifyView(event, distantView); }
            else if (id.equals("test:managed")) { verifyView(event, managedView); }
            else {
                if (WorldViewFogAccessor.kasuga$target() != mainWaterFogTarget)
                    throw new IllegalStateException("Underwater camera overwrote main fog transition history");
                if (Minecraft.getInstance().getMainRenderTarget() != originalTarget)
                    throw new IllegalStateException("Main target was not restored");
                if (Minecraft.getInstance().gameRenderer.getMainCamera() != event.getCamera())
                    throw new IllegalStateException("Main camera was not restored");
                mainPosition = event.getCamera().getPosition();
                mainStage = true;
            }
        } catch (Exception failure) { finish(failure); }
    }

    private static void verifyView(RenderLevelStageEvent event, WorldCameraView view) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getCamera() != MinecraftWorldViews.currentCamera() || mc.gameRenderer.getMainCamera() != event.getCamera())
            throw new IllegalStateException("World camera dispatch differs");
        if (event.getCamera().getPosition().distanceToSqr(new Vec3(view.x(), view.y(), view.z())) > 1e-10
                || Math.abs(event.getCamera().getYRot() - view.yaw()) > 1e-4)
            throw new IllegalStateException("World camera pose differs");
        if (mc.getMainRenderTarget() == originalTarget || mc.getMainRenderTarget().width != view.width())
            throw new IllegalStateException("World camera target differs");
        var expected = MinecraftWorldViews.projection(mc.gameRenderer.getDepthFar());
        if (!event.getProjectionMatrix().equals(expected, 1e-5f))
            throw new IllegalStateException("World camera FOV/aspect/roll projection differs");
        var lines = net.minecraft.client.renderer.GameRenderer.getRendertypeLinesShader();
        lines.setDefaultUniforms(com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES,
                event.getModelViewMatrix(), event.getProjectionMatrix(), mc.getWindow());
        var size = lines.SCREEN_SIZE.getFloatBuffer();
        if (size.get(0) != view.width() || size.get(1) != view.height())
            throw new IllegalStateException("Shader ScreenSize used main window dimensions");
    }

    private static void front(OutputFrame<FrameTexture> output) {
        if (finished) return;
        try {
            if (!frontStage) throw new IllegalStateException("Front published before AFTER_LEVEL");
            if (lastFrontFrame == frame) throw new IllegalStateException("Front rendered twice");
            lastFrontFrame = frame; frontFrames++; lastFront = output.resource();
            if (stage == 1 && frontFrames == 12) frontHash = save(output, "front");
            if (stage == 3) {
                if (output.width() != 480 || output.height() != 270) throw new IllegalStateException("Camera resize failed");
                if (GL11.glIsTexture(oldFront.textureId()) || GL30.glIsFramebuffer(oldFront.framebufferId()))
                    throw new IllegalStateException("Resize retained old output target");
                save(output, "front-resized"); stage = 4;
            }
            if (stage == 5) throw new IllegalStateException("Disabled front view rendered");
        } catch (Exception failure) { finish(failure); }
    }

    private static void back(OutputFrame<FrameTexture> output) {
        if (finished) return;
        try {
            if (!backStage) throw new IllegalStateException("Back published before AFTER_LEVEL");
            if (lastBackFrame == frame) throw new IllegalStateException("Back rendered twice");
            lastBackFrame = frame; backFrames++; lastBack = output.resource();
            if (stage == 1 && backFrames == 12) { backHash = save(output, "back"); stage = 2; }
            if (stage == 5) {
                var target = MinecraftWorldViews.currentTarget();
                closedCameraTarget = target;
                back.close(); backOutput.close(); // release must wait until publication completes
                if (target.getColorTextureId() == -1) throw new IllegalStateException("Borrowed camera target retired in callback");
                stage = 6;
            }
        } catch (Exception failure) { finish(failure); }
    }

    private static void main(OutputFrame<FrameTexture> output) {
        if (finished) return;
        try {
            if (!mainStage || MinecraftWorldViews.currentCamera() != null || MinecraftWorldViews.currentFrameToken() != null)
                throw new IllegalStateException("Main presentation retained camera scope");
            if (stage < 7 && samplesThisFrame != 1)
                throw new IllegalStateException("Model sample count per host frame was " + samplesThisFrame);
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(mainPosition) > 1e-10)
                throw new IllegalStateException("Main camera changed after level render");
            if (stage == 7) {
                int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                try (NativeImage source = Screenshot.takeScreenshot(originalTarget); NativeImage actual = read(output)) {
                    for (int y = 0; y < output.height(); y++) for (int x = 0; x < output.width(); x++)
                        if ((source.getPixelRGBA(x, y) & 0xffffff) != (actual.getPixelRGBA(x, y) & 0xffffff))
                            throw new IllegalStateException("Main WYSIWYG output differs");
                    actual.writeToFile(directory().resolve("main.png"));
                } finally { RenderSystem.bindTexture(previous); }
                stage = 8;
            }
        } catch (Exception failure) { finish(failure); }
    }

    private static java.nio.file.Path directory() throws Exception {
        var directory = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/world-views");
        Files.createDirectories(directory);
        return directory;
    }

    private static NativeImage read(OutputFrame<FrameTexture> output) {
        NativeImage image = new NativeImage(output.width(), output.height(), false);
        RenderSystem.bindTexture(output.resource().textureId()); image.downloadTexture(0, true); image.flipY();
        return image;
    }

    private static long save(OutputFrame<FrameTexture> output, String name) throws Exception {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try (NativeImage image = read(output)) {
            image.writeToFile(directory().resolve(name + ".png"));
            long hash = 1; int first = image.getPixelRGBA(0, 0); boolean varied = false;
            for (int y = 0; y < output.height(); y++) for (int x = 0; x < output.width(); x++) {
                int color = image.getPixelRGBA(x, y);
                varied |= color != first;
            }
            // Compare normalized samples; different resolutions alone must not establish different viewpoints.
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
                hash = hash * 31 + image.getPixelRGBA(x * output.width() / 16, y * output.height() / 16);
            if (!varied) throw new IllegalStateException("World camera image is blank");
            return hash;
        } finally { RenderSystem.bindTexture(previous); }
    }

    private static java.nio.file.Path resourceFixture(String name, int color) throws Exception {
        var pack = Minecraft.getInstance().gameDirectory.toPath().resolve("resourcepacks").resolve(name);
        Files.createDirectories(pack.resolve("assets/minecraft/textures/block"));
        Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34,\"description\":\"Camera test fixture\"}}");
        try (NativeImage image = new NativeImage(16, 16, false)) {
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setPixelRGBA(x, y, color);
            image.writeToFile(pack.resolve("assets/minecraft/textures/block/sand.png"));
        }
        return pack;
    }

    private static int atlasSandPixel() {
        var atlas = Minecraft.getInstance().getModelManager().getAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        var sprite = atlas.getSprite(ResourceLocation.withDefaultNamespace("block/sand"));
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int framebuffer = GL30.glGenFramebuffers();
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            RenderSystem.bindTexture(atlas.getId());
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, atlas.getId(), 0);
            var pixel = stack.malloc(4);
            GL11.glReadPixels((int)(sprite.getU0() * width) + 4, (int)(sprite.getV0() * height) + 4,
                    1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            return pixel.order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL30.glDeleteFramebuffers(framebuffer);
            RenderSystem.bindTexture(previousTexture);
        }
    }

    private static void loadMainShaderFixture(java.nio.file.Path pack) throws Exception {
        var configFile = Minecraft.getInstance().gameDirectory.toPath().resolve("config/iris.properties");
        byte[] previousConfig = Files.exists(configFile) ? Files.readAllBytes(configFile) : null;
        try {
            Iris.getIrisConfig().setShaderPackName(pack.getFileName().toString());
            Iris.getIrisConfig().setShadersEnabled(true);
            Iris.getIrisConfig().save();
            // Iris.reload() reads from disk; persist the fixture only for that call.
            Iris.reload();
        } finally {
            if (previousConfig == null) Files.deleteIfExists(configFile);
            else Files.write(configFile, previousConfig);
        }
    }

    private static java.nio.file.Path shaderFixture(String name, String tint) throws Exception {
        var pack = Iris.getShaderpacksDirectory().resolve(name);
        var shaders = pack.resolve("shaders");
        Files.createDirectories(shaders);
        Files.writeString(shaders.resolve("final.vsh"), """
                #version 120
                varying vec2 texcoord;
                void main() { gl_Position = ftransform(); texcoord = gl_MultiTexCoord0.xy; }
                """);
        Files.writeString(shaders.resolve("final.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 texcoord;
                void main() { gl_FragColor = vec4(texture2D(colortex0, texcoord).rgb * vec3(%s), 1.0); }
                """.formatted(tint));
        Files.writeString(shaders.resolve("shadow.vsh"), """
                #version 120
                void main() { gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("shadow.fsh"), """
                #version 120
                const int shadowMapResolution = 128;
                const float shadowDistance = 32.0;
                void main() { gl_FragColor = vec4(1.0); }
                """);
        return pack;
    }

    private static void finish(Exception failure) {
        if (finished) return;
        finished = true;
        REPORT.put("passed", failure == null);
        if (failure != null) REPORT.put("failure", failure.toString());
        try {
            var file = directory().resolve("report.json");
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure == null) LogUtils.getLogger().info("WORLD_VIEWS_SMOKE_PASS {}", file);
            else LogUtils.getLogger().error("WORLD_VIEWS_SMOKE_FAIL", failure);
        } catch (Exception writeFailure) { LogUtils.getLogger().error("Cannot save world-views report", writeFailure); }
        Minecraft.getInstance().stop();
    }
}
