package test.kasuga.modelling;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationBlockEntity;
import lib.kasuga.rendering.models.mc.dynamic.fsm.KasugaModelPipelines;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Moves the real player through chunk unload and reload in an isolated integrated test world. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ModelLifecycleClientSmoke {
    private static final BlockPos HOST = new BlockPos(800, 180, 1600);
    private static final ResourceLocation BLOCK = ResourceLocation.parse("kasuga_lib:fsm_test_block");
    private static final ResourceLocation MODEL = ResourceLocation.parse("kasuga_lib_test:models/fsm/test_cube.obj");
    private static final ResourceLocation COLLISION = ResourceLocation.parse("kasuga_lib:models/collision_test.bbmodel");
    private static final ResourceLocation INSTANCE = ResourceLocation.parse("kasuga_lib_test:fsm_" + HOST.asLong());
    private static final AABB PROBE = new AABB(803, 180.2, 1600.1, 803.2, 181.8, 1600.3);
    private static final long DEADLINE = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
    private static int phase;
    private static ModelInstance original;
    private static CompletableFuture<Boolean> serverTask;
    private static CompletableFuture<Boolean> unloadCheck;
    private static long settle;
    private static boolean finished;
    private static boolean creatingWorld;

    private ModelLifecycleClientSmoke() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (finished || !Boolean.getBoolean("kasuga.testModelLifecycle")) return;
        Minecraft mc = Minecraft.getInstance();
        if (System.nanoTime() > DEADLINE) {
            finish(mc, new IllegalStateException("Timed out in lifecycle phase " + phase));
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
            if (!creatingWorld && mc.getOverlay() == null && mc.screen instanceof TitleScreen) {
                creatingWorld = true;
                String world = "model-lifecycle-smoke";
                LevelSettings settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL,
                        true, new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(117, false, false),
                        WorldPresets::createNormalWorldDimensions, mc.screen);
            }
            return;
        }
        try {
            if (serverTask != null) {
                if (!serverTask.isDone()) return;
                if (!serverTask.join()) throw new IllegalStateException("Server lifecycle action failed");
                serverTask = null;
            }
            ChunkPos chunk = new ChunkPos(HOST);
            boolean loaded = mc.level.getChunkSource().hasChunk(chunk.x, chunk.z);
            ModelInstance instance = PipelineRegistry.obj().getInstance(MODEL, INSTANCE);
            boolean collision = !mc.level.getEntityCollisions(null, PROBE).isEmpty();
            switch (phase) {
                case 0 -> {
                    if (!PipelineRegistry.obj().hasModel(MODEL)) return;
                    mc.options.renderDistance().set(4);
                    mc.options.simulationDistance().set(5);
                    mc.options.pauseOnLostFocus = false;
                    mc.setWindowActive(true);
                    act(mc, player -> {
                        BlockPos floor = HOST.below();
                        player.serverLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                        player.serverLevel().setBlockAndUpdate(HOST, BuiltInRegistries.BLOCK.get(BLOCK).defaultBlockState());
                        AnimationBlockEntity host = (AnimationBlockEntity) player.serverLevel().getBlockEntity(HOST);
                        host.collisionModel(COLLISION);
                        host.setChanged();
                        player.getAbilities().mayfly = true;
                        player.getAbilities().flying = true;
                        player.onUpdateAbilities();
                        return teleport(player, 6);
                    });
                    phase++;
                }
                case 1 -> {
                    if (!loaded || !collision || !rendering(instance)) return;
                    original = instance;
                    LogUtils.getLogger().info("MODEL_LIFECYCLE_INITIAL_MOUNT");
                    act(mc, player -> teleport(player, 512));
                    phase++;
                }
                case 2 -> {
                    if (loaded || collision || !original.isClosed() || instance != null) return;
                    // Wait for a server chunk unload too, so return exercises NBT reconstruction.
                    if (unloadCheck == null) {
                        if (mc.level.getGameTime() < settle) return;
                        var server = mc.getSingleplayerServer();
                        unloadCheck = server.submit(() -> !server.overworld().hasChunkAt(HOST));
                        return;
                    }
                    if (!unloadCheck.isDone()) return;
                    boolean serverUnloaded = unloadCheck.join();
                    unloadCheck = null;
                    if (!serverUnloaded) {
                        settle = mc.level.getGameTime() + 10;
                        return;
                    }
                    LogUtils.getLogger().info("MODEL_LIFECYCLE_CHUNK_UNLOADED");
                    act(mc, player -> teleport(player, 112));
                    phase++;
                }
                case 3 -> {
                    if (Math.abs(mc.player.getZ() - (HOST.getZ() - 112)) > 1) return;
                    if (loaded || instance != null || collision) throw new IllegalStateException("Model appeared before its chunk");
                    act(mc, player -> teleport(player, 48));
                    phase++;
                }
                case 4 -> {
                    if (!loaded || !collision || !rendering(instance)) return;
                    if (instance == original || instance.isClosed()) throw new IllegalStateException("Reused an unloaded model instance");
                    LogUtils.getLogger().info("MODEL_LIFECYCLE_REMOUNTED");
                    settle = mc.level.getGameTime() + 40;
                    phase++;
                }
                case 5 -> {
                    if (mc.level.getGameTime() < settle) return;
                    if (!rendering(instance) || !collision) throw new IllegalStateException("Reloaded model or collision disappeared");
                    finish(mc, null);
                }
                default -> throw new IllegalStateException("Unexpected lifecycle phase");
            }
        } catch (RuntimeException failure) {
            finish(mc, failure);
        }
    }

    private static boolean rendering(ModelInstance instance) {
        return instance != null && KasugaModelPipelines.isRendering(MODEL, null, INSTANCE);
    }

    private static void act(Minecraft mc, java.util.function.Predicate<ServerPlayer> action) {
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        serverTask = server.submit(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            return player != null && action.test(player);
        });
    }

    private static boolean teleport(ServerPlayer player, int distance) {
        return player.teleportTo(player.serverLevel(), HOST.getX() + 0.5, HOST.getY(),
                HOST.getZ() - distance, Set.of(), 0, 0);
    }

    private static void finish(Minecraft mc, Throwable failure) {
        finished = true;
        if (failure == null) LogUtils.getLogger().info("MODEL_LIFECYCLE_SMOKE_PASS");
        else LogUtils.getLogger().error("MODEL_LIFECYCLE_SMOKE_FAIL", failure);
        mc.stop();
    }
}
