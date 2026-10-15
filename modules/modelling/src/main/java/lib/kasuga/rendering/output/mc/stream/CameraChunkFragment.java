package lib.kasuga.rendering.output.mc.stream;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

/** Fragments stay below the custom-payload size limit; assembled native packets are bounded too. */
public record CameraChunkFragment(UUID camera, long epoch, long sequence, int x, int z,
                                  int index, int count, byte[] bytes) implements CustomPacketPayload {
    public static final int PART_SIZE = 700_000, MAX_PARTS = 4;
    public CameraChunkFragment {
        if (epoch < 0 || sequence < 0 || count < 1 || count > MAX_PARTS || index < 0 || index >= count
                || bytes.length < 1 || bytes.length > PART_SIZE)
            throw new IllegalArgumentException("Invalid camera chunk fragment");
    }
    public static final StreamCodec<FriendlyByteBuf, CameraChunkFragment> CODEC = StreamCodec.of(
            (b, p) -> { b.writeUUID(p.camera); b.writeLong(p.epoch); b.writeLong(p.sequence);
                b.writeInt(p.x); b.writeInt(p.z); b.writeVarInt(p.index); b.writeVarInt(p.count); b.writeByteArray(p.bytes); },
            b -> new CameraChunkFragment(b.readUUID(), b.readLong(), b.readLong(), b.readInt(), b.readInt(),
                    b.readVarInt(), b.readVarInt(), b.readByteArray(PART_SIZE)));
    @Override public Type<CameraChunkFragment> type() { return CameraChunkChannel.CHUNK.getEntry(); }
}
