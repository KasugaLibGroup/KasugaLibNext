package test.kasuga.modelling.demo.nebula;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent;
import lib.kasuga.rendering.effect.post.PostProcessTargetPool;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraph;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphContext;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphTarget;
import lib.kasuga.rendering.effect.shader.RenderShaderDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import lib.kasuga.shader.ShaderParameter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Set;
import java.util.UUID;

/** Development-only world-space animation scene; uses public Kasuga rendering and animation APIs. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class NebulaDemo {
    public static final Vec3 OBSERVER = new Vec3(0, 512, 0);
    /** A 1344 x 768 x 960 block nebula surrounding the travelling starship. */
    public static final double WORLD_SCALE = 32;
    public static final Vec3 CENTER = new Vec3(0, 513.62, 576);
    private static final NebulaSimulation SIMULATION = new NebulaSimulation(0x4E4542554C41L);
    private static final PostProcessGraphTarget VOLUME = NebulaPipeline.VOLUME;
    private static final PostProcessGraphTarget GBUFFER = NebulaPipeline.GBUFFER;
    private static final ResourceLocation FRAME_STATE = id("frame_state");
    private static final NebulaTextures TEXTURES = new NebulaTextures();
    private static final NebulaStarshipRenderer STARSHIP = new NebulaStarshipRenderer();
    private static final float CAPTURE_FRAME_RATE = captureFrameRate();
    private static final float[] BOLT_DATA = new float[512], LIGHT_DATA = new float[8];
    private static final float[] WAKE_DATA = new float[NebulaFlight.WAKE_POINTS * 4];
    private static final float[] WAKE_BOUNDS = new float[24];
    private static final Matrix4f PROJECTION = new Matrix4f(), INVERSE_PROJECTION = new Matrix4f(), INVERSE_VIEW = new Matrix4f();
    private static final Vector3f EYE = new Vector3f();
    private static final Vector3f CAM_DIR = new Vector3f(), VELOCITY = new Vector3f();
    private static RenderShaderHandle volumeShader;
    private static PostProcessGraph graph;
    private static long assetGeneration = -1;
    private static volatile Session session;
    private static volatile Session restoring;
    private static boolean autoHandled;
    private static long drawCount;
    private static float renderedPeak;
    private static float cameraAzimuth, cameraElevation;
    private static boolean interactionEnabled = true, craftVisible = true, coreEnabled = true;
    private static boolean detailEnabled = true, captureMode;
    private static float flashProbe = -1;
    private static float warpTimeProbe = -1;
    private static float cameraTimeProbe = -1;
    private static int craftDebug;
    private static float indirectStrength = 1, smokeStrength = 1;
    private static int renderWidth, renderHeight;
    private static final NebulaGpuTimer GPU_TIMER = new NebulaGpuTimer();

    private NebulaDemo() {}
    public static NebulaSimulation simulation() { return SIMULATION; }
    public static boolean active() { return session != null && session.world != null; }
    public static boolean idle() { return session == null; }
    public static long drawCount() { return drawCount; }
    public static RenderShaderHandle shader() { return volumeShader; }
    static ReturnState originalState() { return session.origin; }
    static boolean originalHideGui() { return session.hideGui; }
    static float renderedPeak() { return renderedPeak; }
    static void interaction(boolean enabled) { interactionEnabled = enabled; }
    static void craftVisible(boolean visible) { craftVisible = visible; }
    static void core(boolean enabled) { coreEnabled = enabled; }
    static void detail(boolean enabled) { detailEnabled = enabled; }
    static void flash(float value) { flashProbe = value; }
    static void warpTimeProbe(float value) { warpTimeProbe = value; }
    static void cameraTimeProbe(float value) { cameraTimeProbe = value; }
    static void craftDebug(int value) { craftDebug = value; }
    static void indirect(float value) { indirectStrength = value; }
    static void smoke(float value) { smokeStrength = value; }
    static void hullTexture(float value) { STARSHIP.textureStrength(value); }
    static void capture(boolean enabled) { captureMode = enabled; }
    static void measureGpuTime(boolean enabled) { GPU_TIMER.enabled(enabled); }
    static double lastGpuPassMs() { return GPU_TIMER.lastMs(); }
    static long completedGpuQueries() { return GPU_TIMER.samples(); }
    static double gpuPassTotalMs() { return GPU_TIMER.totalMs(); }
    static double gpuPassMaxMs() { return GPU_TIMER.maxMs(); }
    static boolean lastQueryDeleteVerified() { return GPU_TIMER.lastDeleteVerified(); }
    static boolean texturesAllocated() { return TEXTURES.allocated() || STARSHIP.allocated() || graphTargetsAllocated(); }
    static boolean graphTargetsAllocated() {
        return graph != null && graph.allocatedTargetIds().stream().anyMatch(id -> PostProcessTargetPool.getInstance().get(id).isPresent());
    }
    static com.mojang.blaze3d.pipeline.RenderTarget gbuffer() {
        return PostProcessTargetPool.getInstance().get(graph.allocatedTargetId(GBUFFER)).orElseThrow();
    }
    static boolean lastTextureDeleteVerified() { return TEXTURES.lastDeleteVerified() && STARSHIP.lastDeleteVerified(); }
    static int starshipTriangleCount() { return STARSHIP.triangleCount(); }
    static long starshipAssetGeneration() { return assetGeneration; }
    static boolean starshipPbrSlotsReady() { return STARSHIP.pbrSlotsReady(); }
    static int renderWidth() { return renderWidth; }
    static int renderHeight() { return renderHeight; }
    static void orbit(double dx, double dy) {
        cameraAzimuth += (float) dx * .225f;
        cameraElevation = Math.clamp(cameraElevation + (float) dy * .225f, -27, 48);
    }

    private static void resetScene() {
        SIMULATION.reset();
        cameraAzimuth = cameraElevation = 0;
        interactionEnabled = craftVisible = coreEnabled = true;
        detailEnabled = true;
        captureMode = false;
        flashProbe = -1;
        warpTimeProbe = -1;
        cameraTimeProbe = -1;
        craftDebug = 0;
        indirectStrength = smokeStrength = 1;
        STARSHIP.textureStrength(1);
    }

    @SubscribeEvent public static void pipelines(RegisterRenderPipelinesEvent event) {
        var registrar = event.registrar(id("nebula_demo"));
        volumeShader = registrar.shader(RenderShaderDescriptor.builder(id("nebula_volume"), DefaultVertexFormat.BLIT_SCREEN)
                .resource()
                .expose(ShaderParameter.floatParameter("InteractionStrength", "Starship interaction with the volume", 1f, 0f, 1f))
                .expose(ShaderParameter.floatParameter("IndirectStrength", "Indirect light from the nebula", 1f, 0f, 2f))
                .expose(ShaderParameter.floatParameter("SmokeStrength", "Volume smoke density", 1f, 0f, 2f))
                .expose(ShaderParameter.floatParameter("CoreStrength", "Core light contribution", 1f, 0f, 2f))
                .expose(ShaderParameter.floatParameter("DetailStrength", "Fine volume detail", 1f, 0f, 2f))
                .expose(ShaderParameter.floatParameter("GlobalFlash", "Lightning flash multiplier", 0f, 0f, 4f))
                .expose(ShaderParameter.floatParameter("ShipScale", "Starship display scale", NebulaFlight.SHIP_SCALE, .1f, 2f))
                .build()).handle();
        graph = NebulaPipeline.create((context, frame) -> active()
                        && context.world().level() == session.world && volumeShader.isReady(),
                NebulaDemo::drawStarship, NebulaDemo::drawVolume);
        registrar.graph(graph, 2000);
    }

    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_nebula")
                .executes(context -> start() ? 1 : 0)
                .then(Commands.literal("stop").executes(context -> { stop(); return 1; }))
                .then(Commands.literal("warp").executes(context -> {
                    boolean triggered = SIMULATION.triggerWarp();
                    var player = Minecraft.getInstance().player;
                    if (player != null) player.displayClientMessage(Component.literal(triggered ? "Warp drive engaged." : "Warp drive already active."), false);
                    return triggered ? 1 : 0;
                })));
    }

    public static boolean start() {
        var mc = Minecraft.getInstance();
        if (session != null) return true;
        var server = mc.getSingleplayerServer();
        if (mc.player == null || server == null || server.isPublished()) {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("Nebula Sim requires a private singleplayer world."), false);
            return false;
        }
        if (volumeShader == null || !volumeShader.isReady()) {
            mc.player.displayClientMessage(Component.literal("Nebula Sim shaders are not ready; retry after resource loading."), false);
            return false;
        }
        var next = new Session(server, mc.player.getUUID(), mc.options.hideGui);
        session = next;
        server.execute(() -> {
            if (session != next) return;
            var player = server.getPlayerList().getPlayer(next.player);
            var end = server.getLevel(Level.END);
            if (player == null || end == null) { next.failed = true; return; }
            next.origin = new ReturnState(player.level().dimension(), player.position(), player.getYRot(), player.getXRot(),
                    player.gameMode.getGameModeForPlayer(), player.getAbilities().flying);
            if (!player.isSpectator() && !player.setGameMode(GameType.SPECTATOR)) { next.failed = true; return; }
            if (!player.teleportTo(end, OBSERVER.x, OBSERVER.y, OBSERVER.z, Set.of(), 0, 0)) next.failed = true;
        });
        return true;
    }

    public static void stop() {
        var previous = session;
        if (previous == null) {
            releaseGpuResources();
            return;
        }
        restoring = previous;
        session = null;
        resetScene();
        releaseGpuResources();
        var mc = Minecraft.getInstance();
        mc.options.hideGui = previous.hideGui;
        if (mc.screen instanceof NebulaScreen) mc.setScreen(null);
        if (!previous.server.isShutdown()) previous.server.execute(() -> restore(previous));
        else if (restoring == previous) restoring = null;
    }

    private static void releaseGpuResources() {
        TEXTURES.release();
        Runnable release = () -> {
            STARSHIP.release();
            if (graph != null) graph.allocatedTargetIds().forEach(PostProcessTargetPool.getInstance()::release);
        };
        if (RenderSystem.isOnRenderThread()) release.run();
        else RenderSystem.recordRenderCall(release::run);
        if (RenderSystem.isOnRenderThread()) deleteQueryNow();
        else RenderSystem.recordRenderCall(NebulaDemo::deleteQueryNow);
    }

    private static void deleteQueryNow() { GPU_TIMER.close(); }

    /** Restore before PlayerList saves the departing player, including closing the game window. */
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        var current = session != null ? session : restoring;
        if (current == null || !(event.getEntity() instanceof ServerPlayer player)
                || player.getServer() != current.server || !player.getUUID().equals(current.player)) return;
        restore(current);
        Minecraft.getInstance().execute(NebulaDemo::stop);
    }

    private static void restore(Session previous) {
        var origin = previous.origin;
        var player = previous.server.getPlayerList().getPlayer(previous.player);
        if (origin == null || player == null) {
            if (restoring == previous) restoring = null;
            return;
        }
        var level = previous.server.getLevel(origin.dimension);
        if (level == null) return;
        player.teleportTo(level, origin.position.x, origin.position.y, origin.position.z, Set.of(), origin.yaw, origin.pitch);
        player.setGameMode(origin.gameMode);
        player.getAbilities().flying = origin.flying;
        player.onUpdateAbilities();
        previous.origin = null;
        if (restoring == previous) restoring = null;
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (!autoHandled && Boolean.getBoolean("kasuga.nebula.auto") && mc.level != null && mc.player != null
                && mc.getOverlay() == null && volumeShader != null && volumeShader.isReady()) {
            autoHandled = start();
        }
        var current = session;
        if (current == null) return;
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() != current.server || current.failed) { stop(); return; }
        if (current.world == null) {
            if (++current.waitTicks > 400) { stop(); return; }
            if (!mc.level.dimension().equals(Level.END) || mc.player.position().distanceTo(OBSERVER) > 2) return;
            current.world = mc.level;
            resetScene();
            mc.options.hideGui = true;
            mc.setScreen(new NebulaScreen());
        }
        if (mc.level != current.world) { stop(); return; }
        if (!mc.isPaused() && !captureMode) SIMULATION.tick();
    }

    @SubscribeEvent public static void beforeFrame(RenderFrameEvent.Pre event) {
        if (active() && captureMode) SIMULATION.captureFrame(CAPTURE_FRAME_RATE);
    }

    private static float captureFrameRate() {
        int fps = Integer.getInteger("kasuga.nebula.captureFps", 30);
        if (fps <= 0) throw new IllegalArgumentException("kasuga.nebula.captureFps must be positive");
        return fps;
    }

    private static PreparedFrame prepareFrame(PostProcessGraphContext context) {
        var world = context.world();
        if (assetGeneration != volumeShader.generation()) {
            STARSHIP.release();
            TEXTURES.release();
            assetGeneration = volumeShader.generation();
        }
        float partial = world.partialTick().getGameTimeDeltaPartialTick(false);
        float time = SIMULATION.time(captureMode ? 1 : partial);
        float warpTime = warpTimeProbe >= 0 ? warpTimeProbe : SIMULATION.warpTime(captureMode ? 1 : partial);
        var warp = NebulaFlight.sampleWarp(warpTime);
        var lightning = NebulaLightningData.pack(SIMULATION.bolts(), time, LIGHT_DATA, BOLT_DATA);
        renderedPeak = lightning.peak();
        int segmentCount = lightning.segments(), lightCount = lightning.lights();

        var craft = NebulaFlight.sample(time);
        NebulaFlight.fillWakeAndBounds(time, WAKE_DATA, WAKE_BOUNDS);

        var target = world.mainRenderTarget();
        renderWidth = target.width; renderHeight = target.height;
        var camera = cameraTimeProbe >= 0
                ? NebulaFlight.camera(craft, warp, cameraTimeProbe, target.width, target.height, cameraAzimuth, cameraElevation)
                : SIMULATION.camera(craft, warp, target.width, target.height, cameraAzimuth, cameraElevation, captureMode ? 1 : partial);
        EYE.set((float) camera.x(), (float) camera.y(), (float) camera.z());
        NebulaFlight.cameraForward(camera, CAM_DIR);
        TEXTURES.uploadFrameData(time, EYE, CAM_DIR, WAKE_DATA, LIGHT_DATA, lightCount, BOLT_DATA, segmentCount, craft, warp);
        camera.projection(.01f, 100, PROJECTION);
        INVERSE_PROJECTION.set(PROJECTION).invert();
        camera.inverseView(INVERSE_VIEW);
        VELOCITY.set(NebulaFlight.sample(time + .01f).position()).sub(craft.position())
                .mul(100f * (1.0f + warp.warpSpeed() * 0.45f));

        return new PreparedFrame(time, warpTime, craft, warp, camera, segmentCount, lightCount);
    }

    private static void drawStarship(PostProcessGraphContext context) {
        var prepared = prepareFrame(context);
        context.frame().put(FRAME_STATE, prepared);
        GPU_TIMER.begin();
        try {
            STARSHIP.render(prepared.craft(), prepared.warp(), PROJECTION, INVERSE_VIEW,
                    context.write(GBUFFER), craftVisible);
        } catch (RuntimeException | Error failure) {
            GPU_TIMER.end();
            throw failure;
        }
    }

    private static void drawVolume(PostProcessGraphContext context) {
        var prepared = context.frame().require(FRAME_STATE, PreparedFrame.class);

        try {
        var parameters = volumeShader.parameters();
        parameters.setFloat("InteractionStrength", interactionEnabled ? 1f : 0f);
        parameters.setFloat("IndirectStrength", indirectStrength);
        parameters.setFloat("SmokeStrength", smokeStrength);
        parameters.setFloat("CoreStrength", coreEnabled ? 1f : 0f);
        parameters.setFloat("DetailStrength", detailEnabled ? 1f : 0f);
        parameters.setFloat("GlobalFlash", flashProbe >= 0 ? flashProbe : renderedPeak);
        parameters.setFloat("ShipScale", NebulaFlight.SHIP_SCALE);
        context.fullscreen(VOLUME, volumeShader)
                .textureSampler("NoiseSampler", TEXTURES.noiseTexture())
                .textureSampler("CellSampler", TEXTURES.cellTexture())
                .bufferSampler("DataSampler", TEXTURES.dataBuffer())
                .colorSampler("CraftSurfaceSampler", context.read(GBUFFER), 0)
                .colorSampler("CraftNormalSampler", context.read(GBUFFER), 1)
                .colorSampler("CraftPbrSampler", context.read(GBUFFER), 2)
                .depthSampler("CraftDepthSampler", context.read(GBUFFER))
                .uniforms(shader -> {
                    RenderSystem.disableBlend();
                    shader.safeGetUniform("InverseProjection").set(INVERSE_PROJECTION);
                    shader.safeGetUniform("InverseView").set(INVERSE_VIEW);
                    shader.safeGetUniform("Eye").set(EYE.x, EYE.y, EYE.z);
                    shader.safeGetUniform("CraftPosition").set(prepared.craft().position().x, prepared.craft().position().y, prepared.craft().position().z);
                    shader.safeGetUniform("CraftInverse").set(prepared.craft().toLocal());
                    shader.safeGetUniform("CraftForward").set(prepared.craft().forward().x, prepared.craft().forward().y, prepared.craft().forward().z);
                    shader.safeGetUniform("CraftRight").set(prepared.craft().right().x, prepared.craft().right().y, prepared.craft().right().z);
                    shader.safeGetUniform("CraftUp").set(prepared.craft().up().x, prepared.craft().up().y, prepared.craft().up().z);
                    shader.safeGetUniform("WakeBoundsMin").set(WAKE_BOUNDS[0], WAKE_BOUNDS[1], WAKE_BOUNDS[2]);
                    shader.safeGetUniform("WakeBoundsMax").set(WAKE_BOUNDS[3], WAKE_BOUNDS[4], WAKE_BOUNDS[5]);
                    shader.safeGetUniform("WakeSubMin0").set(WAKE_BOUNDS[6], WAKE_BOUNDS[7], WAKE_BOUNDS[8]);
                    shader.safeGetUniform("WakeSubMax0").set(WAKE_BOUNDS[9], WAKE_BOUNDS[10], WAKE_BOUNDS[11]);
                    shader.safeGetUniform("WakeSubMin1").set(WAKE_BOUNDS[12], WAKE_BOUNDS[13], WAKE_BOUNDS[14]);
                    shader.safeGetUniform("WakeSubMax1").set(WAKE_BOUNDS[15], WAKE_BOUNDS[16], WAKE_BOUNDS[17]);
                    shader.safeGetUniform("WakeSubMin2").set(WAKE_BOUNDS[18], WAKE_BOUNDS[19], WAKE_BOUNDS[20]);
                    shader.safeGetUniform("WakeSubMax2").set(WAKE_BOUNDS[21], WAKE_BOUNDS[22], WAKE_BOUNDS[23]);
                    shader.safeGetUniform("CraftVisible").set(craftVisible ? 1f : 0f);
                    shader.safeGetUniform("CraftDebug").set(craftDebug);
                    shader.safeGetUniform("CraftVelocity").set(VELOCITY.x, VELOCITY.y, VELOCITY.z);
                    shader.safeGetUniform("Time").set(prepared.time());
                    shader.safeGetUniform("SegmentCount").set(prepared.segmentCount());
                    shader.safeGetUniform("LightCount").set(prepared.lightCount());
                    shader.safeGetUniform("StarCount").set(TEXTURES.visibleStarCount());
                    shader.safeGetUniform("DustCount").set(TEXTURES.visibleDustCount());
                    shader.safeGetUniform("WarpCharge").set(prepared.warp().charge());
                    shader.safeGetUniform("WarpActive").set(prepared.warp().warpActive());
                    shader.safeGetUniform("WarpStretch").set(prepared.warp().stretch());
                    shader.safeGetUniform("WarpFlash").set(prepared.warp().flash());
                    shader.safeGetUniform("WarpDistance").set((float) (prepared.warp().warpDistance() % 6400.0f));
                    shader.safeGetUniform("WarpTunnelPhase").set((float) (prepared.warp().warpDistance() % 628.31853f));
                    shader.safeGetUniform("WarpShock").set(prepared.warp().shockProgress(), prepared.warp().shockIntensity());
                    shader.safeGetUniform("NacelleParticleSphere").set(
                            TEXTURES.nacelleParticleSphere().x,
                            TEXTURES.nacelleParticleSphere().y,
                            TEXTURES.nacelleParticleSphere().z,
                            TEXTURES.nacelleParticleSphere().w
                    );
                    shader.safeGetUniform("NacelleParticleCount").set(TEXTURES.visibleNacelleParticleCount());
                }).draw(false);
        } finally {
            GPU_TIMER.end();
        }
        drawCount++;
    }

    private record PreparedFrame(float time, float warpTime, NebulaFlight.Pose craft,
                                 NebulaFlight.WarpState warp, lib.kasuga.rendering.output.WorldCameraView camera,
                                 int segmentCount, int lightCount) {}

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("kasuga_demo", path); }

    private static final class Session {
        final MinecraftServer server;
        final UUID player;
        final boolean hideGui;
        volatile ReturnState origin;
        volatile boolean failed;
        ClientLevel world;
        int waitTicks;
        Session(MinecraftServer server, UUID player, boolean hideGui) { this.server = server; this.player = player; this.hideGui = hideGui; }
    }

    record ReturnState(ResourceKey<Level> dimension, Vec3 position, float yaw, float pitch, GameType gameMode, boolean flying) {}
}
