package lib.kasuga.core.networking.rpc;

import lib.kasuga.registration.Reg;
import lib.kasuga.registration.minecraft.payload.PayloadReg;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.Lazy;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.apache.commons.lang3.NotImplementedException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
public abstract class RpcApi<T extends CustomPacketPayload, S extends RpcApi<T, S>> {
    PayloadReg<T> requestRegistration;
    private boolean mainThread = false;
    private boolean executableOnServer;
    private boolean executableOnClient;

    public RpcApi(String name) {
        requestRegistration = new PayloadReg<>("rpc/" + name + "/request", this::getRequestCodec);
    }

    @SuppressWarnings("unchecked")
    protected S self() {
        return (S) this;
    }

    protected abstract StreamCodec<FriendlyByteBuf, T> getRequestCodec();

    public void call(T request) {
        this.call(request, null);
    }

    public void call(T request, Player player) {
        if(player instanceof ServerPlayer serverPlayer) {
            if(!executableOnClient) {
                throw new IllegalStateException("This RPC is not executable on client!");
            }
            PacketDistributor.sendToPlayer(serverPlayer, request);
        } else {
            if(!executableOnServer) {
                throw new IllegalStateException("This RPC is not executable on server!");
            }
            PacketDistributor.sendToServer(request);
        }
    }

    protected void handleInternal(T request, IPayloadContext context) {
        if(this.mainThread) {
            context.enqueueWork(() -> handle(request, context));
        } else {
            handle(request, context);
        }
    }

    public void handle(T request, IPayloadContext context) {}


    public S executeOnServer() {
        requestRegistration.server(() -> this::handleInternal);
        this.executableOnServer = true;
        return self();
    }

    public S executeOnClient() {
        requestRegistration.client(() -> this::handleInternal);
        this.executableOnClient = true;
        return self();
    }

    public S runOnMainThread() {
        this.mainThread = true;
        return self();
    }

    public S register(Reg<?, ?> parent) {
        requestRegistration.setParent(parent);
        return self();
    }

    public static abstract class WithResponse<T, R, S extends WithResponse<T,R,S>> extends RpcApi<IdentifiedRpcPacketType<T>.Packet, S> {
        public static class Error {

            public Error(String content) {
                this.content = content;
            }

            String content;

            public String getContent() {
                return content;
            }

            public static StreamCodec<FriendlyByteBuf, Error> CODEC = StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    Error::getContent,
                    Error::new
            );
        }

        private Duration timeout = Duration.ofSeconds(30);

        IdentifiedRpcPacketType<T> requestSerializer;
        IdentifiedRpcPacketType<R> responseSerializer;
        IdentifiedRpcPacketType<Error> errorSerializer;

        protected RpcSessionManager<R> sessionManager = new RpcSessionManager<>();
        PayloadReg<IdentifiedRpcPacketType<R>.Packet> responseRegistration;
        PayloadReg<IdentifiedRpcPacketType<Error>.Packet> errorRegistration;
        public WithResponse(String name) {
            super(name);

            requestSerializer = new IdentifiedRpcPacketType<>(()->requestRegistration.getEntry(), this::getRequestPayloadCodec);
            responseSerializer = new IdentifiedRpcPacketType<>(()->responseRegistration.getEntry(), this::getResponsePayloadCodec);
            errorSerializer = new IdentifiedRpcPacketType<>(()->errorRegistration.getEntry(), ()->Error.CODEC);

            responseRegistration = new PayloadReg<>("rpc/" + name + "/response", responseSerializer::getCodec);
            errorRegistration = new PayloadReg<>("rpc/" + name + "/error", errorSerializer::getCodec);
        }

        /** Set the session timeout; {@code null} resets to the default of 30 seconds. */
        public S setTimeout(Duration timeout) {
            this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
            return self();
        }

        public S setTimeoutSeconds(long seconds) {
            return setTimeout(Duration.ofSeconds(seconds));
        }

        public S setTimeoutMillis(long millis) {
            return setTimeout(Duration.ofMillis(millis));
        }

        /**
         * Legacy millisecond timeout — kept as a compatible alias for old callers.
         * @deprecated the unit is ambiguous; use {@link #setTimeoutMillis(long)} or
         * {@link #setTimeoutSeconds(long)}.
         */
        @Deprecated
        public S setTimeout(long timeout) {
            return setTimeoutMillis(timeout);
        }

