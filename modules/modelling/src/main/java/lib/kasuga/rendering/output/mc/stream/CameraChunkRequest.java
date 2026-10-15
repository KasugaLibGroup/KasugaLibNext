package lib.kasuga.rendering.output.mc.stream;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

public record CameraChunkRequest(UUID camera, long epoch, ResourceLocation dimension,
                                 int x, int z, int radius, boolean open) implements CustomPacketPayload {
    public CameraChunkRequest {
        if (epoch < 0 || radius < 2 || radius > 32 || Math.abs((long) x) > 1875000 || Math.abs((long) z) > 1875000)
            throw new IllegalArgumentException("Invalid camera chunk window");
    }
    public static final StreamCodec<FriendlyByteBuf, CameraChunkRequest> CODEC = StreamCodec.of(
            (b, p) -> { b.writeUUID(p.camera); b.writeLong(p.epoch); b.writeResourceLocation(p.dimension);
                b.writeInt(p.x); b.writeInt(p.z); b.writeVarInt(p.radius); b.writeBoolean(p.open); },
            b -> new CameraChunkRequest(b.readUUID(), b.readLong(), b.readResourceLocation(), b.readInt(),
                    b.readInt(), b.readVarInt(), b.readBoolean()));
    @Override public Type<CameraChunkRequest> type() { return CameraChunkChannel.REQUEST.getEntry(); }
}
