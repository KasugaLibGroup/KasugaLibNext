package lib.kasuga.rendering.models.mc.api;

import lib.kasuga.rendering.models.uml.backend.ModelLightingMode;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Content-owned lighting defaults applied when a native pipeline creates an instance. */
public final class ModelLightingDefaults {
    private static final Map<ResourceLocation, ModelLightingMode> DEFAULTS = new ConcurrentHashMap<>();

    private ModelLightingDefaults() {
    }

    /** Register before the content mounts; defaults survive resource reloads. */
    public static void register(ResourceLocation model, ModelLightingMode mode) {
        DEFAULTS.put(Objects.requireNonNull(model, "model"), Objects.requireNonNull(mode, "mode"));
    }

    public static ModelLightingMode resolve(ResourceLocation model) {
        return DEFAULTS.getOrDefault(Objects.requireNonNull(model, "model"), ModelLightingMode.PBR);
    }
}
