package lib.kasuga.create.content.train;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import org.spongepowered.asm.mixin.Unique;

public interface CarriageContraptionEntityAccess {

    CarriageContraptionEntity kasuga$getCarriageContraptionEntity();

    /** The offset of the current tick; use this for anything that must match server-side logic. */
    float kasuga$getCarriageYOffset();

    /** The offset interpolated between the previous and the current tick; use this for rendering. */
    float kasuga$getInterpolatedYOffset(float partialTicks);

    /**
     * Lateral deviation of the given interior bogey from the leading-to-trailing chord, so that
     * it stays on the track in curves. Positive values shift it to the right of the chord
     * (viewed along leading -> trailing); interpolated between ticks.
     */
    float kasuga$getRedundantBogeyOffset(int index, float partialTicks);

    Carriage kasuga$getCarriage();
}
