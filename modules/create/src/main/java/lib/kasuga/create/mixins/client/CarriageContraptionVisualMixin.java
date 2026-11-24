package lib.kasuga.create.mixins.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.trains.entity.CarriageContraptionVisual;
import lib.kasuga.create.content.train.CarriageContraptionEntityAccess;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the Flywheel-rendered bogeys on the track while the carriage body bobs vertically.
 *
 * <p>The body's offset is applied by {@code applyLocalTransforms}; the bogeys must not receive it.
 * {@code AbstractEntityVisual#getVisualPosition(float)} interpolates between {@code entity.yOld}
 * (never offset) and {@code entity.position()}, which {@code EntityMixin} offsets with the current
 * tick's value. The result therefore carries {@code partialTicks * current} of the offset; cancel
 * exactly that part so the bogeys stay at the un-offset base position.
 */
@Mixin(value = CarriageContraptionVisual.class, remap = false)
public class CarriageContraptionVisualMixin {

    @ModifyExpressionValue(
            method = "animate(F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/entity/CarriageContraptionVisual;getVisualPosition(F)Lorg/joml/Vector3f;"
            )
    )
    private Vector3f kasuga$cancelCarriageYOffsetForBogeys(Vector3f original,
                                                           @Local(argsOnly = true) float partialTicks) {
        Entity entity = ((AbstractEntityVisualAccessor) this).kasuga$getEntity();
        if (!(entity instanceof CarriageContraptionEntityAccess access))
            return original;

        float offset = access.kasuga$getCarriageYOffset();
        if (offset == 0)
            return original;
        return original.add(0.0f, -partialTicks * offset, 0.0f);
    }
}
