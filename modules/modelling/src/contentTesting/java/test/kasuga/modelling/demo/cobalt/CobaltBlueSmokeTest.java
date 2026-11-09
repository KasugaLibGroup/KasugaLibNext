package test.kasuga.modelling.demo.cobalt;

import com.mojang.logging.LogUtils;
import lib.kasuga.scene.SceneGround;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** Final-frame evidence for the explicit cobalt auto smoke run. */
@EventBusSubscriber(modid = "kasuga_lib", value = Dist.CLIENT)
final class CobaltBlueSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.cobalt.auto");
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> CAPTURED = new LinkedHashSet<>();
    private static CobaltBlueDemo.Session observed;
    private static boolean passed;
    private static boolean waterGateObserved;

    private CobaltBlueSmokeTest() {}

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || passed) return;
        var current = CobaltBlueDemo.session();
        if (current == null || current.props == null) {
            observed = null; CAPTURED.clear(); passed = false; return;
        }
        if (observed != current) {
            observed = current; CAPTURED.clear(); passed = false;
            waterGateObserved = false;
        }
        var phase = current.sequence.phase();
        if (phase == CobaltSequence.Phase.WAITING && current.fade != null && current.fade.alpha() > .95f)
            capture(current, phase, "01_room_black");
        if (phase == CobaltSequence.Phase.HOLDING)
            capture(current, phase, "02_record_grabbed");
        if (phase == CobaltSequence.Phase.READY && current.prompt != null && current.prompt.visible())
            capture(current, phase, "03_record_inserted_prompt");
        if (phase == CobaltSequence.Phase.OPENING && current.player != null && current.player.visible())
            capture(current, phase, "04_player_opening");
        if (phase == CobaltSequence.Phase.OPENING && current.sequence.playbackTime() >= 12f)
            capture(current, phase, "04_shore_without_water");
        if (phase == CobaltSequence.Phase.OCEAN && current.autoWaded)
            capture(current, phase, "05_cobalt_ocean");
        if (phase == CobaltSequence.Phase.OPENING && current.voice != null && current.voice.isPlaying()
                && (!current.voice.spatial() || current.voice.position().distanceTo(CobaltRoom.SOURCE) > 1e-4)) {
            throw new IllegalStateException("Cobalt blue music voice is not a positional source: "
                    + current.voice.position() + " spatial=" + current.voice.spatial());
        }
        if (phase != CobaltSequence.Phase.OCEAN && current.water != null && current.water.interactionCount() != 0) {
            throw new IllegalStateException("Cobalt blue created a water interaction before the ocean phase: "
                    + current.water.interactionCount());
        }
        if (current.water != null && current.water.renderEnabled() != (phase == CobaltSequence.Phase.OCEAN)) {
            throw new IllegalStateException("Cobalt blue water rendering gate drifted at phase " + phase
                    + ": renderEnabled=" + current.water.renderEnabled());
        }
        if (!waterGateObserved && phase != CobaltSequence.Phase.OCEAN && current.water != null) {
            waterGateObserved = true;
            LOGGER.info("COBALT_BLUE_SMOKE_WATER_GATE_PASS phase={} interactions={}", phase,
                    current.water.interactionCount());
        }
        if (phase == CobaltSequence.Phase.OCEAN && current.water != null && current.water.interactionCount() >= 4
                && current.footsteps != null && current.autoWaded
                && current.footsteps.emittedCount() > current.autoFootstepsBeforeOcean
                && current.footsteps.lastVoice() != null && current.footsteps.lastVoice().spatial()) {
            var minecraft = Minecraft.getInstance();
            var feet = minecraft.player.position();
            var bounds = minecraft.player.getBoundingBox();
            int minX = (int) Math.floor(bounds.minX), maxX = (int) Math.floor(Math.nextDown(bounds.maxX));
            int minZ = (int) Math.floor(bounds.minZ), maxZ = (int) Math.floor(Math.nextDown(bounds.maxZ));
            float collisionTop = Float.NEGATIVE_INFINITY;
            BlockPos bedPosition = null;
            var bed = Blocks.AIR.defaultBlockState();
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                float top = CobaltCoast.collisionHeight(x + .5f, z + .5f);
                var candidate = new BlockPos(x, CobaltCoast.topBlockY(x + .5f, z + .5f), z);
                var state = minecraft.level.getBlockState(candidate);
                if (state.is(SceneGround.block()) && top > collisionTop) {
                    collisionTop = top;
                    bedPosition = candidate;
                    bed = state;
                }
            }
            if (bedPosition == null)
                throw new IllegalStateException("Cobalt blue wading path lost its authored terrain surface: " + bed);
            if (!minecraft.level.getBlockState(bedPosition.above()).isAir()
                    || !minecraft.level.getBlockState(bedPosition.below()).isAir()
                    || !minecraft.player.onGround() || Math.abs(feet.y - collisionTop) > .08
                    || current.water.surfaceY() - feet.y < .05
                    || current.water.surfaceY() >= minecraft.player.getEyeY())
                throw new IllegalStateException("Cobalt blue feet are not submerged over the physical bed: feet="
                        + feet + " terrain=" + collisionTop + " surface=" + current.water.surfaceY()
                        + " grounded=" + minecraft.player.onGround());
            if (current.voice == null || !current.voice.isPlaying())
                throw new IllegalStateException("Cobalt blue music is not playing during the ocean phase");
            if (current.beach == null || CobaltBeach.activeInstances() != 1)
                throw new IllegalStateException("Cobalt blue custom-model beach is not mounted exactly once");
            if (!current.autoGrabbed || !current.autoInserted || !current.water.simulation().isFinite()
                    || current.water.simulation().maximumDeviation() < 1e-5)
                throw new IllegalStateException("Cobalt blue physical grab, insertion or water simulation was not exercised");
            capture(current, phase, "06_cobalt_ocean_wade");
            if (!passed) {
                if (CAPTURED.size() != 7)
                    throw new IllegalStateException("Cobalt blue did not capture every required phase: " + CAPTURED);
                int mountedAtCapture = CobaltBeach.activeInstances();
                long waterInteractions = current.water.interactionCount();
                boolean waterVisible = current.water.renderEnabled();
                int footsteps = current.footsteps.emittedCount();
                boolean footstepsSpatial = current.footsteps.lastVoice().spatial();
                boolean musicSpatial = current.voice != null && current.voice.spatial();
                var bedBlock = bed.getBlock();
                boolean hudHiddenAtCapture = minecraft.options.hideGui;
                float feetAtCapture = (float) feet.y;
                float waterSurfaceAtCapture = current.water.surfaceY();
                CobaltBlueDemo.stop();
                if (CobaltBeach.activeInstances() != 0)
                    throw new IllegalStateException("Cobalt blue beach instance leaked after stop");
                passed = true;
                LOGGER.info("COBALT_BLUE_SMOKE_PASS captures={} waterInteractions={} waterVisible={} footsteps={} footstepsSpatial={} musicSpatial={} beachModel={} beachInstancesAtCapture={} beachInstancesAfterStop={} bed={} hudHidden={} feetY={} surfaceY={} promptExact=F Play waterGate=roomZeroOceanWading physicalGrabAndSlot=true movement=vanillaInput",
                        CAPTURED, waterInteractions, waterVisible, footsteps, footstepsSpatial,
                        musicSpatial, CobaltBeach.MODEL, mountedAtCapture, CobaltBeach.activeInstances(),
                        bedBlock, hudHiddenAtCapture, feetAtCapture, waterSurfaceAtCapture);
                Minecraft.getInstance().stop();
            }
        }
    }

    private static void capture(CobaltBlueDemo.Session current, CobaltSequence.Phase phase, String name) {
        if (!CAPTURED.add(name)) return;
        var minecraft = Minecraft.getInstance();
        try {
            Path directory = minecraft.gameDirectory.toPath().resolve("screenshots").resolve("kasuga_cobalt_blue");
            Files.createDirectories(directory);
            try (var image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                Path file = directory.resolve(name + ".png");
                image.writeToFile(file);
                if (phase == CobaltSequence.Phase.HOLDING || phase == CobaltSequence.Phase.READY) {
                    int blue = 0, samples = 0;
                    for (int y = image.getHeight() / 20; y < image.getHeight() * 9 / 20; y += 3) {
                        for (int x = image.getWidth() * 3 / 20; x < image.getWidth() * 17 / 20; x += 3) {
                            int pixel = image.getPixelRGBA(x, y);
                            int r = pixel & 255, g = pixel >>> 8 & 255, b = pixel >>> 16 & 255;
                            if (b > r * 1.2 && b - r > 4 && b > g * 1.08) blue++;
                            samples++;
                        }
                    }
                    if (blue > samples * .01)
                        throw new IllegalStateException("Cobalt blue room revealed missing terrain: skyFraction="
                                + (double) blue / samples + " phase=" + phase + " file=" + file);
                }
                LOGGER.info("COBALT_BLUE_SMOKE_FRAME phase={} file={} size={}x{} fadeAlpha={} waterInteractions={}",
                        phase, file, image.getWidth(), image.getHeight(), current.fade == null ? -1 : current.fade.alpha(),
                        current.water == null ? -1 : current.water.interactionCount());
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot save Cobalt blue evidence for " + phase, failure);
        }
    }
}
