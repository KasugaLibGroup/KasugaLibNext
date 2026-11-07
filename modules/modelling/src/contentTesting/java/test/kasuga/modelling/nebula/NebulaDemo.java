package test.kasuga.modelling.nebula;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent;
import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import lib.kasuga.rendering.effect.post.graph.*;
import lib.kasuga.rendering.effect.shader.RenderShaderDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
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

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/** Development-only world-space animation scene; uses public Kasuga rendering and animation APIs. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class NebulaDemo {
    public static final Vec3 OBSERVER = new Vec3(0, 512, 0);
    /** A 1344 x 768 x 960 block nebula surrounding a small travelling cuboid. */
    public static final double WORLD_SCALE = 32;
    public static final Vec3 CENTER = new Vec3(0, 513.62, 576);
    private static final NebulaSimulation SIMULATION = new NebulaSimulation(0x4E4542554C41L);
    private static final PostProcessGraphTarget MAIN = PostProcessGraphTarget.main();
    private static final PostProcessGraphTarget VOLUME = PostProcessGraphTarget.managed(id("nebula_volume_target"));
    private static final float[] BOLT_DATA = new float[512], LIGHT_DATA = new float[16];
    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f(), INVERSE_VIEW = new Matrix4f();
    private static RenderShaderHandle volumeShader, presentShader;
    private static volatile Session session;
    private static volatile Session restoring;
    private static boolean autoHandled;
    private static long drawCount;
    private static float renderedPeak;
    private static float cameraAzimuth, cameraElevation;
    private static boolean interactionEnabled = true, craftVisible = true, coreEnabled = true;
    private static boolean detailEnabled = true, captureMode;
    private static float flashProbe = -1;
    private static int renderWidth, renderHeight;

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
    static void capture(boolean enabled) { captureMode = enabled; }
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
    }

    @SubscribeEvent public static void pipelines(RegisterRenderPipelinesEvent event) {
        var registrar = event.registrar(id("nebula_demo"));
        volumeShader = registrar.shader(RenderShaderDescriptor.standard(id("nebula_volume"), DefaultVertexFormat.BLIT_SCREEN)).handle();
        presentShader = registrar.shader(RenderShaderDescriptor.standard(id("nebula_present"), DefaultVertexFormat.BLIT_SCREEN)).handle();
        registrar.graph(PostProcessGraph.builder(id("nebula_graph"))
                .target(PostProcessTargetDescriptor.builder(id("nebula_volume_target"))
                        .screenScale(1f).filter(PostProcessTargetDescriptor.TextureFilter.LINEAR).build())
                .prepare((context, frame) -> active() && context.world().level() == session.world
                        && volumeShader.isReady() && presentShader.isReady())
                .pass(PostProcessGraphPassDescriptor.builder(id("nebula_march"), NebulaDemo::draw)
                        .writes(VOLUME).build())
                .pass(PostProcessGraphPassDescriptor.builder(id("nebula_present"), context ->
                                context.fullscreen(MAIN, presentShader).colorSampler("SceneSampler", context.read(VOLUME))
                                        .uniforms(ignored -> RenderSystem.disableBlend()).draw(false))
                        .reads(VOLUME).writes(MAIN).build())
                .build(), 2000);
    }

    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_nebula")
                .executes(context -> start() ? 1 : 0)
                .then(Commands.literal("stop").executes(context -> { stop(); return 1; })));
    }

    public static boolean start() {
        var mc = Minecraft.getInstance();
        if (session != null) return true;
        var server = mc.getSingleplayerServer();
        if (mc.player == null || server == null || server.isPublished()) {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("Nebula Sim requires a private singleplayer world."), false);
            return false;
        }
        if (volumeShader == null || !volumeShader.isReady() || !presentShader.isReady()) {
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
        if (previous == null) return;
        restoring = previous;
        session = null;
        resetScene();
        var mc = Minecraft.getInstance();
        mc.options.hideGui = previous.hideGui;
        if (mc.screen instanceof NebulaScreen) mc.setScreen(null);
        if (!previous.server.isShutdown()) previous.server.execute(() -> restore(previous));
        else if (restoring == previous) restoring = null;
    }

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
        if (active() && captureMode) SIMULATION.captureFrame();
    }

    private static void draw(PostProcessGraphContext context) {
        var world = context.world();
        float partial = world.partialTick().getGameTimeDeltaPartialTick(false);
        float time = SIMULATION.time(captureMode ? 1 : partial);
        Arrays.fill(BOLT_DATA, 0); Arrays.fill(LIGHT_DATA, 0);
        renderedPeak = 0;
        int count = 0, light = 0;
        for (var bolt : SIMULATION.bolts()) {
            float energy = bolt.strength(time);
            renderedPeak = Math.max(renderedPeak, energy);
            int k = light++ * 4;
            LIGHT_DATA[k] = bolt.center().x; LIGHT_DATA[k+1] = bolt.center().y; LIGHT_DATA[k+2] = bolt.center().z;
            LIGHT_DATA[k+3] = energy;
            for (var segment : bolt.segments()) {
                k = count++ * 8;
                BOLT_DATA[k] = segment.start().x; BOLT_DATA[k+1] = segment.start().y; BOLT_DATA[k+2] = segment.start().z;
                BOLT_DATA[k+3] = energy;
                BOLT_DATA[k+4] = segment.end().x; BOLT_DATA[k+5] = segment.end().y; BOLT_DATA[k+6] = segment.end().z;
                BOLT_DATA[k+7] = segment.width();
            }
        }
        int segmentCount = count;
        // The procedural scene uses a Kasuga follow camera in compact cloud-local units.
        var craft = NebulaFlight.sample(time);
        var target = context.write(VOLUME);
        renderWidth = target.width; renderHeight = target.height;
        var camera = NebulaFlight.camera(craft, target.width, target.height, cameraAzimuth, cameraElevation);
        var eye = new Vector3f((float) camera.x(), (float) camera.y(), (float) camera.z());
        var look = new Vector3f(craft.position()).fma(.7f, craft.forward());
        INVERSE_PROJECTION.identity().perspective((float) Math.toRadians(camera.verticalFov()), camera.aspectRatio(), .01f, 100)
                .invert();
        INVERSE_VIEW.identity().lookAt(eye, look, new Vector3f(0, 1, 0))
                .rotateLocalZ((float) Math.toRadians(camera.roll())).invert();
        context.fullscreen(VOLUME, volumeShader).uniforms(shader -> {
            RenderSystem.disableBlend();
            shader.safeGetUniform("InverseProjection").set(INVERSE_PROJECTION);
            shader.safeGetUniform("InverseView").set(INVERSE_VIEW);
            shader.safeGetUniform("Eye").set((float) eye.x, (float) eye.y, (float) eye.z);
            shader.safeGetUniform("CraftPosition").set(craft.position().x, craft.position().y, craft.position().z);
            shader.safeGetUniform("CraftInverse").set(craft.toLocal());
            shader.safeGetUniform("WakeData").set(NebulaFlight.wake(time));
            shader.safeGetUniform("InteractionStrength").set(interactionEnabled ? 1f : 0f);
            shader.safeGetUniform("CraftVisible").set(craftVisible ? 1f : 0f);
            shader.safeGetUniform("CoreStrength").set(coreEnabled ? 1f : 0f);
            shader.safeGetUniform("DetailStrength").set(detailEnabled ? 1f : 0f);
            shader.safeGetUniform("GlobalFlash").set(flashProbe >= 0 ? flashProbe : renderedPeak);
            shader.safeGetUniform("ShipScale").set(NebulaFlight.SHIP_SCALE);
            var velocity = new Vector3f(NebulaFlight.sample(time+.01f).position()).sub(craft.position()).mul(100);
            shader.safeGetUniform("CraftVelocity").set(velocity.x,velocity.y,velocity.z);
            shader.safeGetUniform("Time").set(time);
            shader.safeGetUniform("SegmentCount").set(segmentCount);
            shader.safeGetUniform("BoltData").set(BOLT_DATA);
            shader.safeGetUniform("LightData").set(LIGHT_DATA);
        }).draw(false);
        drawCount++;
    }

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
