package template.lib.kasuga.registration.minecraft.payload;

import com.mojang.logging.LogUtils;
import lib.kasuga.inject.class_loader.Envs;
import lib.kasuga.internal.generator.annotations.CodeTemplate;
import lib.kasuga.internal.generator.annotations.RegGenerator;
import lib.kasuga.internal.generator.facades.RegFacade;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.core.ResourceLocationModifiers;
import lib.kasuga.registration.minecraft.payload.PayloadStage;
import lib.kasuga.registration.stages.RegistrationStage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import java.util.function.Supplier;

@CodeTemplate(generator = "Reg")
@RegGenerator(
        modifiers = {
                @RegGenerator.Modifier(
                        type = "Version",
                        target = String.class
                ),
                @RegGenerator.Modifier(
                        type = "Stage",
                        target = PayloadStage.class
                )
        }
)
public class PayloadReg<T extends CustomPacketPayload> extends Reg<PayloadReg<T>, CustomPacketPayload.Type<T>> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final String name;

    private final Supplier<StreamCodec<FriendlyByteBuf, T>> codec;
    private CustomPacketPayload.Type<T> entry;
    private Supplier<IPayloadHandler<T>> serverHandlerSupplier;
    private Supplier<IPayloadHandler<T>> clientHandlerSupplier;

    public PayloadReg(String name, StreamCodec<FriendlyByteBuf, T> codec) {
        this(name, ()->codec);
    }

    public PayloadReg(String name, Supplier<StreamCodec<FriendlyByteBuf, T>> codec) {
        this.name = name;
        this.codec = codec;
    }

    @Override
    public void register(RegisterContext<?> context) {
        context.onStage(RegistrationStage.PAYLOAD_REGISTRATION, c->{
            this.ensureEntry();
            PayloadStage stage = RegFacade.transformObject("Stage", PayloadStage.COMMON);
            String version = RegFacade.transformObject("Version", "1");
            PayloadRegistrar registrar = c.getRegistrar(version);
            IPayloadHandler<T> clientHandler = null;
            if(clientHandlerSupplier != null) {
                if(Envs.isDedicatedServer()){
                    clientHandler = (i,j)->{};
                } else {
                    clientHandler = clientHandlerSupplier.get();
                }
            }
            IPayloadHandler<T> serverHandler = null;
            if(serverHandlerSupplier != null) {
                serverHandler = serverHandlerSupplier.get();
            }

            StreamCodec<FriendlyByteBuf, T> codec = this.codec.get();

            if(serverHandler != null && clientHandler != null) {
                switch (stage) {
                    case PLAY -> registrar.playBidirectional(this.entry, codec, new DirectionalPayloadHandler<>(clientHandler, serverHandler));
                    case CONFIGURATION -> registrar.configurationBidirectional(this.entry, codec, new DirectionalPayloadHandler<>(clientHandler, serverHandler));
                    case COMMON -> registrar.commonBidirectional(this.entry, codec, new DirectionalPayloadHandler<>(clientHandler, serverHandler));
                    default -> throw new IllegalStateException("Unexpected value: " + stage);
                }
            } else if(serverHandler != null) {
                switch (stage) {
                    case PLAY -> registrar.playToServer(this.entry, codec, serverHandler);
                    case CONFIGURATION -> registrar.configurationToServer(this.entry, codec, serverHandler);
                    case COMMON -> registrar.commonToServer(this.entry, codec, serverHandler);
                    default -> throw new IllegalStateException("Unexpected value: " + stage);
                }
            } else if(clientHandler != null) {
                switch (stage) {
                    case PLAY -> registrar.playToClient(this.entry, codec, clientHandler);
                    case CONFIGURATION -> registrar.configurationToClient(this.entry, codec, clientHandler);
                    case COMMON -> registrar.commonToClient(this.entry, codec, clientHandler);
                    default -> throw new IllegalStateException("Unexpected value: " + stage);
                }
            } else {
                LOGGER.warn("No handlers for payload {}, skipping registration", this.name);
            }
        });
    }

    private synchronized void ensureEntry() {
        if(this.entry == null) {
            ResourceLocation location = transform(ResourceLocationModifiers.ID, ResourceLocation.fromNamespaceAndPath("minecraft",name));
            if(location.getNamespace().equals("minecraft")) {
                throw new IllegalStateException(
                        "Payload '" + name + "' resolved to the reserved 'minecraft' namespace — register it into a "
                        + "registration tree that supplies a namespace (e.g. registry.register(...) / .setParent(...))");
            }
            this.entry = new CustomPacketPayload.Type<>(location);
        }
    }

    public PayloadReg<T> server(Supplier<IPayloadHandler<T>> handler) {
        this.serverHandlerSupplier = handler;
        return this;
    }

    public PayloadReg<T> client(Supplier<IPayloadHandler<T>> handler) {
        this.clientHandlerSupplier = handler;
        return this;
    }

    @Override
    public CustomPacketPayload.Type<T> getEntry() {
        return entry;
    }

    @RegGenerator.ModifyFunction(type = "Version")
    public String version(String originalValue, String version) {
        return version;
    }

    @RegGenerator.ModifyFunction(type = "Stage")
    public PayloadStage stage(PayloadStage originalValue, PayloadStage stage) {
        return stage;
    }
}
