package lib.kasuga.rendering.models.mc.multiplexer;

import lib.kasuga.rendering.models.uml.dynamic.multiplexer.Variant;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * A Minecraft variant handle: in addition to the variant id it carries model, morph-set and overlay
 * {@link ResourceLocation}s. It is one ordinary implementation of {@link Variant}.
 */
public final class McVariant extends Variant<McVariant> {

    private ResourceLocation modelVariant;
    private ResourceLocation morphSet;
    private ResourceLocation overlay;

    public McVariant(String id) {
        super(id);
    }

    public @Nullable ResourceLocation modelVariant() {
        return modelVariant;
    }

    public @Nullable ResourceLocation morphSet() {
        return morphSet;
    }

    public @Nullable ResourceLocation overlay() {
        return overlay;
    }

    public McVariant model(ResourceLocation modelVariant) {
        this.modelVariant = modelVariant;
        return this;
    }

    public McVariant morphSet(ResourceLocation morphSet) {
        this.morphSet = morphSet;
        return this;
    }

    public McVariant overlay(ResourceLocation overlay) {
        this.overlay = overlay;
        return this;
    }
}
