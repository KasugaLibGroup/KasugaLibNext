package lib.kasuga.mixins.modelling;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WalkAnimationState.class)
public interface CameraWalkAnimationAccessor {
    @Accessor("speedOld") float kasuga$previousSpeed();
    @Accessor("speedOld") void kasuga$previousSpeed(float value);
    @Accessor("speed") float kasuga$speed();
    @Accessor("speed") void kasuga$speed(float value);
    @Accessor("position") float kasuga$position();
    @Accessor("position") void kasuga$position(float value);
}
