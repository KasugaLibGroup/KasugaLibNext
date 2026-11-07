package lib.kasuga.registration.stages;

import lib.kasuga.registration.CreativeTabContentRegistration;
import net.neoforged.neoforge.registries.NewRegistryEvent;

public class RegistrationStage<T> {
    public static final RegistrationStage<MenuScreenBindingStage> MENU_SCREEN_BINDING = new RegistrationStage<>();
    public static final RegistrationStage<CapabilityRegistration> REGISTER_CAPABILITIES = new RegistrationStage<CapabilityRegistration>();
    public static final RegistrationStage<CreativeTabContentRegistration> CREATIVE_TAB_CONTENT_REGISTRATION = new RegistrationStage<>();
    public static RegistrationStage<RegisterStageContext> REGISTER_EVENT = new RegistrationStage<>();

    public static RegistrationStage<RegisterStageContext> COMMON_SETUP = new RegistrationStage<>();

    public static RegistrationStage<BakingCompleteStage> BAKING_COMPLETE = new RegistrationStage<>();

    public static RegistrationStage<PayloadRegistrationStage> PAYLOAD_REGISTRATION = new RegistrationStage<>();

    public static RegistrationStage<NewRegistryEvent> NEW_REGISTRY = new RegistrationStage<>();

    public static RegistrationStage<FluidClientExtensionsStage> REGISTER_CLIENT_EXTENSIONS = new RegistrationStage<>();

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj);
    }
}
