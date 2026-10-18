package lib.kasuga.rendering.models.mc.dynamic.fsm.sync;

import lib.kasuga.KasugaLibApplication;
import lib.kasuga.registration.minecraft.payload.PayloadReg;
import lib.kasuga.registration.minecraft.payload.PayloadStage;

/**
 * Wire registration of the FSM sync payload — an independent {@code CustomPacketPayload} channel
 * (not the request/response RPC model). Play-stage, server→client only, wire format version "2"
 * (v2 renamed {@code definitionVersion} → {@code definitionHash}, a per-definition content hash).
 * Static registration works only if this class loads before the registration window closes;
 * {@link FsmSyncChannelRegistrar} forces that during mod construction.
 */
public final class FsmSyncChannel {

    public static final PayloadReg<FsmSyncPayload> PAYLOAD = new PayloadReg<>("fsm/sync", FsmSyncPayload.CODEC)
            .stage(PayloadStage.PLAY)
            .version("2")
            .client(() -> (payload, context) -> context.enqueueWork(() -> FsmSyncClient.INSTANCE.apply(payload)))
            .setParent(KasugaLibApplication.REGISTRY);

    private FsmSyncChannel() {
    }
}
