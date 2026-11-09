package lib.kasuga.create.registration;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lib.kasuga.create.CreateModBeans;
import lib.kasuga.registration.core.RegisterContextRegistry;
import lib.kasuga.registration.stages.RegistrationStage;

@Context
@CreateModBeans
public final class CreateRegistrationDispatcher {
    @Inject
    RegisterContextRegistry contextRegistry;

    @PostConstruct
    void init() {
        contextRegistry.registerBefore(
                RegisterContextRegistry.Side.COMMON,
                RegistrationStage.REGISTER_EVENT,
                (registry, ignored) -> registry
                        .getOrCreateAttachment(CreateRegistrationState.class, CreateRegistrationState::new)
                        .prepare(
                                registry,
                                registry.getAttachment(CreateClientBridge.class).orElseGet(CreateClientBridge::noop)
                        )
        );
    }
}
