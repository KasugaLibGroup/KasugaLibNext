package lib.kasuga.rendering.output.mc.stream;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;
public record CameraEntityPayload(UUID camera, long epoch, byte[] packet) implements CustomPacketPayload {
    public CameraEntityPayload { if (epoch < 0 || packet.length == 0 || packet.length > 700_000) throw new IllegalArgumentException("Invalid camera entity packet"); }
    public static final StreamCodec<FriendlyByteBuf, CameraEntityPayload> CODEC = StreamCodec.of(
            (b, p) -> { b.writeUUID(p.camera); b.writeLong(p.epoch); b.writeByteArray(p.packet); },
            b -> new CameraEntityPayload(b.readUUID(), b.readLong(), b.readByteArray(700_000)));
    @Override public Type<CameraEntityPayload> type() { return CameraChunkChannel.ENTITY.getEntry(); }
}
