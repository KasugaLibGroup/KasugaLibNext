package lib.kasuga.rendering.models.mc.dynamic;

import lib.kasuga.KasugaLib;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Releases model owners evicted by a client view-radius change as well as normal chunk drops. */
@EventBusSubscriber(modid = KasugaLib.MODID)
public final class ClientBlockModelLifetime {
    private static final Map<Level, Map<BlockEntity, Consumer<Level>>> HOSTS = new IdentityHashMap<>();

    private ClientBlockModelLifetime() {}

    public static boolean isLoadedHost(BlockEntity host, Level level) {
        return !host.isRemoved() && level.getChunkSource().hasChunk(
                SectionPos.blockToSectionCoord(host.getBlockPos().getX()),
                SectionPos.blockToSectionCoord(host.getBlockPos().getZ()))
                && level.getBlockEntity(host.getBlockPos()) == host;
    }

    public static void watch(BlockEntity host, Level level, Consumer<Level> release) {
        if (level.isClientSide()) HOSTS.computeIfAbsent(level, ignored -> new IdentityHashMap<>()).putIfAbsent(host, release);
    }

    public static void forget(BlockEntity host, Level level) {
        if (level == null || !level.isClientSide()) return;
        Map<BlockEntity, Consumer<Level>> hosts = HOSTS.get(level);
        if (hosts != null) hosts.remove(host);
    }

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (!level.isClientSide()) return;
        Map<BlockEntity, Consumer<Level>> hosts = HOSTS.get(level);
        if (hosts == null) return;
        for (BlockEntity host : List.copyOf(hosts.keySet())) {
            if (isLoadedHost(host, level)) continue;
            Consumer<Level> release = hosts.remove(host);
            if (release != null) release.accept(level);
        }
    }

    @SubscribeEvent
    public static void unloaded(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof Level level) || !level.isClientSide()) return;
        Map<BlockEntity, Consumer<Level>> hosts = HOSTS.remove(level);
        if (hosts == null) return;
        for (Consumer<Level> release : hosts.values()) release.accept(level);
    }
}
