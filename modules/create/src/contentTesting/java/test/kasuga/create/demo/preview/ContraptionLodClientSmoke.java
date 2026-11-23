package test.kasuga.create.demo.preview;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import lib.kasuga.rendering.models.mc.compat.voxy.VoxyCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Exercises a native rotating windmill, including out-of-track rendering and explicit server removal. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ContraptionLodClientSmoke {
    private static final long START = System.nanoTime();
    private static final BlockPos BEARING = CreateWindmillExample.BEARING;
    private static boolean worldRequested;
    private static boolean finished;
    private static int phase;
    private static int entityId;
    private static long arrived;
    private static float[] firstDepth;
    private static CompletableFuture<Boolean> idleCheck;
    private static long idleSince;

    private ContraptionLodClientSmoke() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (finished || !Boolean.getBoolean("kasuga.testContraptionLod")) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (System.nanoTime() - START > 300_000_000_000L) throw new IllegalStateException("Contraption LOD timeout in phase " + phase);
            if (mc.player == null || mc.level == null) {
                if (!worldRequested && mc.getOverlay() == null && mc.screen instanceof TitleScreen) {
                    worldRequested = true;
                    String world = "contraption-lod-smoke";
                    LevelSettings settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(117, false, false),
                            WorldPresets::createNormalWorldDimensions, mc.screen);
                }
                return;
            }
            if (phase == 0) {
                mc.options.renderDistance().set(4);
                mc.options.simulationDistance().set(5);
                mc.options.hideGui = true;
                mc.options.pauseOnLostFocus = false;
                mc.setWindowActive(true);
                if (!CreateModelGallery.start()) throw new IllegalStateException("Cannot start the windmill fixture");
                phase++;
            } else if (phase == 1 && CreateModelGallery.windmillEntity() != null) {
                entityId = CreateModelGallery.windmillEntity().getId();
                server(mc, player -> {
                    for (int x = -1; x <= 0; x++) {
                        for (int z = -1; z <= 0; z++) player.serverLevel().setChunkForced(x, z, true);
                    }
                    teleport(player, 320);
                });
                arrived = 0;
                phase++;
            } else if (phase == 3 && near(mc, 24) && mc.level.getEntity(entityId) != null) {
                LogUtils.getLogger().info("CONTRAPTION_LOD_NATIVE_TRACKING_RESTORED");
                server(mc, player -> teleport(player, 320));
                arrived = 0;
                phase++;
            } else if (phase == 6 && mc.level.dimension().equals(Level.OVERWORLD)) {
                if (idleCheck == null) {
                    var server = mc.getSingleplayerServer();
                    idleCheck = server.submit(() -> {
                        for (ServerLevel level : server.getAllLevels()) {
                            if (level.getChunkSource().getPendingTasksCount() != 0
                                    || level.getChunkSource().getLightEngine().hasLightWork()) return false;
                        }
                        return true;
                    });
                    return;
                }
                if (!idleCheck.isDone()) return;
                long tick = mc.level.getGameTime();
                if (!idleCheck.join()) idleSince = 0;
                else if (idleSince == 0) idleSince = tick;
                else if (tick - idleSince >= 20) finish(mc, null);
                idleCheck = null;
            }
        } catch (Exception failure) {
            finish(mc, failure);
        }
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (finished || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL
                || phase < 2 || phase == 3 || phase >= 6) return;
        Minecraft mc = Minecraft.getInstance();
        if (!near(mc, 320) || mc.level.getEntity(entityId) != null) return;
        if (arrived == 0) arrived = mc.level.getGameTime();
        if (mc.level.getGameTime() - arrived < 20) return;
        try {
            if (Boolean.getBoolean("kasuga.testContraptionLod.voxy") && !VoxyCompat.hasTerrainPass(event.getLevelRenderer())) {
                throw new IllegalStateException("Voxy terrain renderer is not active");
            }
            float[] depth = depth(mc);
            int occupied = 0;
            int changed = 0;
            for (int index = 0; index < depth.length; index++) {
                if (depth[index] < 0.9999999f) occupied++;
                if (firstDepth != null && Math.abs(depth[index] - firstDepth[index]) > 0.00000005f) changed++;
            }
            if (phase == 2 && occupied >= 24) {
                if (firstDepth == null) {
                    firstDepth = depth;
                    capture(mc, "distant.png");
                    return;
                }
                if (changed < 12) return;
                LogUtils.getLogger().info("CONTRAPTION_LOD_FAR_DRAW pixels={} changed={} farPlane={}",
                        occupied, changed, mc.gameRenderer.getDepthFar());
                server(mc, player -> teleport(player, 24));
                phase++;
            } else if (phase == 4 && occupied >= 24) {
                server(mc, player -> {
                    var entity = player.serverLevel().getEntity(entityId);
                    if (entity != null) entity.discard();
                });
                arrived = mc.level.getGameTime();
                phase++;
            } else if (phase == 5 && occupied < 12) {
                capture(mc, "removed.png");
                server(mc, player -> {
                    for (int x = -1; x <= 0; x++) {
                        for (int z = -1; z <= 0; z++) player.serverLevel().setChunkForced(x, z, false);
                    }
                });
                CreateModelGallery.stop();
                phase++;
            }
        } catch (Exception failure) {
            finish(mc, failure);
        }
    }

    private static boolean near(Minecraft mc, int distance) {
        return mc.player != null && Math.abs(mc.player.getZ() - (BEARING.getZ() + distance)) < 1;
    }

    private static void teleport(ServerPlayer player, int distance) {
        if (!player.teleportTo(player.serverLevel(), BEARING.getX() + 0.5, BEARING.getY() - 1,
                BEARING.getZ() + distance, Set.of(), 180, 0)) throw new IllegalStateException("Cannot move LOD observer");
    }

    private static void server(Minecraft mc, Consumer<ServerPlayer> action) {
        UUID uuid = mc.player.getUUID();
        var server = mc.getSingleplayerServer();
        server.execute(() -> action.accept(server.getPlayerList().getPlayer(uuid)));
    }

    private static float[] depth(Minecraft mc) {
        int size = 96;
        var target = mc.getMainRenderTarget();
        var pixels = MemoryUtil.memAllocFloat(size * size);
        try (GuiRenderScope ignored = new GuiRenderScope()) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL11.glReadPixels((target.width - size) / 2, (target.height - size) / 2, size, size,
                    GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixels);
            float[] result = new float[size * size];
            pixels.get(result);
            return result;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static void capture(Minecraft mc, String file) throws java.io.IOException {
        var directory = mc.gameDirectory.toPath().resolve("debug/contraption-lod");
        Files.createDirectories(directory);
        try (GuiRenderScope ignored = new GuiRenderScope(); var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(directory.resolve(file));
        }
    }

    private static void finish(Minecraft mc, Exception failure) {
        finished = true;
        if (failure == null) LogUtils.getLogger().info("CONTRAPTION_LOD_SMOKE_PASS");
        else LogUtils.getLogger().error("CONTRAPTION_LOD_SMOKE_FAIL", failure);
        CreateModelGallery.stop();
        mc.stop();
    }
}
