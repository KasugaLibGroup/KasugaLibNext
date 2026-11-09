package lib.kasuga.registration.core;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lib.kasuga.inject.class_loader.BeanOnlyIn;
import lib.kasuga.registration.stages.BakingCompleteStage;
import lib.kasuga.registration.stages.FluidClientExtensionsStage;
import lib.kasuga.registration.stages.MenuScreenBindingStage;
import lib.kasuga.registration.stages.RegistrationStage;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

@Context
@BeanOnlyIn.Client()
public class DefaultClientRegisterContextDispatchers {
    @Inject() RegisterContextRegistry contextRegistry;


    @PostConstruct
    public void init() {
        contextRegistry.register(RegisterContextRegistry.Side.CLIENT, (registry, modEventBus)->{
            modEventBus.addListener(RegisterMenuScreensEvent.class, (event)->{
                contextRegistry.dispatch(RegisterContextRegistry.Side.CLIENT, registry, new RegisterContext<>(
                        RegistrationStage.MENU_SCREEN_BINDING,
                        new MenuScreenBindingStage.Instance(event)
                ));
            });

            modEventBus.addListener(RegisterClientExtensionsEvent.class, (event)->{
                registry.dispatchRegister(new RegisterContext<>(
                        RegistrationStage.REGISTER_CLIENT_EXTENSIONS,
                        new FluidClientExtensionsStage(event)
                ));
            });

            modEventBus.addListener(ModelEvent.BakingCompleted.class, (event)->{
                contextRegistry.dispatch(RegisterContextRegistry.Side.CLIENT, registry, new RegisterContext<>(
                        RegistrationStage.BAKING_COMPLETE,
                        new BakingCompleteStage()
                ));
            });
        });
    }
}
