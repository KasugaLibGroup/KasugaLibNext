package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.backend.ModelLightingMode;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Immutable host light policy, also part of the native global-batch key. */
public record ModelLighting(
        ModelLightingMode mode,
        boolean item,
        float downShade,
        float upShade,
        float zShade,
        float xShade
) {
    public static final ModelLighting PBR =
            new ModelLighting(ModelLightingMode.PBR, false, .5f, 1f, .8f, .6f);

    public ModelLighting {
        Objects.requireNonNull(mode, "mode");
        if (!Float.isFinite(downShade) || !Float.isFinite(upShade)
                || !Float.isFinite(zShade) || !Float.isFinite(xShade)) {
            throw new IllegalArgumentException("block shade coefficients must be finite");
        }
    }

    public static ModelLighting world(ModelLightingMode mode, @Nullable BlockAndTintGetter level) {
        Objects.requireNonNull(mode, "mode");
        if (mode == ModelLightingMode.PBR) return PBR;
        return level == null
                ? new ModelLighting(mode, false, .5f, 1f, .8f, .6f)
                : new ModelLighting(mode, false,
                        level.getShade(Direction.DOWN, true), level.getShade(Direction.UP, true),
                        level.getShade(Direction.NORTH, true), level.getShade(Direction.EAST, true));
    }

    public static ModelLighting item(ModelLightingMode mode) {
        Objects.requireNonNull(mode, "mode");
        return mode == ModelLightingMode.PBR ? PBR : new ModelLighting(mode, true, .5f, 1f, .8f, .6f);
    }

    /** Native shader ABI: PBR, world block shading, vanilla item diffuse lighting. */
    public int shaderMode() {
        return mode == ModelLightingMode.PBR ? 0 : item ? 2 : 1;
    }
}
