package lib.kasuga.mixins.modelling;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
@Mixin(ClientChunkCache.class) abstract class WorldViewChunkLightMixin {
    @Shadow @Final private ClientLevel level;
    @Redirect(method = "onLightUpdate", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;levelRenderer:Lnet/minecraft/client/renderer/LevelRenderer;"))
    private LevelRenderer kasuga$ownRenderer(Minecraft mc) { return ((WorldViewClientLevelAccessor) level).kasuga$renderer(); }
}
