package lib.kasuga.mixins.modelling;

import lib.kasuga.rendering.models.mc.backend.ui.UIBackend;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client-only optional modelling hook; ordinary gameplay and Screens pass through unchanged. */
@Mixin(KeyboardHandler.class)
abstract class WorldUiKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true, require = 1)
    private void kasuga$worldUiKey(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) {
        if (UIBackend.interceptKey(window, key, scanCode, action, modifiers)) ci.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true, require = 1)
    private void kasuga$worldUiCharacter(long window, int codePoint, int modifiers, CallbackInfo ci) {
        if (UIBackend.interceptCharacter(window, codePoint, modifiers)) ci.cancel();
    }
}
