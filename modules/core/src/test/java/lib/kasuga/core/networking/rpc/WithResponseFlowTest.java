package lib.kasuga.core.networking.rpc;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end {@link RpcApi.WithResponse} flow on a fake {@link IPayloadContext}: request handling
 * replies through {@code enqueueWork} on both the success and the error branch, sessions settle via
 * handleResponse/handleError, tick() drains timeouts, and the default timeout is 30 seconds.
 */
class WithResponseFlowTest {

    static final class EchoRpc extends RpcApi.WithResponse<EchoRpc.Request, Integer, EchoRpc> {

        record Request(int value) {}

        private final Function<Request, Integer> handler;

        EchoRpc(String name, Function<Request, Integer> handler) {
            super(name);
            this.handler = handler;
        }

        @Override
        protected StreamCodec<? super FriendlyByteBuf, Request> getRequestPayloadCodec() {
            return StreamCodec.<FriendlyByteBuf, Request>of(
                    (buf, request) -> buf.writeInt(request.value()),
                    buf -> new Request(buf.readInt()));
        }

        @Override
        protected StreamCodec<? super FriendlyByteBuf, Integer> getResponsePayloadCodec() {
            return StreamCodec.<FriendlyByteBuf, Integer>of(
                    (buf, value) -> buf.writeInt(value),
                    FriendlyByteBuf::readInt);
        }

        @Override
        public Integer handleInstant(Request request, IPayloadContext context) {
            return handler.apply(request);
        }
    }

    /** Records enqueued work and replies instead of touching the network or the main thread. */
    static final class FakeContext implements IPayloadContext {
        final List<Runnable> work = new ArrayList<>();
        final List<CustomPacketPayload> replies = new ArrayList<>();

        @Override
        public ICommonPacketListener listener() {
            return null;
        }

        @Override
        public Player player() {
            return null;
        }

        @Override
        public CompletableFuture<Void> enqueueWork(Runnable runnable) {
            work.add(runnable);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletableFuture<T> enqueueWork(Supplier<T> supplier) {
            return CompletableFuture.completedFuture(supplier.get());
        }

        @Override
        public PacketFlow flow() {
            return PacketFlow.CLIENTBOUND;
        }

        @Override
        public void handle(CustomPacketPayload payload) {
        }

        @Override
        public void finishCurrentTask(ConfigurationTask.Type type) {
        }

        @Override
        public void reply(CustomPacketPayload payload) {
            replies.add(payload);
        }
    }

    private static Duration timeoutOf(EchoRpc rpc) throws Exception {
        Field timeout = RpcApi.WithResponse.class.getDeclaredField("timeout");
        timeout.setAccessible(true);
        return (Duration) timeout.get(rpc);
    }

    @Test
    void timeoutDefaultsToThirtySecondsAndUnitsConvert() throws Exception {
        EchoRpc rpc = new EchoRpc("echo", request -> request.value());
        assertEquals(Duration.ofSeconds(30), timeoutOf(rpc));

        rpc.setTimeoutSeconds(7);
        assertEquals(Duration.ofSeconds(7), timeoutOf(rpc));

        rpc.setTimeoutMillis(1500);
        assertEquals(Duration.ofMillis(1500), timeoutOf(rpc));

        rpc.setTimeout(Duration.ofMinutes(1));
        assertEquals(Duration.ofMinutes(1), timeoutOf(rpc));

        rpc.setTimeout(null);
        assertEquals(Duration.ofSeconds(30), timeoutOf(rpc));

        // legacy alias is millisecond-based
        @SuppressWarnings("deprecation")
        EchoRpc legacy = rpc.setTimeout(500);
        assertEquals(Duration.ofMillis(500), timeoutOf(legacy));
    }

    @Test
    void successReplyRunsThroughEnqueueWork() {
        EchoRpc rpc = new EchoRpc("echo", request -> request.value() * 2);
        FakeContext ctx = new FakeContext();

        rpc.handle(rpc.requestSerializer.wrap(7L, new EchoRpc.Request(21)), ctx);

        assertEquals(1, ctx.work.size());
        assertTrue(ctx.replies.isEmpty()); // nothing replied before the enqueued work runs
        ctx.work.get(0).run();
        assertEquals(1, ctx.replies.size());

        IdentifiedRpcPacketType<?>.Packet reply = (IdentifiedRpcPacketType<?>.Packet) ctx.replies.get(0);
        assertEquals(7L, reply.getId());
        assertEquals(42, reply.getValue());
    }

    @Test
    void errorReplyRunsThroughEnqueueWork() {
        EchoRpc rpc = new EchoRpc("failing", request -> {
            throw new RuntimeException("boom");
        });
        FakeContext ctx = new FakeContext();

        rpc.handle(rpc.requestSerializer.wrap(9L, new EchoRpc.Request(1)), ctx);

        assertEquals(1, ctx.work.size());
        ctx.work.get(0).run();
        assertEquals(1, ctx.replies.size());

        IdentifiedRpcPacketType<?>.Packet reply = (IdentifiedRpcPacketType<?>.Packet) ctx.replies.get(0);
        assertEquals(9L, reply.getId());
        assertTrue(reply.getValue() instanceof RpcApi.WithResponse.Error);
        assertEquals("boom", ((RpcApi.WithResponse.Error) reply.getValue()).getContent());
    }

    @Test
    void runFlowCompletesViaHandleResponse() {
        EchoRpc rpc = new EchoRpc("echo", request -> request.value());
        RpcSessionManager.Session<Integer> session = rpc.sessionManager.assign(60_000, null);
        FakeContext ctx = new FakeContext();

        rpc.handleResponse(rpc.responseSerializer.wrap(session.id(), 42), ctx);
        assertEquals(42, session.future().join());
    }

    @Test
    void errorFlowFailsFutureViaHandleError() {
        EchoRpc rpc = new EchoRpc("echo", request -> request.value());
        RpcSessionManager.Session<Integer> session = rpc.sessionManager.assign(60_000, null);
        FakeContext ctx = new FakeContext();

        rpc.handleError(rpc.errorSerializer.wrap(session.id(), new RpcApi.WithResponse.Error("boom")), ctx);
        assertTrue(session.future().isCompletedExceptionally());
        assertThrows(RuntimeException.class, () -> session.future().join());
    }

    @Test
    void tickTimesOutExpiredSessions() {
        EchoRpc rpc = new EchoRpc("echo", request -> request.value());
        RpcSessionManager.Session<Integer> session = rpc.sessionManager.assign(-1, null);

        rpc.tick();
        assertTrue(session.future().isCompletedExceptionally());
        assertThrows(CompletionException.class, () -> session.future().join());
        try {
            session.future().join();
        } catch (CompletionException e) {
            assertTrue(e.getCause() instanceof RpcTimeoutException, "cause must be RpcTimeoutException");
        }
    }
}
