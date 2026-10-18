package lib.kasuga.mixins.client;

import lib.kasuga.client.loading.LoadingIndicator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LoadingOverlay.class)
public abstract class MixinLoadingOverlay {
    @Inject(method = "render", at = @At("TAIL"))
    private void kasuga$renderDetailedProgress(GuiGraphics graphics, int mouseX, int mouseY,
                                               float partialTick, CallbackInfo callbackInfo) {
        LoadingIndicator.Snapshot snapshot = LoadingIndicator.snapshot();
        if (!snapshot.active()) return;

        int centerX = graphics.guiWidth() / 2;
        int textY = (int) (graphics.guiHeight() * 0.865f);
        String text = snapshot.label() + "  " + snapshot.current() + "/" + snapshot.total();
        graphics.drawCenteredString(Minecraft.getInstance().font, text, centerX, textY, 0xffffffff);

        int width = Math.min(320, (int) (graphics.guiWidth() * 0.6f));
        int left = centerX - width / 2;
        int top = textY + 13;
        graphics.fill(left, top, left + width, top + 3, 0x66000000);
        graphics.fill(left, top, left + Math.round(width * snapshot.progress()), top + 3, 0xffffffff);
    }
}