        protected abstract StreamCodec<? super FriendlyByteBuf, T> getRequestPayloadCodec();
        protected abstract StreamCodec<? super FriendlyByteBuf, R> getResponsePayloadCodec();


        @Override
        protected StreamCodec<FriendlyByteBuf, IdentifiedRpcPacketType<T>.Packet> getRequestCodec() {
            return requestSerializer.getCodec();
        }

        public CompletableFuture<R> run(T request) {
            return run(request, null);
        }

        public CompletableFuture<R> run(T request, Player player) {
            RpcSessionManager.Session<R> session = sessionManager.assign(timeout.toMillis(), player);
            super.call(session.wrap(requestSerializer, request), player);
            return session.future();
        }

        public void receive(Long id, R value, Player player) {
            sessionManager.accept(id, value, player);
        }

        public void error(Long id, Error value, Player player) {
            sessionManager.reject(id, new RuntimeException(value.getContent()), player);
        }

        /**
         * Drive session timeouts: drains every expired session and completes its future exceptionally
         * (off the internal lock). Call periodically on the network thread — e.g. on a client tick —
         * to keep abandoned requests from leaking.
         */
        public void tick() {
            sessionManager.checkTimeout();
        }

        public void handleResponse(IdentifiedRpcPacketType<R>.Packet response, IPayloadContext context) {
            receive(response.getId(), response.getValue(), context.player());
        }
        public void handleError(IdentifiedRpcPacketType<Error>.Packet response, IPayloadContext context) {
            error(response.getId(), response.getValue(), context.player());
        }

        public CompletableFuture<R> handle(T request, IPayloadContext context) {
            try {
                return CompletableFuture.completedFuture(handleInstant(request, context));
            }catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        public R handleInstant(T request, IPayloadContext context) {
            throw new NotImplementedException();
        }

        @Override
        public void handle(IdentifiedRpcPacketType<T>.Packet request, IPayloadContext context) {
            CompletableFuture<R> future = handle(request.getValue(), context);
            future.whenComplete((result, exception) -> {
                // Both the success and the error reply hop to the main thread — replying from the
                // network thread would race the client's session bookkeeping.
                context.enqueueWork(() -> {
                    if (exception != null) {
                        context.reply(errorSerializer.wrap(request.getId(), new Error(exception.getMessage())));
                        return;
                    }
                    context.reply(responseSerializer.wrap(request.getId(), result));
                });
            });
        }

        @Override
        public S executeOnServer() {
            super.executeOnServer();
            responseRegistration.client(() -> this::handleResponse);
            errorRegistration.client(() -> this::handleError);
            return self();
        }

        @Override
        public S executeOnClient() {
            super.executeOnClient();
            responseRegistration.server(() -> this::handleResponse);
            errorRegistration.server(() -> this::handleError);
            return self();
        }

        @Override
        public S register(Reg<?, ?> parent) {
            super.register(parent);
            responseRegistration.setParent(parent);
            errorRegistration.setParent(parent);
            return self();
        }
    }

    public static abstract class WithOutResponse<T, S extends WithOutResponse<T, S>> extends RpcApi<RpcApi.WithOutResponse<T, S>.Wrapper, S> {
        public WithOutResponse(String name) {
            super(name);
        }


        protected Lazy<StreamCodec<FriendlyByteBuf, Wrapper>> requestCodec = Lazy.of(()->StreamCodec.composite(getRequestPayloadCodec(), Wrapper::getValue, Wrapper::new));


        @Override
        protected StreamCodec<FriendlyByteBuf, Wrapper> getRequestCodec() {
            return requestCodec.get();
        }


        protected abstract StreamCodec<FriendlyByteBuf, T> getRequestPayloadCodec();

        public class Wrapper implements CustomPacketPayload {
            T value;

            public Wrapper(T value) {
                this.value = value;
            }

            public T getValue() {
                return value;
            }

            @Override
            public Type<? extends CustomPacketPayload> type() {
                return requestRegistration.getEntry();
            }
        }


        public void handleInstant(T request, IPayloadContext context) {
            throw new NotImplementedException();
        }

        @Override
        public void handle(Wrapper request, IPayloadContext context) {
            handleInstant(request.value, context);
        }

        public void call(T request, Player p) {
            super.call(new Wrapper(request), p);
        }
        public void call(T request) {
            this.call(request, null);
        }
    }
}
