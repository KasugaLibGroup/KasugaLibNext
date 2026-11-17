package lib.kasuga.create.mixins;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import net.createmod.catnip.animation.LerpedFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessors for package-private {@code CarriageBogey} state: the three {@link LerpedFloat} angle
 * fields and the bogey type, none of which have a public getter in Create.
 */
@Mixin(value = CarriageBogey.class, remap = false)
public interface CarriageBogeyAccessor {

    @Accessor("wheelAngle")
    LerpedFloat kasuga$getWheelAngle();

    @Accessor("yaw")
    LerpedFloat kasuga$getYaw();

    @Accessor("pitch")
    LerpedFloat kasuga$getPitch();

    @Accessor("type")
    AbstractBogeyBlock<?> kasuga$getType();
}
