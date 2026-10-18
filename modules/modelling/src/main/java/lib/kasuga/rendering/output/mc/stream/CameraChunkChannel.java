package lib.kasuga.rendering.output.mc.stream;

import lib.kasuga.KasugaLibApplication;
import lib.kasuga.registration.minecraft.payload.PayloadReg;
import lib.kasuga.registration.minecraft.payload.PayloadStage;

public final class CameraChunkChannel {
    public static final PayloadReg<CameraChunkRequest> REQUEST = new PayloadReg<>("camera/chunk_request", CameraChunkRequest.CODEC)
            .stage(PayloadStage.PLAY).version("1")
            .server(() -> (p, c) -> c.enqueueWork(() -> CameraChunkServer.request((net.minecraft.server.level.ServerPlayer) c.player(), p)))
            .setParent(KasugaLibApplication.REGISTRY);
    public static final PayloadReg<CameraChunkFragment> CHUNK = new PayloadReg<>("camera/chunk", CameraChunkFragment.CODEC)
            .stage(PayloadStage.PLAY).version("1")
            .client(() -> (p, c) -> c.enqueueWork(() -> CameraChunkClient.receive(p)))
            .setParent(KasugaLibApplication.REGISTRY);
    public static final PayloadReg<CameraEntityPayload> ENTITY = new PayloadReg<>("camera/entity", CameraEntityPayload.CODEC)
            .stage(PayloadStage.PLAY).version("1")
            .client(() -> (p, c) -> c.enqueueWork(() -> CameraChunkClient.receiveEntity(p)))
            .setParent(KasugaLibApplication.REGISTRY);
    private CameraChunkChannel() {}
}
