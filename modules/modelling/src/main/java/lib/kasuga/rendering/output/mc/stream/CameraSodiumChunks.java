package lib.kasuga.rendering.output.mc.stream;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.multiplayer.ClientLevel;
/** Mirrors Sodium's light-ready packet hook for snapshots delivered outside ClientPacketListener. */
final class CameraSodiumChunks {
    static void lightReady(ClientLevel level, int x, int z) { ChunkTrackerHolder.get(level).onChunkStatusAdded(x, z, 2); }
    static void unloaded(ClientLevel level, int x, int z) { ChunkTrackerHolder.get(level).onChunkStatusRemoved(x, z, 3); }
}
