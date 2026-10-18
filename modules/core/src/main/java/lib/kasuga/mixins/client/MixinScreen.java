package lib.kasuga.mixins.client;

import lib.kasuga.core.rendering.IBlurControl;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class MixinScreen implements IBlurControl {

    private boolean ksgBlurEnabled = true;

    @Unique
    public boolean ksgB$isBlurEnabled() {
        return ksgBlurEnabled;
    }

    @Unique
    public void ksg$SetBlurEnabled(boolean ksgBlurEnabled) {
        this.ksgBlurEnabled = ksgBlurEnabled;
    }

    @Inject(method = "renderBlurredBackground", at = @At("HEAD"), cancellable = true)
    private void ksg$onRenderBlurredBackground(CallbackInfo ci) {
        if (!ksgBlurEnabled) {ci.cancel();}
    }

    @Unique
    public Screen ksg$GetSelf() {
        return (Screen)(Object)this;
    }
}
