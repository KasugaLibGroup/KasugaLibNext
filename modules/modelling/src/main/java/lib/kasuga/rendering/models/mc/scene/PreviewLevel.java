package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Private, non-ticking render level. It has no entities, packets, sounds, scheduled ticks, or
 * writes into the borrowed game level. Flywheel therefore uses each block entity's native renderer.
 */
public final class PreviewLevel extends Level {
    private final Level host;
    private final Map<BlockPos, BlockState> blocks;
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    private final Map<Long, LevelChunk> chunks = new HashMap<>();
    private final LevelEntityGetter<Entity> entities = new LevelEntityGetterAdapter<>(new EntityLookup<>(),
            new EntitySectionStorage<>(Entity.class, key -> Visibility.HIDDEN));
    private final ChunkSource source;
    private final Holder<Biome> biome;
    private final int minimumY, height;

    public PreviewLevel(Level host, Map<BlockPos, BlockState> states) {
        super(new net.minecraft.client.multiplayer.ClientLevel.ClientLevelData(host.getDifficulty(), false, false), host.dimension(), host.registryAccess(),
                host.dimensionTypeRegistration(), host.getProfilerSupplier(), true, false, 0, 0);
        if (states.size() > MinecraftModelSources.MAX_BLOCKS) throw new IllegalArgumentException("Too many preview blocks");
        this.host = host;
        this.blocks = Map.copyOf(states);
        int min = states.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0);
        int max = states.keySet().stream().mapToInt(BlockPos::getY).max().orElse(0);
        if ((long) max - min > MinecraftModelSources.MAX_SPAN) throw new IllegalArgumentException("Preview height exceeds limit");
        minimumY = Math.floorDiv(min, 16) * 16;
        height = Math.max(16, (Math.floorDiv(max, 16) + 1) * 16 - minimumY);
        biome = registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        source = new ChunkSource() {
            private final LevelLightEngine lights = new LevelLightEngine(this, false, false);
            @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) { return PreviewLevel.this.getChunk(x, z); }
            @Override public void tick(BooleanSupplier more, boolean tickChunks) {}
            @Override public String gatherStats() { return "preview"; }
            @Override public int getLoadedChunksCount() { return chunks.size(); }
            @Override public LevelLightEngine getLightEngine() { return lights; }
            @Override public net.minecraft.world.level.BlockGetter getLevel() { return PreviewLevel.this; }
        };
    }

    public void addPreviewBlockEntity(BlockEntity entity) {
        if (blocks.containsKey(entity.getBlockPos())) {
            entity.setLevel(this);
            blockEntities.put(entity.getBlockPos().immutable(), entity);
        }
    }

    @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override @Nullable public BlockEntity getBlockEntity(BlockPos pos) { return blockEntities.get(pos); }
    @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) { return false; }
    @Override public void setBlockEntity(BlockEntity entity) {}
    @Override public void removeBlockEntity(BlockPos pos) {}
    @Override public boolean destroyBlock(BlockPos pos, boolean drops, Entity entity, int recursion) { return false; }
    @Override public boolean removeBlock(BlockPos pos, boolean moving) { return false; }
    @Override public int getBrightness(LightLayer layer, BlockPos pos) { return 15; }
    @Override public int getRawBrightness(BlockPos pos, int amount) { return 15; }
    @Override public int getMaxLocalRawBrightness(BlockPos pos) { return 15; }
    @Override public float getShade(Direction face, boolean shaded) { return host.getShade(face, shaded); }
    @Override public int getMinBuildHeight() { return minimumY; }
    @Override public int getHeight() { return height; }
    @Override public Holder<Biome> getBiome(BlockPos pos) { return biome; }
    @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) { return biome; }
    @Override public long getGameTime() { return host.getGameTime(); }
    @Override public long getDayTime() { return host.getDayTime(); }
    @Override public ChunkSource getChunkSource() { return source; }
    @Override public LevelChunk getChunk(int x, int z) {
        if (chunks.size() >= 256 && !chunks.containsKey(ChunkPos.asLong(x, z)))
            throw new IllegalStateException("Preview renderer requested too many chunks");
        return chunks.computeIfAbsent(ChunkPos.asLong(x, z), key -> new LevelChunk(this, new ChunkPos(x, z)) {
            @Override public BlockState getBlockState(BlockPos pos) { return PreviewLevel.this.getBlockState(pos); }
            @Override public FluidState getFluidState(BlockPos pos) { return PreviewLevel.this.getFluidState(pos); }
            @Override public BlockEntity getBlockEntity(BlockPos pos, EntityCreationType creation) { return PreviewLevel.this.getBlockEntity(pos); }
            @Override public BlockState setBlockState(BlockPos pos, BlockState state, boolean moving) { return null; }
            @Override public Holder<Biome> getNoiseBiome(int bx, int by, int bz) { return biome; }
        });
    }
    @Override protected LevelEntityGetter<Entity> getEntities() { return entities; }
    @Override public List<? extends Player> players() { return List.of(); }
    @Override public Entity getEntity(int id) { return null; }
    @Override public boolean isLoaded(BlockPos pos) { return true; }
    @Override public boolean isAreaLoaded(BlockPos pos, int radius) { return true; }
    @Override public LevelTickAccess<Block> getBlockTicks() { return BlackholeTickAccess.emptyLevelList(); }
    @Override public LevelTickAccess<Fluid> getFluidTicks() { return BlackholeTickAccess.emptyLevelList(); }
    @Override public TickRateManager tickRateManager() { return host.tickRateManager(); }
    @Override public FeatureFlagSet enabledFeatures() { return host.enabledFeatures(); }
    @Override public Scoreboard getScoreboard() { return new Scoreboard(); }
    @Override public RecipeManager getRecipeManager() { return host.getRecipeManager(); }
    @Override public PotionBrewing potionBrewing() { return host.potionBrewing(); }
    @Override public MapItemSavedData getMapData(MapId id) { return null; }
    @Override public void setMapData(MapId id, MapItemSavedData data) {}
    @Override public MapId getFreeMapId() { return new MapId(0); }
    @Override public void sendBlockUpdated(BlockPos pos, BlockState previous, BlockState next, int flags) {}
    @Override public void updateNeighbourForOutputSignal(BlockPos pos, Block block) {}
    @Override public void levelEvent(Player player, int type, BlockPos pos, int data) {}
    @Override public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {}
    @Override public void playSeededSound(Player player, double x, double y, double z, Holder<SoundEvent> sound,
                                          SoundSource category, float volume, float pitch, long seed) {}
    @Override public void playSeededSound(Player player, Entity entity, Holder<SoundEvent> sound,
                                          SoundSource category, float volume, float pitch, long seed) {}
    @Override public String gatherChunkSourceStats() { return "preview"; }
    @Override public void destroyBlockProgress(int id, BlockPos pos, int amount) {}
    @Override public void setDayTimeFraction(float value) {}
    @Override public float getDayTimeFraction() { return host.getDayTimeFraction(); }
    @Override public float getDayTimePerTick() { return host.getDayTimePerTick(); }
    @Override public void setDayTimePerTick(float value) {}
    @Override public void close() {
        for (BlockEntity entity : blockEntities.values()) entity.setLevel(null);
        blockEntities.clear();
        chunks.clear();
    }
}
