package lib.kasuga.rendering.models.mc.backend;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.nio.file.Files;

/** Opt-in completed-frame capture, including Iris' final composite. Use a copied test save. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ModelRenderCapture {
    private static final int CAPTURE_FRAME = Integer.getInteger("kasuga.captureModelFrame", 0);
    private static int frames;

    @SubscribeEvent
    public static void afterFrame(RenderFrameEvent.Post event) {
        if (CAPTURE_FRAME <= 0) return;
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()
                || ++frames != CAPTURE_FRAME) return;
        var output = minecraft.gameDirectory.toPath().resolve("debug/model-render.png");
        try (var image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            Files.createDirectories(output.getParent());
            image.writeToFile(output);
            LogUtils.getLogger().info("Saved completed model render frame: {}", output.toAbsolutePath());
        } catch (Exception failure) {
            LogUtils.getLogger().error("Cannot capture completed model render frame", failure);
        }
        minecraft.stop();
    }
}
