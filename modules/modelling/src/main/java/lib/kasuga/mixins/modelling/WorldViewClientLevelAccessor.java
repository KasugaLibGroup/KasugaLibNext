package lib.kasuga.mixins.modelling;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ClientLevel.class) public interface WorldViewClientLevelAccessor {
    @Accessor("levelRenderer") LevelRenderer kasuga$renderer();
}
