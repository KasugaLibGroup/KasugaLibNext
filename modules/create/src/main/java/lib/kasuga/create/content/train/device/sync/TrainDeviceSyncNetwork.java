package lib.kasuga.create.content.train.device.sync;

import com.simibubi.create.Create;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lib.kasuga.create.CreateModBeans;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 注册设备状态同步包。
 *
 * <p>没有走 {@code PayloadReg} 的注册树，是因为注册树在 core 的 application 初始化时就被
 * {@code REGISTRY.register(modEventBus)} 固定了顺序，create 模块作为同 jar 内的子模块
 * 无法保证在那之前挂上子节点。直接向 mod event bus 添加监听器没有时序问题。</p>
 */
@Context
@CreateModBeans
public class TrainDeviceSyncNetwork {

    private static final String PROTOCOL_VERSION = "1";

    @Inject()
    @Named("modEventBus")
    IEventBus modEventBus;

    @PostConstruct
    public void init() {
        TrainDeviceSyncClient.installTrainResolver(id -> Create.RAILWAYS.trains.get(id));
        modEventBus.addListener(this::onRegisterPayloads);
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(
                TrainDeviceSyncPayload.TYPE,
                TrainDeviceSyncPayload.CODEC,
                TrainDeviceSyncPayload::handle
        );
    }
}
