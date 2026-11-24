package lib.kasuga.create.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = AbstractContraptionEntity.class, remap = false)
public interface AbstractContraptionEntityAccessor {

    @Accessor(value = "prevPosInvalid")
    public boolean getPrevPosInvalid();
}
