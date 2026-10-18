package lib.kasuga.mixins.client;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.RenderTypeGroup;
import net.neoforged.neoforge.client.event.RegisterNamedRenderTypesEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(RegisterNamedRenderTypesEvent.class)
public interface AccessorOnRegisterRenderTypesEvent {

    @Accessor("renderTypes")
    public Map<ResourceLocation, RenderTypeGroup> getRenderTypes();
}
