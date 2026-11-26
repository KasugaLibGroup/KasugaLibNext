package lib.kasuga.rendering.models.mc.compat.iris;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/** Optional Iris world target and sorted-translucency diagnostics. Called on the render thread. */
public final class IrisOitCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean fallbackReported;

    private IrisOitCompat() {}

    public static boolean framebufferUnavailable() {
        return IrisCompat.framebufferUnavailable();
    }

    public static boolean bindWorldFramebuffer() {
        return IrisCompat.bindShaderPackWorldFramebuffer();
    }

    public static void reportFallback(String reason, Throwable cause) {
        if (fallbackReported) return;
        fallbackReported = true;
        String iris = modVersion("iris");
        LOGGER.warn("Kasuga weighted OIT unavailable with Iris {} and Sodium {} ({}). Using sorted translucency.",
                iris, modVersion("sodium"), reason, cause);
        Minecraft client = Minecraft.getInstance();
        client.getToasts().addToast(SystemToast.multiline(client, new SystemToast.SystemToastId(),
                Component.translatable("kasuga_lib.oit.fallback.title"),
                Component.translatable("kasuga_lib.oit.fallback.iris", iris)));
    }

    private static String modVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(mod -> mod.getModInfo().getVersion().toString()).orElse("absent");
    }

}
