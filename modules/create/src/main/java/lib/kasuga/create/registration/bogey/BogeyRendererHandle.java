package lib.kasuga.create.registration.bogey;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record BogeyRendererHandle(ResourceLocation id) {
    public BogeyRendererHandle {
        Objects.requireNonNull(id, "id");
    }

    public static BogeyRendererHandle of(ResourceLocation id) {
        return new BogeyRendererHandle(id);
    }
}
