package lib.kasuga.create.mixins;

import lib.kasuga.create.content.train.CarriageContraptionEntityAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin {

    /**
     * Rewrites the value returned by {@link Entity#position()}: {@code RETURN} injects right before
     * the method returns, and {@code cancellable = true} lets the callback replace the result.
     * {@link CallbackInfoReturnable#getReturnValue()} reads what the method computed,
     * {@link CallbackInfoReturnable#setReturnValue(Object)} writes the replacement back.
     *
     * <p>The adjustment below is a placeholder; replace it with the actual modification.
     */
    @Inject(method = "position", at = @At("RETURN"), cancellable = true)
    private void kasuga$modifyPosition(CallbackInfoReturnable<Vec3> cir) {
        if (!(this instanceof CarriageContraptionEntityAccess access)) return;
        Vec3 original = cir.getReturnValue();
        if (original == null)
            return;
        cir.setReturnValue(original.add(0.0D, access.kasuga$getCarriageYOffset(), 0.0D));
    }
}
