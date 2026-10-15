package lib.kasuga.registration.minecraft.payload;

import lib.kasuga.registration.Registry;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.stages.PayloadRegistrationStage;
import lib.kasuga.registration.stages.RegistrationStage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PayloadReg namespace guard: an entry that resolves to the reserved {@code minecraft} namespace
 * fails fast at registration time instead of silently hijacking a vanilla namespace; a parent
 * registration tree rewrites the entry to its own namespace.
 */
class PayloadRegNamespaceTest {

    private record TestPayload(int value) implements CustomPacketPayload {
        private static final StreamCodec<FriendlyByteBuf, TestPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, TestPayload::value, TestPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return null; // registration-only fixture; type() is never used here
        }
    }

    @Test
    void withoutParentResolvesToMinecraftAndFailsFast() {
        PayloadReg<TestPayload> reg = new PayloadReg<>("foo", TestPayload.CODEC);
        RegisterContext<PayloadRegistrationStage> context =
                new RegisterContext<>(RegistrationStage.PAYLOAD_REGISTRATION, null);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> reg.register(context));
        assertTrue(e.getMessage().contains("setParent") || e.getMessage().contains("minecraft"));
    }

    @Test
    void withParentResolvesToParentNamespace() throws Exception {
        PayloadReg<TestPayload> reg = new PayloadReg<>("foo", TestPayload.CODEC).setParent(new Registry("t"));

        // the full register() path needs a live payload registrar; drive ensureEntry() directly
        Method ensureEntry = PayloadReg.class.getDeclaredMethod("ensureEntry");
        ensureEntry.setAccessible(true);
        ensureEntry.invoke(reg);

        assertEquals("t", reg.getEntry().id().getNamespace());
        assertEquals("foo", reg.getEntry().id().getPath());
    }
}
