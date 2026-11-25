package lib.kasuga.registration.stages;

import lib.kasuga.registration.minecraft.fluid.FluidTypeReg;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * Payload for {@link RegistrationStage#REGISTER_CLIENT_EXTENSIONS}.
 * <p>
 * Fluid client extensions are registered through this stage so that every registered
 * {@code FluidType} gets its still / flowing / overlay sprites, tint and fog, including types
 * produced by a custom factory that does not extend the default managed type.
 */
@Getter
@RequiredArgsConstructor
public class FluidClientExtensionsStage {
    private final RegisterClientExtensionsEvent event;

    /**
     * Registers the managed extensions for one fluid type.
     */
    public void register(FluidTypeReg<?> reg) {
        reg.registerClientExtensions(event);
    }
}