package test.kasuga.create.demo.preview;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.EffectHandle;
import lib.kasuga.rendering.effect.builtin.weather.WeatherFilterEffect;
import lib.kasuga.rendering.effect.builtin.weather.WeatherFilters;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import lib.kasuga.rendering.weather.WeatherFilterVolume;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/**
 * Temporary headless client capture harness for verifying before/after 3D weather occlusion
 * inside an assembled Create carriage contraption and across an explicit OBB WeatherFilterVolume.
 */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class WeatherFilterVisualCapture {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.captureWeatherDemo");
    private static final long START_NANOS = System.nanoTime();
    private static final int ORIGIN_X = 0;
    private static final int ORIGIN_Y = -56;
    private static final int ORIGIN_Z = 0;
    private static final BlockPos BEARING_POS = new BlockPos(ORIGIN_X, ORIGIN_Y - 1, ORIGIN_Z);

    private static boolean worldRequested;
    private static boolean stageBuilt;
    private static boolean assembled;
    private static boolean finished;
    private static int stageTicks;
    private static int phase;
    private static int framesInPhase;
    private static int entityId = -1;

    private static AutoCloseable excludeHandle;
    private static EffectHandle<WeatherFilterEffect> splitVolumeHandle;

    private WeatherFilterVisualCapture() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED || finished) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            if (System.nanoTime() - START_NANOS > 120_000_000_000L) {
                Entity dbgEntity = mc.level != null && entityId >= 0 ? mc.level.getEntity(entityId) : null;
                float dbgRain = mc.level != null ? mc.level.getRainLevel(1.0f) : -1.0f;
                throw new IllegalStateException("Weather visual capture timed out at phase " + phase
                        + " entity=" + dbgEntity + " rain=" + dbgRain
                        + " ready=" + (dbgEntity instanceof AbstractContraptionEntity ace && ace.isReadyForRender()));
            }
            if (mc.level == null || mc.player == null) {
                if (!worldRequested && mc.getOverlay() == null && mc.screen instanceof TitleScreen) {
                    worldRequested = true;
                    String worldName = "weather-capture-" + Long.toString(System.currentTimeMillis(), 36);
                    LevelSettings settings = new LevelSettings(
                            worldName,
                            GameType.CREATIVE,
                            false,
                            Difficulty.PEACEFUL,
                            true,
                            new GameRules(),
                            WorldDataConfiguration.DEFAULT
                    );
                    mc.createWorldOpenFlows().createFreshLevel(
                            worldName,
                            settings,
                            new WorldOptions(42L, false, false),
                            registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                                    .getHolderOrThrow(WorldPresets.FLAT)
                                    .value()
                                    .createWorldDimensions(),
                            mc.screen
                    );
                }
                return;
            }

            mc.options.renderDistance().set(6);
            mc.options.simulationDistance().set(6);
            mc.options.hideGui = true;
            mc.options.pauseOnLostFocus = false;
            mc.setWindowActive(true);
            if (mc.screen != null) {
                mc.setScreen(null);
            }

            MinecraftServer server = mc.getSingleplayerServer();
            if (server == null) {
                return;
            }

            if (!stageBuilt) {
                stageBuilt = true;
                UUID playerId = mc.player.getUUID();
                server.execute(() -> buildStage(server, playerId));
                return;
            }

            mc.level.setDayTime(6000L);
            mc.level.setRainLevel(1.0f);

            stageTicks++;
            if (!assembled && stageTicks >= 15) {
                server.execute(() -> tryAssemble(server));
                return;
            }

            if (assembled && phase == 0 && entityId >= 0) {
                Entity entity = mc.level.getEntity(entityId);
                if (entity instanceof AbstractContraptionEntity contraptionEntity
                        && contraptionEntity.isReadyForRender()
                        && mc.level.getRainLevel(1.0f) >= 0.95f
                        && stageTicks >= 55) {
                    excludeHandle = WeatherFilters.excludeEntityTypes(contraptionEntity.getType());
                    setCameraPose(mc, server, ORIGIN_X + 0.5, ORIGIN_Y + 0.72, ORIGIN_Z + 2.8, 180.0f, 3.0f);
                    phase = 1;
                    framesInPhase = 0;
                }
            } else if (phase == 1) {
                lockClientCamera(mc, ORIGIN_X + 0.5, ORIGIN_Y + 0.72, ORIGIN_Z + 2.8, 180.0f, 3.0f);
            } else if (phase == 2) {
                lockClientCamera(mc, ORIGIN_X + 0.5, ORIGIN_Y + 0.72, ORIGIN_Z + 2.8, 180.0f, 3.0f);
            } else if (phase == 3) {
                lockClientCamera(mc, ORIGIN_X + 1.52, ORIGIN_Y + 0.72, ORIGIN_Z + 2.78, 156.0f, 3.0f);
            } else if (phase == 4) {
                lockClientCamera(mc, 22.5, ORIGIN_Y + 0.72, 1.5, 180.0f, 3.0f);
            }
        } catch (Exception failure) {
            finish(mc, failure);
        }
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (!ENABLED || finished || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || phase == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null) {
            return;
        }
        try {
            framesInPhase++;
            if (phase == 1 && framesInPhase >= 25) {
                capture(mc, "01_contraption_unfiltered.png");
                closeQuietly(excludeHandle);
                excludeHandle = null;
                setCameraPose(mc, server, ORIGIN_X + 0.5, ORIGIN_Y + 0.72, ORIGIN_Z + 2.8, 180.0f, 3.0f);
                phase = 2;
                framesInPhase = 0;
            } else if (phase == 2 && framesInPhase >= 30) {
                capture(mc, "02_contraption_filtered.png");
                rotateContraption(mc, server, 24.0f);
                setCameraPose(mc, server, ORIGIN_X + 1.52, ORIGIN_Y + 0.72, ORIGIN_Z + 2.78, 156.0f, 3.0f);
                phase = 3;
                framesInPhase = 0;
            } else if (phase == 3 && framesInPhase >= 30) {
                capture(mc, "03_contraption_rotated_filtered.png");
                setCameraPose(mc, server, 22.5, ORIGIN_Y + 0.72, 1.5, 180.0f, 3.0f);
                splitVolumeHandle = WeatherFilters.spawn(
                        mc.level,
                        WeatherFilterVolume.box(10.0, 12.0, 16.0)
                                .position(27.5, ORIGIN_Y + 5.0, -5.5)
                                .strength(1.0f)
                                .feather(0.02f)
                                .build()
                );
                phase = 4;
                framesInPhase = 0;
            } else if (phase == 4 && framesInPhase >= 30) {
                capture(mc, "04_explicit_volume_split.png");
                if (splitVolumeHandle != null) {
                    splitVolumeHandle.remove();
                    splitVolumeHandle = null;
                }
                finish(mc, null);
            }
        } catch (Exception failure) {
            finish(mc, failure);
        }
    }

    private static void buildStage(MinecraftServer server, UUID playerId) {
        ServerLevel level = server.overworld();
        level.setDayTime(6000L);
        level.setWeatherParameters(0, 24000, true, false);
        level.setRainLevel(1.0f);
        server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);

        BlockState deepslate = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        BlockState blackConcrete = Blocks.BLACK_CONCRETE.defaultBlockState();
        BlockState darkOak = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState brassCasing = AllBlocks.BRASS_CASING.getDefaultState();
        BlockState andesiteCasing = AllBlocks.ANDESITE_CASING.getDefaultState();
        BlockState copperCasing = AllBlocks.COPPER_CASING.getDefaultState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        BlockState lantern = Blocks.SEA_LANTERN.defaultBlockState();

        for (int x = -14; x <= 36; x++) {
            for (int z = -16; z <= 12; z++) {
                level.setBlock(new BlockPos(x, ORIGIN_Y - 1, z), deepslate, 3);
            }
            for (int y = ORIGIN_Y; y <= ORIGIN_Y + 12; y++) {
                level.setBlock(new BlockPos(x, y, -14), blackConcrete, 3);
            }
        }

        // Brass centerline on open-air split stage marking the WeatherFilterVolume boundary (x = 22.5)
        for (int z = -13; z <= 3; z++) {
            level.setBlock(new BlockPos(22, ORIGIN_Y - 1, z), brassCasing, 3);
        }

        // Mechanical bearing below the carriage
        level.setBlock(
                BEARING_POS,
                AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BearingBlock.FACING, Direction.UP),
                3
        );

        // 7x6x9 Create carriage structure
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int wx = ORIGIN_X + dx;
                int wz = ORIGIN_Z + dz;

                // Floor (y = ORIGIN_Y)
                BlockState floor = (dx == 0 && dz == 0) || Math.abs(dx) == 3 ? brassCasing : darkOak;
                level.setBlock(new BlockPos(wx, ORIGIN_Y, wz), floor, 3);

                // Roof (y = ORIGIN_Y + 5) with central glass skylight
                boolean skylight = Math.abs(dx) <= 1 && dz >= -3 && dz <= 2;
                level.setBlock(new BlockPos(wx, ORIGIN_Y + 5, wz), skylight ? glass : copperCasing, 3);

                // Side walls (x = +-3)
                if (Math.abs(dx) == 3) {
                    boolean corner = Math.abs(dz) == 4;
                    level.setBlock(new BlockPos(wx, ORIGIN_Y + 1, wz), corner ? brassCasing : andesiteCasing, 3);
                    level.setBlock(new BlockPos(wx, ORIGIN_Y + 2, wz), corner ? brassCasing : glass, 3);
                    level.setBlock(new BlockPos(wx, ORIGIN_Y + 3, wz), corner ? brassCasing : glass, 3);
                    level.setBlock(new BlockPos(wx, ORIGIN_Y + 4, wz), copperCasing, 3);
                }

                // Back wall (z = +4)
                if (dz == 4 && Math.abs(dx) < 3) {
                    level.setBlock(new BlockPos(wx, ORIGIN_Y + 1, wz), darkOak, 3);
                    level.setBlock(new BlockPos(wx, ORIGIN_Y + 2, wz), Math.abs(dx) <= 1 ? glass : darkOak, 3);
                    level.setBlock(new BlockPos(wx, ORIGIN_Y + 3, wz), Math.abs(dx) <= 1 ? glass : darkOak, 3);
                    level.setBlock(new BlockPos(wx, ORIGIN_Y + 4, wz), Math.abs(dx) == 2 ? lantern : copperCasing, 3);
                }

                // Front doorway lintel (z = -4, wide open x = -2..2 at y = +1..+3)
                if (dz == -4 && Math.abs(dx) < 3) {
                    level.setBlock(new BlockPos(wx, ORIGIN_Y + 4, wz), brassCasing, 3);
                }
            }
        }

        // Interior lanterns near ceiling
        level.setBlock(new BlockPos(ORIGIN_X - 2, ORIGIN_Y + 4, ORIGIN_Z + 1), lantern, 3);
        level.setBlock(new BlockPos(ORIGIN_X + 2, ORIGIN_Y + 4, ORIGIN_Z + 1), lantern, 3);

        SuperGlueEntity glue = new SuperGlueEntity(
                level,
                new AABB(ORIGIN_X - 4.0, ORIGIN_Y, ORIGIN_Z - 5.0, ORIGIN_X + 5.0, ORIGIN_Y + 7.0, ORIGIN_Z + 6.0)
        );
        level.addFreshEntity(glue);

        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.setNoGravity(true);
            player.onUpdateAbilities();
            player.teleportTo(level, ORIGIN_X + 0.5, ORIGIN_Y + 0.72, ORIGIN_Z + 2.8, Set.of(), 180.0f, 3.0f);
        }
    }

    private static void tryAssemble(MinecraftServer server) {
        if (assembled) {
            return;
        }
        ServerLevel level = server.overworld();
        if (!SuperGlueEntity.isGlued(level, new BlockPos(ORIGIN_X, ORIGIN_Y, ORIGIN_Z), Direction.EAST, new java.util.HashSet<>())) {
            return;
        }
        if (!(level.getBlockEntity(BEARING_POS) instanceof MechanicalBearingBlockEntity bearing)) {
            return;
        }
        bearing.assemble();
        ControlledContraptionEntity contraption = bearing.getMovedContraption();
        if (contraption == null) {
            throw new IllegalStateException("Create bearing failed to assemble carriage");
        }
        entityId = contraption.getId();
        assembled = true;
        LogUtils.getLogger().info("WEATHER_CAPTURE_ASSEMBLED entityId={} blocks={}",
                entityId, contraption.getContraption().getBlocks().size());
    }

    private static void rotateContraption(Minecraft mc, MinecraftServer server, float angleDegrees) {
        server.execute(() -> {
            if (server.overworld().getBlockEntity(BEARING_POS) instanceof MechanicalBearingBlockEntity bearing) {
                bearing.setAngle(angleDegrees);
                bearing.sendData();
            }
        });
        if (mc.level != null) {
            if (mc.level.getBlockEntity(BEARING_POS) instanceof MechanicalBearingBlockEntity clientBearing) {
                clientBearing.setAngle(angleDegrees);
            }
            Entity entity = mc.level.getEntity(entityId);
            if (entity instanceof ControlledContraptionEntity rotor) {
                rotor.setAngle(angleDegrees);
            }
        }
    }

    private static void setCameraPose(Minecraft mc, MinecraftServer server, double x, double y, double z, float yaw, float pitch) {
        if (mc.player != null) {
            UUID playerId = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    player.teleportTo(server.overworld(), x, y, z, Set.of(), yaw, pitch);
                }
            });
            lockClientCamera(mc, x, y, z, yaw, pitch);
        }
    }

    private static void lockClientCamera(Minecraft mc, double x, double y, double z, float yaw, float pitch) {
        if (mc.player == null) {
            return;
        }
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setPos(x, y, z);
        mc.player.xo = x;
        mc.player.yo = y;
        mc.player.zo = z;
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.setXRot(pitch);
        mc.player.xRotO = pitch;
    }

    private static void capture(Minecraft mc, String fileName) throws IOException {
        String outputDirProp = System.getProperty("kasuga.weatherCaptureDir");
        Path dir = outputDirProp != null && !outputDirProp.isBlank()
                ? Path.of(outputDirProp)
                : mc.gameDirectory.toPath().resolve("debug/weather-capture");
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        try (GuiRenderScope ignored = new GuiRenderScope();
             com.mojang.blaze3d.platform.NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(target);
        }
        LogUtils.getLogger().info("WEATHER_CAPTURE_SAVED {}", target);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static void finish(Minecraft mc, Exception failure) {
        if (finished) {
            return;
        }
        finished = true;
        closeQuietly(excludeHandle);
        excludeHandle = null;
        if (splitVolumeHandle != null) {
            splitVolumeHandle.remove();
            splitVolumeHandle = null;
        }
        if (failure == null) {
            LogUtils.getLogger().info("WEATHER_VISUAL_CAPTURE_PASS");
        } else {
            LogUtils.getLogger().error("WEATHER_VISUAL_CAPTURE_FAIL", failure);
        }
        mc.stop();
    }
}
