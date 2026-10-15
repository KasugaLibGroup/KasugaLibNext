package lib.kasuga.rendering.effect;

import net.minecraft.resources.ResourceLocation;

/** Common lifecycle and ownership view for every public render registration. */
public interface RenderRegistration extends AutoCloseable {
    ResourceLocation id();

    ResourceLocation owner();

    boolean isActive();

    @Override
    void close();
}
