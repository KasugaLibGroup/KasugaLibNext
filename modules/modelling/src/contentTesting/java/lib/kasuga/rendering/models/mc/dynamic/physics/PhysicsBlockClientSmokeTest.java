package lib.kasuga.rendering.models.mc.dynamic.physics;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.scene.SceneProps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.UUID;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Hidden-client regression for the vanilla block fallback renderer. The same
 * body is rendered in open space, rotated into a wall, and moved back out;
 * collision ownership remains in MinecraftBlockPhysics for every phase.
 */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class PhysicsBlockClientSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.physics.auto");
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private static final BlockPos REFERENCE = new BlockPos(0, 65, 2);
    private static final BlockPos WALL = new BlockPos(0, 65, 3);
    private static final Vec3 PLAYER = new Vec3(.5, 65, -5.5);
    // The reference block and the physics body use exactly the same center and
    // camera. This makes the open-space framebuffer comparison meaningful.
    private static final Vec3 OPEN = new Vec3(.5, 65.5, 2.5);
    private static final Vec3 EMBEDDED = new Vec3(.5, 65.5, 2.82);
    private static final Vec3 RECOVERED = new Vec3(.5, 66.2, 2.15);
    private static final BlockState DIAGNOSTIC_STATE = Blocks.BLUE_CONCRETE.defaultBlockState();
    private static final EnumSet<Phase> CAPTURED = EnumSet.noneOf(Phase.class);
    private static boolean autoWorldRequested;
    private static int autoWorldTicks;
    private static volatile boolean preparationRequested;
    private static volatile boolean preparationComplete;
    private static boolean referenceRequested;
    private static boolean referenceCaptured;
    private static boolean referenceRemovalRequested;
    private static int referenceVisibleTicks;
    private static boolean started;
    private static boolean passed;
    private static boolean embeddedFallback;
    private static boolean openSpaceFallback;
    private static boolean recoveredFallback;
    private static boolean terrainContact;
    private static int ticks;
    private static int referenceRenderFrames;
    private static boolean chamberRenderInvalidated;
    private static boolean clientAnchorApplied;
    private static int referenceProbeAttempts;
    private static BlockState lastReferenceState;
    private static String pendingCapture;
    private static SceneProps smokeScene;
    private static MinecraftBlockRigidBody body;
    private static Metrics referenceMetrics;
    private static Metrics openMetrics;
    private static Metrics embeddedMetrics;
    private static Metrics recoveredMetrics;
    private static lib.kasuga.scene.SceneWarmup fixtureWarmup;

    private PhysicsBlockClientSmokeTest() {}

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || passed) return;
        Minecraft minecraft = Minecraft.getInstance();
        // Freeze only the identity comparison at render time, after the real
        // simulation step. Otherwise sub-tick gravity changes the silhouette
        // and conflates lighting errors with a physically falling block.
        if (body != null && phase() == Phase.OPEN) {
            body.body().teleport(new Vector3f((float) OPEN.x, (float) OPEN.y, (float) OPEN.z), new Quaternionf());
        }
        minecraft.setWindowActive(true);
        if (pendingCapture != null || minecraft.level == null || minecraft.getOverlay() != null
                || minecraft.screen != null) return;
        if (!started && !referenceCaptured && referenceVisibleTicks >= 12 && referenceRenderFrames >= 30
                && minecraft.level.getBlockState(REFERENCE).is(DIAGNOSTIC_STATE.getBlock())) {
            if (fixtureFrameReady(minecraft)) {
                pendingCapture = "00_reference";
            } else if (++referenceProbeAttempts >= 40) {
                throw new IllegalStateException("Physics smoke fixture never became visible in the final framebuffer"
                        + " referenceRenderFrames=" + referenceRenderFrames
                        + " referenceVisibleTicks=" + referenceVisibleTicks);
            } else {
                referenceRenderFrames = 0;
                if (minecraft.levelRenderer != null) minecraft.levelRenderer.allChanged();
                LOGGER.info("PHYSICS_BLOCK_SMOKE_WAIT_FOR_FRAME attempt={} reason=fixture_not_visible",
                        referenceProbeAttempts);
            }
        } else if (started && body != null) {
            Phase phase = phase();
            if (phase == Phase.OPEN && ticks >= 20 && !CAPTURED.contains(phase)) pendingCapture = "01_open";
            else if (phase == Phase.EMBEDDED && !CAPTURED.contains(phase)) pendingCapture = "02_embedded_rotated";
            else if (phase == Phase.RECOVERED && ticks >= 100 && !CAPTURED.contains(phase)) pendingCapture = "03_recovered";
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!ENABLED || passed) return;
        Minecraft minecraft = Minecraft.getInstance();
        requestAutoWorld(minecraft);
        if (minecraft.player == null || minecraft.level == null || minecraft.getSingleplayerServer() == null) return;
        if (!preparationComplete || minecraft.level.dimension() != Level.OVERWORLD
                || minecraft.getOverlay() != null
                || !clientChamberReady(minecraft)) {
            requestPreparation(minecraft);
            return;
        }
        minecraft.player.getAbilities().mayfly = true;
        minecraft.player.getAbilities().flying = true;
        if (!clientAnchorApplied) {
            minecraft.player.setPos(PLAYER.x, PLAYER.y, PLAYER.z);
            minecraft.player.setDeltaMovement(Vec3.ZERO);
            minecraft.player.setOnGround(true);
            clientAnchorApplied = true;
        }
        minecraft.options.hideGui = true;
        minecraft.setScreen(null);
        minecraft.setWindowActive(true);
        aimAt(minecraft, OPEN);
        if (!chamberRenderInvalidated && minecraft.levelRenderer != null) {
            // The fixture is written after quick-play joins. Force one client
            // chunk rebuild once the fixture is visible so the first capture
            // is a real world frame instead of the hidden-client clear buffer.
            minecraft.levelRenderer.allChanged();
            chamberRenderInvalidated = true;
        }
        if (!referenceRequested) {
            requestBlock(minecraft, REFERENCE, DIAGNOSTIC_STATE);
            referenceRequested = true;
            return;
        }
        if (!referenceCaptured) {
            BlockState state = minecraft.level.getBlockState(REFERENCE);
            if (state.is(DIAGNOSTIC_STATE.getBlock())) {
                if (fixtureWarmup == null) fixtureWarmup = lib.kasuga.scene.SceneWarmup.forArea(minecraft.level,
                        new BlockPos(-8, 64, -10), new BlockPos(8, 80, 8)).requireBlock(REFERENCE, DIAGNOSTIC_STATE);
                if (!fixtureWarmup.ready()) return;
                referenceVisibleTicks++;
                if (lastReferenceState != state && minecraft.levelRenderer != null) {
                    minecraft.levelRenderer.allChanged();
                    lastReferenceState = state;
                    referenceRenderFrames = 0;
                }
            } else {
                referenceVisibleTicks = 0;
                referenceRenderFrames = 0;
                lastReferenceState = state;
            }
            return;
        }
        if (!referenceRemovalRequested) {
            requestBlock(minecraft, REFERENCE, Blocks.AIR.defaultBlockState());
            referenceRemovalRequested = true;
            return;
        }
        if (!started) {
            if (!minecraft.level.getBlockState(REFERENCE).isAir()) return;
            if (lastReferenceState != minecraft.level.getBlockState(REFERENCE)
                    && minecraft.levelRenderer != null) {
                minecraft.levelRenderer.allChanged();
                lastReferenceState = minecraft.level.getBlockState(REFERENCE);
            }
            MinecraftBlockPhysics.clear();
            if (smokeScene == null) smokeScene = SceneProps.open(minecraft.level);
            body = MinecraftBlockPhysics.spawn(minecraft.level, OPEN, DIAGNOSTIC_STATE)
                    .orElseThrow(() -> new IllegalStateException("Box3D physics block could not be spawned"));
            started = true;
            LOGGER.info("PHYSICS_BLOCK_SMOKE_START activeCount={} position={}",
                    MinecraftBlockPhysics.activeCount(), OPEN);
        }

        ticks++;
        if (ticks <= 5 || ticks % 20 == 0) {
            LOGGER.info("PHYSICS_BLOCK_SMOKE_TRACE tickBegin={} phase={} activeCount={}", ticks, phase(),
                    MinecraftBlockPhysics.activeCount());
        }
        Phase phase = phase();
        Vec3 position = switch (phase) {
            case OPEN -> OPEN;
            case EMBEDDED -> EMBEDDED;
            case RECOVERED -> RECOVERED;
        };
        Quaternionf rotation = switch (phase) {
            case OPEN -> new Quaternionf();
            case EMBEDDED -> new Quaternionf().rotateY((float) Math.toRadians(45));
            case RECOVERED -> new Quaternionf().rotateXYZ(0, (float) Math.toRadians(90), (float) Math.toRadians(25));
        };
        body.body().teleport(new Vector3f((float) position.x, (float) position.y, (float) position.z), rotation);
        aimAt(minecraft, OPEN);
        PhysicsLightSampler.Sample sample = body.lightSample(minecraft.level);
        if (ticks <= 5 || ticks % 20 == 0) {
            LOGGER.info("PHYSICS_BLOCK_SMOKE_TRACE tickSample={} fallback={} packedLight={}", ticks,
                    sample.fallback(), sample.packedLight());
        }
        // Ignore the first few open-space frames while the client light
        // cache settles. Each authored phase is then evaluated independently;
        // recovered space must return to the ordinary vanilla path too.
        if (phase == Phase.OPEN && ticks >= 20) openSpaceFallback |= sample.fallback();
        if (phase == Phase.EMBEDDED) embeddedFallback |= sample.fallback();
        if (phase == Phase.EMBEDDED) terrainContact |= MinecraftBlockPhysics.contacts(body).stream()
                .anyMatch(contact -> contact.touching() && contact.other().isEmpty());
        if (phase == Phase.RECOVERED && ticks >= 80) recoveredFallback |= sample.fallback();
        if (!Float.isFinite(sample.indirectLight().x)
                || !Float.isFinite(sample.indirectLight().y)
                || !Float.isFinite(sample.indirectLight().z)) {
            throw new IllegalStateException("Physics light sample became non-finite");
        }
        if (ticks == 20 || ticks == 60 || ticks == 100) {
            LOGGER.info("PHYSICS_BLOCK_SMOKE_STATE phase={} fallback={} packedLight={} activeCount={}",
                    phase, sample.fallback(), sample.packedLight(), MinecraftBlockPhysics.activeCount());
        }
        // Final verification is performed from RenderFrameEvent.Post, after
        // the recovered framebuffer has actually been captured. A tick can
        // happen before that frame and must not reject a valid run.
        if (ticks >= 240 && !passed)
            throw new IllegalStateException("Physics block smoke did not reach a complete post-render frame"
                    + " referenceMetrics=" + referenceMetrics
                    + " openMetrics=" + openMetrics
                    + " embeddedMetrics=" + embeddedMetrics
                    + " recoveredMetrics=" + recoveredMetrics);
    }

    @SubscribeEvent
    public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (pendingCapture != null && minecraft.level != null && minecraft.getOverlay() == null
                && minecraft.screen == null) {
            String name = pendingCapture;
            pendingCapture = null;
            captureMainFrame(minecraft, name);
        }
        if (!started) {
            if (!referenceCaptured && minecraft.level != null
                    && minecraft.getOverlay() == null && minecraft.screen == null
                    && minecraft.level.getBlockState(REFERENCE).is(DIAGNOSTIC_STATE.getBlock())) {
                referenceRenderFrames++;
            }
            return;
        }
        if (body == null) return;
    }

    private static void captureMainFrame(Minecraft minecraft, String name) {
        try {
            try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                Metrics metrics = Metrics.sample(image);
                Path directory = minecraft.gameDirectory.toPath().resolve("screenshots").resolve("kasuga_physics_block");
                Files.createDirectories(directory);
                Path file = directory.resolve(name + ".png");
                image.writeToFile(file);
                LOGGER.info("PHYSICS_BLOCK_SMOKE_FRAME file={} size={}x{} center={} centerLuma={} cropLuma={} cropDarkPixels={} cropNonBlackPixels={} cropVariance={} player={} yaw={} pitch={} camera={}",
                        file, metrics.width(), metrics.height(),
                        Integer.toUnsignedString(metrics.centerColor()), metrics.centerLuma(),
                        metrics.cropLuma(), metrics.cropDarkPixels(), metrics.cropNonBlackPixels(), metrics.cropVariance(),
                        minecraft.player == null ? null : minecraft.player.position(),
                        minecraft.player == null ? 0 : minecraft.player.getYRot(),
                        minecraft.player == null ? 0 : minecraft.player.getXRot(),
                        minecraft.gameRenderer.getMainCamera().getPosition());
                LOGGER.info("PHYSICS_BLOCK_SMOKE_BLUE region={} pixels={}",
                        metrics.blueRegion(), metrics.blueRegion().pixels());
                switch (name) {
                    case "00_reference" -> {
                        referenceMetrics = metrics;
                        referenceCaptured = true;
                        LOGGER.info("PHYSICS_BLOCK_SMOKE_REFERENCE_READY metrics={}", referenceMetrics);
                    }
                    case "01_open" -> {
                        try (var stream = Files.newInputStream(directory.resolve("00_reference.png"));
                             var reference = NativeImage.read(stream)) {
                            compareTerrainSurface(reference, image);
                        }
                        openMetrics = metrics; CAPTURED.add(Phase.OPEN);
                    }
                    case "02_embedded_rotated" -> { embeddedMetrics = metrics; CAPTURED.add(Phase.EMBEDDED); }
                    case "03_recovered" -> {
                        recoveredMetrics = metrics;
                        CAPTURED.add(Phase.RECOVERED);
                        verifyComplete(minecraft);
                    }
                    default -> throw new IllegalStateException("Unknown physics smoke capture: " + name);
                }
            }
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Physics block smoke framebuffer capture failed: " + name, failure);
        }
    }

    private static boolean fixtureFrameReady(Minecraft minecraft) {
        try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            Metrics metrics = Metrics.sample(image);
            return metrics.blueRegion().pixels() >= 256 && metrics.cropVariance() >= .0001;
        } catch (RuntimeException failure) {
            LOGGER.debug("Physics smoke fixture framebuffer probe failed", failure);
            return false;
        }
    }

    private static void compareTerrainSurface(NativeImage reference, NativeImage physical) {
        if (reference.getWidth() != physical.getWidth() || reference.getHeight() != physical.getHeight())
            throw new IllegalStateException("Terrain/physics comparison changed viewport size");
        int[] histogram = new int[256];
        long sum = 0;
        int samples = 0;
        int cx = reference.getWidth() / 2, cy = reference.getHeight() / 2;
        for (int y = cy - 126; y < cy + 126; y++) for (int x = cx - 126; x < cx + 126; x++) {
            boolean inside = true;
            for (int dy = -2; dy <= 2 && inside; dy++) for (int dx = -2; dx <= 2; dx++) {
                if (!Metrics.isDiagnosticBlue(reference.getPixelRGBA(x + dx, y + dy))) { inside = false; break; }
            }
            if (!inside) continue;
            int a = reference.getPixelRGBA(x, y), b = physical.getPixelRGBA(x, y);
            for (int shift : new int[]{0, 8, 16}) {
                int error = Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255));
                histogram[error]++; sum += error; samples++;
            }
        }
        if (samples < 768) throw new IllegalStateException("Terrain comparison has no diagnostic surface");
        double mean = sum / (double) samples;
        int cumulative = 0, p95 = 0;
        for (; p95 < 255; p95++) { cumulative += histogram[p95]; if (cumulative >= samples * .95) break; }
        LOGGER.info("PHYSICS_TERRAIN_PARITY samples={} meanByteError={} p95ByteError={}", samples, mean, p95);
        if (mean > 3 || p95 > 8)
            throw new IllegalStateException("Physical conversion changed terrain lighting: mean=" + mean + " p95=" + p95);
    }

    private static void verifyComplete(Minecraft minecraft) {
        if (passed) return;
        if (!embeddedFallback || openSpaceFallback || recoveredFallback || !terrainContact
                || MinecraftBlockPhysics.activeCount() != 1
                || referenceMetrics == null || openMetrics == null
                || embeddedMetrics == null || recoveredMetrics == null) {
            throw new IllegalStateException("Physics block fallback state machine failed"
                    + " embeddedFallback=" + embeddedFallback
                    + " openSpaceFallback=" + openSpaceFallback
                    + " recoveredFallback=" + recoveredFallback
                    + " activeCount=" + MinecraftBlockPhysics.activeCount()
                    + " terrainContact=" + terrainContact
                    + " referenceMetrics=" + referenceMetrics
                    + " openMetrics=" + openMetrics
                    + " embeddedMetrics=" + embeddedMetrics
                    + " recoveredMetrics=" + recoveredMetrics);
        }
        verifyVisualEvidence();
        passed = true;
        if (smokeScene != null) {
            smokeScene.close();
            smokeScene = null;
        }
        LOGGER.info("PHYSICS_BLOCK_SMOKE_PASS captured={} embeddedFallback={} openSpaceFallback={} terrainContact={} activeCount={} visual=reference/open/embedded/recovered camera={} yaw={} pitch={}",
                CAPTURED, embeddedFallback, openSpaceFallback, terrainContact, MinecraftBlockPhysics.activeCount(),
                minecraft.gameRenderer.getMainCamera().getPosition(),
                minecraft.gameRenderer.getMainCamera().getYRot(),
                minecraft.gameRenderer.getMainCamera().getXRot());
        minecraft.stop();
    }

    private static void aimAt(Minecraft minecraft, Vec3 target) {
        Vec3 eye = minecraft.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        minecraft.player.setYRot(yaw);
        minecraft.player.setXRot(pitch);
        minecraft.player.setYHeadRot(yaw);
        minecraft.player.setYBodyRot(yaw);
    }

    private static Phase phase() {
        if (ticks < 40) return Phase.OPEN;
        if (ticks < 80) return Phase.EMBEDDED;
        return Phase.RECOVERED;
    }

    private static void requestPreparation(Minecraft minecraft) {
        if (preparationRequested) return;
        preparationRequested = true;
        var server = minecraft.getSingleplayerServer();
        UUID playerId = minecraft.player.getUUID();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (level == null || player == null) throw new IllegalStateException("Physics smoke world is unavailable");
            level.setDayTime(6000L);
            // Clear well above the player. The quick-play fixture can contain
            // ordinary terrain above y=69; leaving that terrain in place puts
            // the camera under an unlit ceiling and makes every framebuffer
            // capture look black even when the physics block is rendered.
            // Include the camera/player volume. The old fixture left PLAYER
            // at z=-5.5 inside random terrain outside the cleared z>=-2 area;
            // vanilla suffocation escape then moved the reference camera.
            for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(-8, 65, -10), new BlockPos(8, 128, 8)))
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            for (int x = -8; x <= 8; x++) for (int z = -10; z <= 8; z++)
                level.setBlock(new BlockPos(x, 64, z), Blocks.WHITE_CONCRETE.defaultBlockState(), 3);
            for (int x = -4; x <= 4; x++) for (int y = 65; y <= 69; y++)
                level.setBlock(new BlockPos(x, y, 3), Blocks.WHITE_CONCRETE.defaultBlockState(), 3);
            level.setBlock(REFERENCE, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(new BlockPos(0, 67, 0), Blocks.SEA_LANTERN.defaultBlockState(), 3);
            // Keep the diagnostic chamber readable even when the quick-play
            // world starts at night. These lights belong to the smoke world;
            // they do not participate in the runtime fallback implementation.
            for (int x = -3; x <= 3; x += 3) for (int z = -1; z <= 2; z += 3)
                level.setBlock(new BlockPos(x, 69, z), Blocks.SEA_LANTERN.defaultBlockState(), 3);
            if (!player.teleportTo(level, PLAYER.x, PLAYER.y, PLAYER.z, java.util.Set.of(), 0, 5))
                throw new IllegalStateException("Physics smoke player teleport failed");
            player.setNoGravity(true);
            player.setDeltaMovement(Vec3.ZERO);
            preparationComplete = true;
        });
    }

    private static void requestBlock(Minecraft minecraft, BlockPos position, BlockState state) {
        var server = minecraft.getSingleplayerServer();
        UUID playerId = minecraft.player.getUUID();
        server.execute(() -> {
            ServerLevel level = server.overworld();
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (level == null || player == null) throw new IllegalStateException("Physics smoke world is unavailable");
            level.setBlock(position, state, 3);
        });
    }

    private static void requestAutoWorld(Minecraft minecraft) {
        if (autoWorldRequested || minecraft.level != null || minecraft.player != null || minecraft.getOverlay() != null)
            return;
        if (++autoWorldTicks < 120) return;
        autoWorldRequested = true;
        var world = System.getProperty("kasuga.physics.world", "graal-shader-test");
        LOGGER.info("PHYSICS_BLOCK_SMOKE_WORLD_REQUEST world={}", world);
        try {
            if (minecraft.getLevelSource().levelExists(world)) {
                minecraft.createWorldOpenFlows().openWorld(world,
                        () -> LOGGER.info("PHYSICS_BLOCK_SMOKE_WORLD_OPENED world={}", world));
            } else {
                var settings = new net.minecraft.world.level.LevelSettings(
                        world,
                        net.minecraft.world.level.GameType.CREATIVE,
                        false,
                        net.minecraft.world.Difficulty.NORMAL,
                        true,
                        new net.minecraft.world.level.GameRules(),
                        net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
                minecraft.createWorldOpenFlows().createFreshLevel(world, settings,
                        net.minecraft.world.level.levelgen.WorldOptions.defaultWithRandomSeed(),
                        net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions,
                        minecraft.screen);
                LOGGER.info("PHYSICS_BLOCK_SMOKE_WORLD_CREATED world={}", world);
            }
        } catch (RuntimeException failure) {
            autoWorldRequested = false;
            autoWorldTicks = 0;
            LOGGER.warn("PHYSICS_BLOCK_SMOKE_WORLD_FAILED world={}", world, failure);
        }
    }

    private static boolean clientChamberReady(Minecraft minecraft) {
        if (minecraft.level == null || !minecraft.level.hasChunkAt(REFERENCE)) return false;
        // A single anchor block is not enough to prove that the server-side
        // fixture has arrived on the client. Starting the reference capture at
        // that point races the chunk update and can produce a partial wall,
        // which then contaminates the framebuffer comparison with sky pixels.
        for (int x : new int[]{-4, 0, 4}) {
            for (int z : new int[]{-2, 0, 2, 6}) {
                if (!minecraft.level.getBlockState(new BlockPos(x, 64, z)).is(Blocks.WHITE_CONCRETE)) return false;
            }
        }
        for (int x : new int[]{-4, 0, 4}) {
            for (int y = 65; y <= 69; y++) {
                if (!minecraft.level.getBlockState(new BlockPos(x, y, 3)).is(Blocks.WHITE_CONCRETE)) return false;
            }
        }
        return minecraft.level.getBlockState(new BlockPos(0, 67, 0)).is(Blocks.SEA_LANTERN)
                && minecraft.level.getBlockState(new BlockPos(0, 69, 2)).is(Blocks.SEA_LANTERN);
    }

    private static void verifyVisualEvidence() {
        if (referenceMetrics.cropDarkPixels() == referenceMetrics.cropPixels()
                || openMetrics.cropDarkPixels() == openMetrics.cropPixels()
                || embeddedMetrics.cropDarkPixels() == embeddedMetrics.cropPixels()
                || recoveredMetrics.cropDarkPixels() == recoveredMetrics.cropPixels()) {
            throw new IllegalStateException("Physics block framebuffer contains an all-black diagnostic crop");
        }
        if (referenceMetrics.blueRegion().pixels() < 256
                || openMetrics.blueRegion().pixels() < 256
                || embeddedMetrics.blueRegion().pixels() < 256
                || recoveredMetrics.blueRegion().pixels() < 256) {
            throw new IllegalStateException("Physics block diagnostic blue marker is missing from the final framebuffer: "
                    + "reference=" + referenceMetrics.blueRegion()
                    + " open=" + openMetrics.blueRegion()
                    + " embedded=" + embeddedMetrics.blueRegion()
                    + " recovered=" + recoveredMetrics.blueRegion());
        }
        if (relativeDifference(referenceMetrics.blueRegion().pixels(), openMetrics.blueRegion().pixels()) > .35) {
            throw new IllegalStateException("Open physics block does not preserve the vanilla reference marker: "
                    + "reference=" + referenceMetrics.blueRegion()
                    + " open=" + openMetrics.blueRegion());
        }
        if (colorDistance(referenceMetrics.centerColor(), openMetrics.centerColor()) > .45f
                || cropDifference(referenceMetrics, openMetrics) > .16f) {
            throw new IllegalStateException("Open physics block does not match the same-position vanilla reference: "
                    + "reference=" + referenceMetrics + " open=" + openMetrics);
        }
        if (openMetrics.cropNonBlackPixels() < 256 || openMetrics.cropVariance() < .0001
                || embeddedMetrics.cropLuma() < .015f || recoveredMetrics.cropLuma() < .015f) {
            throw new IllegalStateException("Physics block fallback became visually black: embedded="
                    + embeddedMetrics + " recovered=" + recoveredMetrics);
        }
        boolean embeddedChanged = cropDifference(openMetrics, embeddedMetrics) >= .002
                || !sameBlueRegion(openMetrics.blueRegion(), embeddedMetrics.blueRegion());
        boolean recoveredChanged = cropDifference(openMetrics, recoveredMetrics) >= .002
                || !sameBlueRegion(openMetrics.blueRegion(), recoveredMetrics.blueRegion());
        if (!embeddedChanged || !recoveredChanged) {
            throw new IllegalStateException("Physics block phases did not produce distinct framebuffer structure: open="
                    + openMetrics + " embedded=" + embeddedMetrics + " recovered=" + recoveredMetrics);
        }
        if (sameBlueRegion(openMetrics.blueRegion(), embeddedMetrics.blueRegion())
                && sameBlueRegion(openMetrics.blueRegion(), recoveredMetrics.blueRegion())) {
            throw new IllegalStateException("Physics block phases did not move the diagnostic marker in the final framebuffer: open="
                    + openMetrics.blueRegion() + " embedded=" + embeddedMetrics.blueRegion()
                    + " recovered=" + recoveredMetrics.blueRegion());
        }
    }

    private static double relativeDifference(int left, int right) {
        return Math.abs(left - right) / (double) Math.max(1, Math.max(left, right));
    }

    private static boolean sameBlueRegion(BlueRegion left, BlueRegion right) {
        return Math.abs(left.pixels() - right.pixels()) < 256
                && Math.abs(left.centerX() - right.centerX()) < 4
                && Math.abs(left.centerY() - right.centerY()) < 4
                && Math.abs(left.width() - right.width()) < 8
                && Math.abs(left.height() - right.height()) < 8;
    }

    private static double cropDifference(Metrics left, Metrics right) {
        return Math.abs(left.cropLuma() - right.cropLuma())
                + Math.abs(left.cropVariance() - right.cropVariance())
                + Math.abs(left.cropNonBlackPixels() - right.cropNonBlackPixels())
                / (double) Math.max(left.cropPixels(), right.cropPixels());
    }

    private static float colorDistance(int left, int right) {
        float r = (((left >>> 16) & 255) - ((right >>> 16) & 255)) / 255f;
        float g = (((left >>> 8) & 255) - ((right >>> 8) & 255)) / 255f;
        float b = ((left & 255) - (right & 255)) / 255f;
        return (float) Math.sqrt(r * r + g * g + b * b);
    }

    private record Metrics(int width, int height, int centerColor, double centerLuma,
                           double cropLuma, double cropVariance, int cropNonBlackPixels,
                           int cropDarkPixels, int cropPixels, BlueRegion blueRegion) {
        static Metrics sample(NativeImage image) {
            int width = image.getWidth(), height = image.getHeight();
            int centerX = width / 2, centerY = height / 2;
            int center = image.getPixelRGBA(centerX, centerY);
            int halfWidth = Math.min(160, width / 4), halfHeight = Math.min(160, height / 4);
            int minX = Math.max(0, centerX - halfWidth), maxX = Math.min(width, centerX + halfWidth);
            int minY = Math.max(0, centerY - halfHeight), maxY = Math.min(height, centerY + halfHeight);
            int focusHalfWidth = Math.min(130, width / 4), focusHalfHeight = Math.min(130, height / 4);
            int focusMinX = Math.max(0, centerX - focusHalfWidth), focusMaxX = Math.min(width, centerX + focusHalfWidth);
            int focusMinY = Math.max(0, centerY - focusHalfHeight), focusMaxY = Math.min(height, centerY + focusHalfHeight);
            double sum = 0, sumSquared = 0;
            int dark = 0, nonBlack = 0, count = 0;
            int bluePixels = 0;
            int blueMinX = width, blueMinY = height, blueMaxX = -1, blueMaxY = -1;
            for (int y = minY; y < maxY; y++) for (int x = minX; x < maxX; x++) {
                int color = image.getPixelRGBA(x, y);
                double value = luma(color);
                sum += value; sumSquared += value * value;
                if (value >= .02) nonBlack++; else dark++;
                count++;
                if (x >= focusMinX && x < focusMaxX && y >= focusMinY && y < focusMaxY
                        && isDiagnosticBlue(color)) {
                    bluePixels++;
                    blueMinX = Math.min(blueMinX, x);
                    blueMinY = Math.min(blueMinY, y);
                    blueMaxX = Math.max(blueMaxX, x);
                    blueMaxY = Math.max(blueMaxY, y);
                }
            }
            double average = count == 0 ? 0 : sum / count;
            return new Metrics(width, height, center, luma(center), average,
                    count == 0 ? 0 : Math.max(0, sumSquared / count - average * average),
                    nonBlack, dark, count,
                    new BlueRegion(bluePixels, blueMinX, blueMinY, blueMaxX, blueMaxY));
        }

        private static boolean isDiagnosticBlue(int color) {
            int red = color & 255;
            int green = (color >>> 8) & 255;
            int blue = (color >>> 16) & 255;
            // Find the dark-blue diagnostic block, rather than counting the
            // light-blue sky or the ocean. The marker is intentionally placed
            // at the camera target, so its connected region is stable across
            // the reference and physics phases.
            return red < 100 && blue > 60 && blue > red * 1.25 && blue > green * 1.15;
        }

        private static double luma(int color) {
            // NativeImage stores the downloaded RGBA pixel as ABGR in the int,
            // while BufferedImage.getRGB exposes ARGB. Luma is symmetric enough
            // for the two chroma channels, but decode the native layout here so
            // the metric remains explicit and stable.
            return (((color & 255) * .2126)
                    + (((color >>> 8) & 255) * .7152)
                    + (((color >>> 16) & 255) * .0722)) / 255d;
        }
    }

    private record BlueRegion(int pixels, int minX, int minY, int maxX, int maxY) {
        int centerX() {
            return pixels == 0 ? 0 : (minX + maxX) / 2;
        }

        int centerY() {
            return pixels == 0 ? 0 : (minY + maxY) / 2;
        }

        int width() {
            return pixels == 0 ? 0 : maxX - minX + 1;
        }

        int height() {
            return pixels == 0 ? 0 : maxY - minY + 1;
        }
    }

    private enum Phase { OPEN, EMBEDDED, RECOVERED }
}
