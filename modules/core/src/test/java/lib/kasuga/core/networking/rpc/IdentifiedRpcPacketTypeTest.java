package lib.kasuga.core.networking.rpc;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** IdentifiedRpcPacketType wire format: id + value round-trips through a real FriendlyByteBuf. */
class IdentifiedRpcPacketTypeTest {

    private static final CustomPacketPayload.Type<IdentifiedRpcPacketType<Integer>.Packet> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("test", "rpc"));

    private static final CustomPacketPayload.Type<IdentifiedRpcPacketType<String>.Packet> STRING_TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("test", "rpc_str"));

    @Test
    void roundTripsThroughFriendlyByteBuf() {
        IdentifiedRpcPacketType<Integer> type = new IdentifiedRpcPacketType<>(() -> TYPE, () -> ByteBufCodecs.VAR_INT);
        IdentifiedRpcPacketType<Integer>.Packet packet = type.wrap(123L, 456);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        type.getCodec().encode(buf, packet);
        IdentifiedRpcPacketType<Integer>.Packet decoded = type.getCodec().decode(buf);

        assertEquals(123L, decoded.getId());
        assertEquals(456, decoded.getValue());
        assertSame(TYPE, decoded.type());
    }

    @Test
    void typeIsResolvedFromSupplierAtCallTime() {
        // TYPE supplier is invoked lazily on first type() call, mirroring payload-registration timing
        IdentifiedRpcPacketType<String> type = new IdentifiedRpcPacketType<>(() -> STRING_TYPE, () -> ByteBufCodecs.STRING_UTF8);
        CustomPacketPayload.Type<?> resolved = type.wrap(1L, "x").type();
        assertSame(STRING_TYPE, resolved);
    }
}
