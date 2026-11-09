package test.kasuga.modelling.demo.cobalt;

import lib.kasuga.KasugaLib;
import lib.kasuga.audio.SceneTrack;
import lib.kasuga.audio.SpatialAudio;
import lib.kasuga.rendering.cloud.SkyCloudSettings;
import lib.kasuga.rendering.effect.builtin.cloud.CloudEffects;
import lib.kasuga.rendering.effect.builtin.water.WaterEffects;
import lib.kasuga.rendering.effect.builtin.water.WaterSurface;
import lib.kasuga.rendering.water.WaterOptics;
import lib.kasuga.scene.MinecraftStage;
import lib.kasuga.scene.SceneControls;
import lib.kasuga.scene.SceneOverlay;
import lib.kasuga.scene.SceneProps;
import lib.kasuga.scene.SceneWarmup;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/** Playable development demo. All rendering, liquid, grab, audio and UI are owned by library APIs. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CobaltBlueDemo {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, id("cobalt_blue_ocean"));
    private static final AABB SLOT = new AABB(.13, 66.12, .13, .87, 66.92, .87);
    private static final boolean AUTO = Boolean.getBoolean("kasuga.cobalt.auto");
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private static volatile Session session;
    private static boolean autoStarted;
    private static int autoWorldTicks;
    private static boolean autoWorldRequested;
    private CobaltBlueDemo() {}
    public static boolean active() { return session != null && session.props != null; }
    static Session session() { return session; }

    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_cobalt_blue")
                .executes(context -> start() ? 1 : 0)
                .then(Commands.literal("stop").executes(context -> { stop(); return 1; })));
        event.getDispatcher().register(Commands.literal("ksglib").then(Commands.literal("demo")
                .then(Commands.literal("cobalt_blue").executes(context -> start() ? 1 : 0)
                        .then(Commands.literal("stop").executes(context -> { stop(); return 1; })))));
    }
    public static boolean start() {
        var mc = Minecraft.getInstance();
        if (session != null) return true;
        var server = mc.getSingleplayerServer();
        if (mc.player == null || server == null || server.isPublished()) return reject("Cobalt blue requires a private singleplayer world.");
        if (!WaterEffects.ready() || !SceneProps.physicsAvailable()) return reject("Cobalt blue requires the built-in water shaders and Box3D physics.");
        SceneTrack track;
        try { track = SceneTrack.load(id("cobalt_blue/track.json")); }
        catch (RuntimeException failure) { return reject(failure.getMessage()); }
        if (!track.available()) return reject("Cobalt blue music is missing. Run scripts/cobalt-blue.py --music <FLAC> first.");
        var next = new Session(track);
        next.hideGuiBefore = mc.options.hideGui;
        next.silentBefore = mc.player.isSilent();
        next.uiCaptured = true;
        next.overlay = SceneOverlay.openGlobal();
        next.fade = next.overlay.fadeFromBlack(1.4f).pause();
        session = next;
        mc.options.hideGui = true;
        mc.player.setSilent(true);
        mc.setScreen(null);
        var playerId = mc.player.getUUID();
        server.execute(() -> {
            if (session != next) return;
            try {
                var player = server.getPlayerList().getPlayer(playerId);
                if (player == null) throw new IllegalStateException("Player left before stage preparation");
                next.stage = MinecraftStage.open(player, DIMENSION, new Vec3(.5, 65, -4.2), 0, 13, CobaltRoom::build);
                if (session != next) next.stage.close();
            } catch (RuntimeException failure) { next.failure = failure; }
        });
        return true;
    }
    private static boolean reject(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(message), false);
        return false;
    }
    public static void stop() {
        var previous = session;
        if (previous == null) return;
        session = null;
        if (AUTO) Minecraft.getInstance().options.keyUp.setDown(false);
        if (previous.voice != null) previous.voice.close();
        if (previous.footsteps != null) previous.footsteps.close();
        if (previous.overlay != null) previous.overlay.close();
        if (previous.controls != null) previous.controls.close();
        if (previous.water != null) previous.water.close();
        if (previous.beach != null) previous.beach.close();
        if (previous.props != null) previous.props.close();
        if (previous.initialized) {
            var mc = Minecraft.getInstance();
            CloudEffects.sky().settings(previous.cloudSettings);
            CloudEffects.setSkyEnabled(previous.skyEnabled); CloudEffects.setEnabled(previous.cloudEnabled);
        }
        if (previous.uiCaptured) {
            var mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.setSilent(previous.silentBefore);
            mc.options.hideGui = previous.hideGuiBefore;
            previous.uiCaptured = false;
        }
        if (previous.stage != null) previous.stage.close();
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        requestAutoWorld(mc);
        if (!autoStarted && Boolean.getBoolean("kasuga.cobalt.auto") && mc.player != null && mc.level != null
                && mc.getOverlay() == null && WaterEffects.ready()) autoStarted = start();
        var current = session;
        if (current == null) return;
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null || mc.getSingleplayerServer().isPublished()) { stop(); return; }
        if (current.failure != null) { reject("Cobalt blue: " + current.failure.getMessage()); stop(); return; }
        if (current.props == null) {
            if (++current.wait > 600) {
                LOGGER.warn("COBALT_BLUE_WARMUP_TIMEOUT waitingFor={}", current.warmup == null ? "stage dimension" : current.warmup.waitReason());
                reject("Cobalt blue stage timed out."); stop(); return;
            }
            if (current.stage == null || !mc.level.dimension().equals(DIMENSION)
                    || !stageGeometryReady(current, mc)) return;
            try { initialize(current); }
            catch (RuntimeException failure) { reject("Cobalt blue: " + failure.getMessage()); stop(); }
            return;
        }
        if (!mc.level.dimension().equals(DIMENSION) || current.stage.closed()) { stop(); return; }
        if (mc.isPaused()) return;
        if (mc.screen != null && current.props.holding()) { current.props.release(); current.sequence.release(); }
        if (current.props.holding()) {
            current.props.drag(mc.player.getEyePosition(), mc.player.getLookAngle(), .05f);
            var position = current.disc.position();
            if (SLOT.contains(position) || current.previousDisc != null && SLOT.clip(current.previousDisc, position).isPresent()) insert(current);
            current.previousDisc = position;
        }
        current.sequence.tick(.05f);
        boolean ocean = current.sequence.waterInteractionEnabled();
        current.water.playerInteraction(ocean).renderEnabled(ocean);
        current.footsteps.tick(mc.player);
        boolean canPlay = current.sequence.ready() && mc.player.getEyePosition().distanceTo(CobaltRoom.SOURCE) < 5;
        current.prompt.visible(canPlay);
        if (current.sequence.playing()) current.player.seconds(current.sequence.playbackTime());
        if (current.voice != null && current.voice.failedToStart()) {
            reject("The positional music voice failed to start."); stop();
        }
        if (AUTO) autoDrive(current, mc);
    }

    /**
     * Development-only choreography for a headless smoke run. The normal demo
     * remains fully manual: this path exists only when the launch property is
     * explicitly enabled by the Gradle run configuration.
     */
    private static void autoDrive(Session current, Minecraft mc) {
        current.autoTicks++;
        if (!current.autoGrabbed && current.autoTicks == 12 && current.sequence.phase() == CobaltSequence.Phase.WAITING) {
            var direction = CobaltRoom.DISC.subtract(mc.player.getEyePosition()).normalize();
            current.autoGrabbed = current.props.grab(mc.player.getEyePosition(), direction, 6).isPresent();
            if (!current.autoGrabbed || !current.sequence.grab())
                throw new IllegalStateException("Automatic physical record grab was rejected");
            LOGGER.info("COBALT_BLUE_AUTO_GRAB physicsGrab={}", current.autoGrabbed);
        }
        if (current.autoGrabbed && !current.autoInserted && current.autoTicks >= 30
                && current.sequence.phase() == CobaltSequence.Phase.HOLDING) {
            var aim = CobaltRoom.SLOT.subtract(mc.player.getEyePosition());
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(aim.y, aim.horizontalDistance())));
            current.props.holdDistance((float) aim.length());
            mc.options.keyUp.setDown(mc.player.getZ() < -3.1);
        }
        if (current.autoGrabbed && !current.autoInserted && current.sequence.phase() == CobaltSequence.Phase.INSERTING) {
            current.autoInserted = true;
            mc.options.keyUp.setDown(false);
            mc.player.setYRot(0); mc.player.setXRot(13);
            LOGGER.info("COBALT_BLUE_AUTO_INSERT viaPhysicalSlot=true position={}", current.insertionStart);
        }
        if (current.sequence.ready()) {
            current.autoReadyTicks++;
            if (!current.autoPlayed && current.autoReadyTicks >= 10) {
                current.prompt.visible(true);
                play(current);
                current.autoPlayed = true;
                LOGGER.info("COBALT_BLUE_AUTO_PLAY voiceActive={}", current.voice != null && current.voice.isPlaying());
            }
        }
        if (current.sequence.phase() == CobaltSequence.Phase.OCEAN) {
            if (!current.autoWaded) {
                current.autoWaded = true;
                current.autoWadeTicks = 0;
                current.autoFootstepsBeforeOcean = current.footsteps.emittedCount();
                LOGGER.info("COBALT_BLUE_AUTO_OCEAN waterInteractionEnabled={}", current.water.playerInteractionEnabled());
            }
            if (current.autoWadeTicks < 160) {
                // Exercise ordinary client input, server movement and block collision. Gravity
                // brings the player onto the submerged bed when the room floor opens.
                if (current.autoWadeTicks == 1) mc.player.setYRot(-35f);
                mc.options.keyUp.setDown(true);
                current.autoWadeTicks++;
            } else mc.options.keyUp.setDown(false);
            if (!current.autoWaterLogged && current.water.interactionCount() > 0) {
                current.autoWaterLogged = true;
                LOGGER.info("COBALT_BLUE_AUTO_WATER interactions={}", current.water.interactionCount());
            }
        }
        if (current.autoTicks > 1800)
            throw new IllegalStateException("Cobalt blue automatic interaction timed out at " + current.sequence.phase());
    }

    /** Keep the transition black until the built-in scene gate confirms uploaded room meshes. */
    private static boolean stageGeometryReady(Session current, Minecraft mc) {
        if (current.warmup == null) current.warmup = CobaltRoom.warmup(mc.level);
        if (!CobaltBeach.ready()) return false;
        return current.warmup.ready();
    }
    private static void initialize(Session current) {
        var mc = Minecraft.getInstance();
        current.cloudEnabled = CloudEffects.isEnabled(); current.skyEnabled = CloudEffects.isSkyEnabled();
        current.cloudSettings = CloudEffects.sky().settings(); current.initialized = true;
        current.overlay.attachToCurrentLevel();
        mc.options.hideGui = true;
        mc.player.setSilent(true);
        CloudEffects.sky().settings(SkyCloudSettings.DEFAULT); CloudEffects.setSkyEnabled(true); CloudEffects.setEnabled(true);
        current.beach = CobaltBeach.mount();
        current.props = SceneProps.open(mc.level).ground(65)
                .collider(new Vec3(-2, 65, 0), new Vec3(-1, 66, 1))
                .collider(new Vec3(0, 65, 0), new Vec3(1, 66, 1));
        current.disc = current.props.physicalItem(new ItemStack(Items.MUSIC_DISC_MALL), CobaltRoom.DISC,
                new Vector3f(.28f, .28f, .025f), .15f).scale(.8f, .8f, .8f);
        current.room = new CobaltRoom(current.stage, current.props);
        // WaterEffects owns the simulation tick and may run before this demo's tick listener.
        // Disable coupling at construction time so the room phase can never create a foot wave
        // during that first event-order window.
        // Keep the optical surface well below the dry collision top. The coupling tolerance
        // intentionally includes almost-at-surface feet; a 1.0 m rest depth would count dry sand
        // at y=65 as already submerged, while a near-surface depth also lets depth jitter paint
        // water over the beach.
        current.water = WaterEffects.open(mc.level, .5, 64, .5, 128, .25f, .55f)
                .followPlayer(true).playerInteraction(false).renderEnabled(false)
                .terrainHeight((x, z) -> CobaltCoast.height((float) x, (float) z))
                .optics(WaterOptics.CLEAR_COAST);
        current.prompt = current.overlay.prompt(new Vec3(.5, 65.75, -.12), "F", "Play");
        current.player = current.overlay.track(current.track.title(), current.track.artist(), current.track.album(), current.track.durationSeconds());
        current.controls = SceneControls.open().key(GLFW.GLFW_KEY_F, () -> play(current))
                .key(GLFW.GLFW_KEY_BACKSPACE, CobaltBlueDemo::stop)
                .mouse(action -> {
                    if (session != current || current.sequence.playing()) return;
                    if (action == GLFW.GLFW_PRESS && current.sequence.phase() == CobaltSequence.Phase.WAITING) {
                        if (current.props.grab(mc.player.getEyePosition(), mc.player.getLookAngle(), 6).isPresent()) current.sequence.grab();
                    } else if (action == GLFW.GLFW_RELEASE) { current.props.release(); current.sequence.release(); }
                }).scroll(delta -> current.props.holdDistance(current.props.holdDistance() + (float) delta * .18f));
        mc.setScreen(null); mc.mouseHandler.grabMouse();
        // The stage and its client render sections are warm before this point;
        // only now is it safe to reveal the scene through the black transition.
        current.fade.resume();
        LOGGER.info("COBALT_BLUE_INITIALIZED hudHidden={} fadeSeconds={} room={}x{}x{} beachModel={} beachInstances={} track={} durationSeconds={}",
                mc.options.hideGui, 1.4f, CobaltRoom.MAX_X - CobaltRoom.MIN_X + 1,
                CobaltRoom.CEILING_Y - CobaltRoom.FLOOR_Y + 1,
                CobaltRoom.MAX_Z - CobaltRoom.MIN_Z + 1, CobaltBeach.MODEL, CobaltBeach.activeInstances(),
                current.track.title(), current.track.durationSeconds());
    }
    private static void insert(Session current) {
        if (!current.sequence.insert()) return;
        current.insertionStart = current.disc.position(); current.props.detachPhysics(current.disc);
    }
    private static void play(Session current) {
        if (session != current || !current.prompt.visible() || !current.sequence.ready()) return;
        current.voice = current.track.playAt(CobaltRoom.SOURCE, 1);
        current.sequence.play(); current.prompt.visible(false); current.player.visible(true);
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event) {
        var mc = Minecraft.getInstance();
        requestAutoWorld(mc);
        var current = session;
        if (current == null || current.props == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if (current.sequence.phase() == CobaltSequence.Phase.INSERTING) {
            float t = current.sequence.insertion(partial);
            var position = current.insertionStart.lerp(CobaltRoom.SLOT, CobaltSequence.ease(Math.min(1, t * 3)))
                    .add(0, -.86 * CobaltSequence.ease(Math.max(0, (t - .25f) / .75f)), 0);
            current.disc.position(position).rotation(0, CobaltSequence.ease(Math.min(1, t * 3)) * 90, 0);
        } else if (current.sequence.ready() || current.sequence.playing()) current.disc.visible(false);
        if (current.sequence.playing()) current.room.sample(current.sequence.playbackTime(partial));
    }
    private static void requestAutoWorld(Minecraft mc) {
        if (!AUTO || autoWorldRequested || mc.level != null || mc.player != null || mc.getOverlay() != null) return;
        if (++autoWorldTicks < 120) return;
        autoWorldRequested = true;
        var world = System.getProperty("kasuga.cobalt.world", "graal-shader-test");
        LOGGER.info("COBALT_BLUE_AUTO_WORLD_REQUEST world={}", world);
        try {
            if (mc.getLevelSource().levelExists(world)) {
                mc.createWorldOpenFlows().openWorld(world,
                        () -> LOGGER.info("COBALT_BLUE_AUTO_WORLD_OPENED world={}", world));
            } else {
                var settings = new net.minecraft.world.level.LevelSettings(world, GameType.CREATIVE, false,
                        Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(world, settings, WorldOptions.defaultWithRandomSeed(),
                        WorldPresets::createNormalWorldDimensions, mc.screen);
                LOGGER.info("COBALT_BLUE_AUTO_WORLD_CREATED world={}", world);
            }
        } catch (RuntimeException failure) {
            autoWorldRequested = false;
            autoWorldTicks = 0;
            LOGGER.warn("COBALT_BLUE_AUTO_WORLD_FAILED world={}", world, failure);
        }
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("kasuga_demo", path); }
    static final class Session {
        final SceneTrack track;
        final CobaltSequence sequence = new CobaltSequence();
        volatile MinecraftStage stage;
        volatile RuntimeException failure;
        int wait;
        boolean initialized, cloudEnabled, skyEnabled;
        SceneWarmup warmup;
        SkyCloudSettings cloudSettings;
        SceneProps props;
        SceneProps.Prop disc;
        Vec3 previousDisc, insertionStart;
        CobaltBeach beach;
        WaterSurface water;
        SceneControls controls;
        SceneOverlay overlay;
        SceneOverlay.Fade fade;
        SceneOverlay.Prompt prompt;
        SceneOverlay.Track player;
        SpatialAudio.Voice voice;
        CobaltFootsteps footsteps;
        CobaltRoom room;
        boolean hideGuiBefore, silentBefore, uiCaptured;
        boolean autoGrabbed, autoInserted, autoPlayed, autoWaded, autoWaterLogged;
        int autoTicks, autoReadyTicks, autoWadeTicks, autoFootstepsBeforeOcean;
        Session(SceneTrack track) { this.track = track; footsteps = new CobaltFootsteps(); }
    }
}
