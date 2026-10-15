package lib.kasuga.registration.core;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lib.kasuga.registration.CreativeTabContentRegistration;
import lib.kasuga.registration.stages.CapabilityRegistration;
import lib.kasuga.registration.stages.PayloadRegistrationStage;
import lib.kasuga.registration.stages.RegisterStageContext;
import lib.kasuga.registration.stages.RegistrationStage;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@Context
public class DefaultRegisterContextDispatchers {
    @Inject() RegisterContextRegistry registry;

    @PostConstruct
    public void init() {
        registry.register(RegisterContextRegistry.Side.COMMON, (registry, modEventBus)->{

            modEventBus.addListener(EventPriority.HIGHEST, RegisterEvent.class, (event)->{
                this.registry.dispatch(RegisterContextRegistry.Side.COMMON, registry, new RegisterContext<>(
                        RegistrationStage.REGISTER_EVENT,
                        new RegisterStageContext(event)
                ));
            });

            modEventBus.addListener(RegisterPayloadHandlersEvent.class, (event)->{
                this.registry.dispatch(RegisterContextRegistry.Side.COMMON, registry, new RegisterContext<>(
                        RegistrationStage.PAYLOAD_REGISTRATION,
                        new PayloadRegistrationStage(event::registrar)
                ));
            });

            modEventBus.addListener(RegisterCapabilitiesEvent.class, (event)->{
                this.registry.dispatch(RegisterContextRegistry.Side.COMMON, registry, new RegisterContext<CapabilityRegistration>(
                        RegistrationStage.REGISTER_CAPABILITIES,
                        new CapabilityRegistration(event)
                ));
            });


            modEventBus.addListener(BuildCreativeModeTabContentsEvent.class, (event)->{
                this.registry.dispatch(RegisterContextRegistry.Side.COMMON, registry, new RegisterContext<>(
                        RegistrationStage.CREATIVE_TAB_CONTENT_REGISTRATION,
                        new CreativeTabContentRegistration(event.getTabKey(), event.getTab(), event)
                ));
            });

            modEventBus.addListener(NewRegistryEvent.class,
                    s->this.registry.dispatch(RegisterContextRegistry.Side.COMMON, registry,
                            new RegisterContext<>(RegistrationStage.NEW_REGISTRY, s))
            );
        });
    }
}
