package lib.kasuga.test.registration.minecraft.payload;

import lib.kasuga.KasugaLib;
import lib.kasuga.KasugaLibRegistry;
import lib.kasuga.registration.Registry;
import lib.kasuga.registration.minecraft.payload.PayloadReg;
import lib.kasuga.registration.minecraft.payload.PayloadStage;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(EphemeralTestServerProvider.class)
public class PayloadRegistryTest{

    public record TestMyClass(int a) implements CustomPacketPayload {
        public static StreamCodec<FriendlyByteBuf, TestMyClass> CODEC = StreamCodec.composite(
                ByteBufCodecs.INT,
                TestMyClass::a,
                TestMyClass::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TEST_PAYLOAD.getEntry();
        }
    }

    public static Registry registry = KasugaLibRegistry.getRegistryOf(KasugaLib.MODID);

    public static PayloadReg<TestMyClass> TEST_PAYLOAD = new PayloadReg<>("test_payload", TestMyClass.CODEC)
            .stage(PayloadStage.COMMON)
            .client(()->(i,j)-> System.out.println("Received TestMyClass on client: " + i.a()))
            .server(()->(i,j)-> System.out.println("Received TestMyClass on server: " + i.a()))
            .setParent(registry);

    @Test()
    public void testPayloadRegistry() {
        // Test that payload is registered
        assertNotNull(TEST_PAYLOAD.getEntry());

        StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload> codec = NetworkRegistry.getCodec(
                ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "test_payload"),
                ConnectionProtocol.PLAY,
                PacketFlow.CLIENTBOUND
        );

        assertNotNull(codec);
        assertSame(TestMyClass.CODEC, codec);
    }
}
