package lib.kasuga.scene;

import lib.kasuga.KasugaLib;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Server-owned visit to a disposable stage dimension. Call on the server thread; no client class
 * is referenced. The return ticket is saved before teleporting, recovered on login after a crash,
 * and consumed only after a successful return. Stage blocks belong to the disposable dimension.
 * Hosts must supply a separate dimension rather than passing a player's normal world.
 */
@EventBusSubscriber(modid = KasugaLib.MODID)
public final class MinecraftStage implements AutoCloseable {
    private static final String TICKET = "kasuga_lib:stage_return";
    private static final Map<UUID, MinecraftStage> ACTIVE = new HashMap<>();
    private final MinecraftServer server;
    private final UUID playerId;
    private final ServerLevel level;
    private final Map<BlockPos, BlockSnapshot> originalBlocks = new HashMap<>();
    private volatile boolean closed;

    private MinecraftStage(ServerPlayer player, ServerLevel level) {
        this.server = player.getServer(); this.playerId = player.getUUID(); this.level = level;
    }

    public static MinecraftStage open(ServerPlayer player, ResourceKey<Level> dimension, Vec3 feet,
                                      float yaw, float pitch, Consumer<MinecraftStage> prepare) {
        var server = player.getServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("Stage requires the server thread");
        if (dimension.equals(player.level().dimension())) throw new IllegalArgumentException("Stage must use a separate disposable dimension");
        if (ACTIVE.containsKey(player.getUUID())) throw new IllegalStateException("Player already owns a stage");
        var level = server.getLevel(dimension);
        if (level == null) throw new IllegalStateException("Stage dimension is not installed: " + dimension.location());
        if (player.getPersistentData().contains(TICKET)) {
            if (!restore(player)) throw new IllegalStateException("Previous stage return ticket cannot be recovered");
        }
        var ticket = new CompoundTag();
        ticket.putInt("schema", 1); ticket.putString("dimension", player.level().dimension().location().toString());
        ticket.putDouble("x", player.getX()); ticket.putDouble("y", player.getY()); ticket.putDouble("z", player.getZ());
        ticket.putFloat("yaw", player.getYRot()); ticket.putFloat("pitch", player.getXRot());
        ticket.putInt("mode", player.gameMode.getGameModeForPlayer().getId());
        ticket.putBoolean("flying", player.getAbilities().flying);
        ticket.putBoolean("invulnerable", player.getAbilities().invulnerable);
        player.getPersistentData().put(TICKET, ticket);
        server.getPlayerList().saveAll();
        var stage = new MinecraftStage(player, level);
        ACTIVE.put(player.getUUID(), stage);
        try {
            level.getChunk(BlockPos.containing(feet));
            prepare.accept(stage);
            if (!player.setGameMode(GameType.ADVENTURE)
                    || !player.teleportTo(level, feet.x, feet.y, feet.z, Set.of(), yaw, pitch))
                throw new IllegalStateException("Stage entry failed");
            player.getAbilities().invulnerable = true;
            player.onUpdateAbilities(); player.fallDistance = 0;
            return stage;
        } catch (RuntimeException | Error failure) {
            stage.close(); throw failure;
        }
    }

    public ServerLevel level() { return level; }
    public boolean closed() { return closed; }
    public boolean contains(ServerPlayer player) { return !closed && player.level() == level; }

    /** Reversible edits, including block-entity contents, on the server thread. */
    public void block(BlockPos position, BlockState state) {
        if (!server.isSameThread()) throw new IllegalStateException("Stage block edits require the server thread");
        if (closed) return;
        originalBlocks.computeIfAbsent(position.immutable(), key -> {
            var entity = level.getBlockEntity(key);
            return new BlockSnapshot(level.getBlockState(key), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess()));
        });
        level.setBlock(position, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    public void fill(BlockPos minimum, BlockPos maximum, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(minimum, maximum)) block(pos, state);
    }

    public void execute(Consumer<MinecraftStage> action) {
        server.execute(() -> { if (!closed) action.accept(this); });
    }

    @Override public void close() {
        if (!server.isSameThread()) { server.execute(this::close); return; }
        if (closed) return;
        closed = true;
        ACTIVE.remove(playerId, this);
        var player = server.getPlayerList().getPlayer(playerId);
        if (player != null) restore(player);
        for (var entry : originalBlocks.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue().state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            var entity = level.getBlockEntity(entry.getKey());
            if (entity != null && entry.getValue().data != null)
                entity.loadWithComponents(entry.getValue().data, level.registryAccess());
        }
        originalBlocks.clear();
    }

    private static boolean restore(ServerPlayer player) {
        if (!player.getPersistentData().contains(TICKET)) return true;
        var ticket = player.getPersistentData().getCompound(TICKET);
        if (ticket.getInt("schema") != 1 || !ticket.contains("dimension")
                || !ticket.contains("x") || !ticket.contains("y") || !ticket.contains("z")
                || !ticket.contains("mode")) return false;
        double x = ticket.getDouble("x"), y = ticket.getDouble("y"), z = ticket.getDouble("z");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
        var id = ResourceLocation.tryParse(ticket.getString("dimension"));
        if (id == null || player.getServer() == null) return false;
        var destination = player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (destination == null) return false;
        if (!player.teleportTo(destination, x, y, z, Set.of(), ticket.getFloat("yaw"), ticket.getFloat("pitch"))) return false;
        player.setGameMode(GameType.byId(ticket.getInt("mode")));
        player.getAbilities().flying = ticket.getBoolean("flying");
        player.getAbilities().invulnerable = ticket.getBoolean("invulnerable");
        player.onUpdateAbilities(); player.fallDistance = 0;
        player.getPersistentData().remove(TICKET);
        return true;
    }

    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !ACTIVE.containsKey(player.getUUID())) restore(player);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var stage = ACTIVE.get(player.getUUID());
            if (stage != null) stage.close();
            else restore(player);
        }
    }
    private record BlockSnapshot(BlockState state, CompoundTag data) {}
}
