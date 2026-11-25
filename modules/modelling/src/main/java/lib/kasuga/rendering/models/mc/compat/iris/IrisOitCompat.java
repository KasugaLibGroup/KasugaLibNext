package lib.kasuga.rendering.models.mc.compat.iris;

import com.mojang.logging.LogUtils;
import lib.kasuga.core.compat.CompatCapability;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Optional Iris entry points for weighted transparency. Called on the render thread. */
public final class IrisOitCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CompatCapability<BooleanSupplier> WORLD_FRAMEBUFFER =
            new CompatCapability<>("Iris world framebuffer", () -> WorldFramebuffer.link());
    private static boolean framebufferUnavailable;
    private static boolean fallbackReported;

    private IrisOitCompat() {}

    public static boolean framebufferUnavailable() {
        return framebufferUnavailable;
    }

    public static boolean bindWorldFramebuffer() {
        if (framebufferUnavailable) return false;
        CompatCapability.Result<BooleanSupplier> binding = WORLD_FRAMEBUFFER.result();
        if (!binding.available()) {
            framebufferUnavailable = true;
            reportFallback(binding.reason(), binding.cause());
            return false;
        }
        try {
            return binding.value().getAsBoolean();
        } catch (LinkageError failure) {
            // A transitive dependency may first link inside bindDefault itself.
            framebufferUnavailable = true;
            reportFallback("binding the Iris world framebuffer", failure);
            return false;
        }
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

    private static final class WorldFramebuffer {
        static BooleanSupplier link() {
            // Resolve each exact JVM method descriptor during the probe. Fetch the actual
            // manager/pipeline at draw time because pack toggles and reloads replace them.
            Supplier<PipelineManager> manager = Iris::getPipelineManager;
            Function<PipelineManager, WorldRenderingPipeline> current = PipelineManager::getPipelineNullable;
            Consumer<IrisRenderingPipeline> bind = IrisRenderingPipeline::bindDefault;
            BooleanSupplier shadow = IrisApi.getInstance()::isRenderingShadowPass;
            return () -> {
                if (shadow.getAsBoolean()) return false;
                PipelineManager activeManager = manager.get();
                if (activeManager == null) return false;
                WorldRenderingPipeline pipeline = current.apply(activeManager);
                if (pipeline == null) return false;
                if (!(pipeline instanceof IrisRenderingPipeline world)) {
                    reportFallback("unsupported world pipeline " + pipeline.getClass().getName(), null);
                    return false;
                }
                bind.accept(world);
                return true;
            };
        }
    }
}
