package lib.kasuga.mixins.modelling;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ClientPacketListener.class) public interface WorldViewConnectionAccessor {
    @Accessor("level") ClientLevel kasuga$getLevel();
    @Accessor("level") void kasuga$setLevel(ClientLevel level);
}
