package lib.kasuga.mixins.modelling;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ClientLevel.ClientLevelData.class) public interface WorldViewLevelDataAccessor {
    @Accessor("isFlat") boolean kasuga$isFlat();
}
