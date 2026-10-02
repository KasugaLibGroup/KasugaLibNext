package lib.kasuga.mixins.modelling;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(SynchedEntityData.class) public interface CameraEntityDataAccessor {
    @Accessor("itemsById") SynchedEntityData.DataItem<?>[] kasuga$items();
}
