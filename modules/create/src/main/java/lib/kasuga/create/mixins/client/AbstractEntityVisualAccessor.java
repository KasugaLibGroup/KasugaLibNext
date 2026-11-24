package lib.kasuga.create.mixins.client;

import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the entity a Flywheel visual belongs to. */
@Mixin(value = AbstractEntityVisual.class, remap = false)
public interface AbstractEntityVisualAccessor {

    @Accessor("entity")
    Entity kasuga$getEntity();
}
