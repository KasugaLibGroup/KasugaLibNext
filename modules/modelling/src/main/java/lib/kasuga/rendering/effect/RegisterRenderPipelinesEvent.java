package lib.kasuga.rendering.effect;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/** One-time client mod-bus event that creates owner-scoped, client-lifetime registrars. */
public final class RegisterRenderPipelinesEvent extends Event implements IModBusEvent {
    private final RenderPipelineScope clientLifetime;

    @ApiStatus.Internal
    public RegisterRenderPipelinesEvent(RenderPipelineScope clientLifetime) {
        this.clientLifetime = Objects.requireNonNull(clientLifetime, "clientLifetime");
    }

    /** Every integration must declare its owner before registering any render resources. */
    public RenderPipelineRegistrar registrar(ResourceLocation owner) {
        return clientLifetime.child(Objects.requireNonNull(owner, "owner"));
    }
}
