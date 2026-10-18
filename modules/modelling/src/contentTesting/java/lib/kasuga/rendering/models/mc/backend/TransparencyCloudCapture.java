package lib.kasuga.rendering.models.mc.backend;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import java.nio.file.Files;

/** Opt-in regression view for a COPY of the water-column test save. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class TransparencyCloudCapture {
    private static int frames;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("kasuga.debugCloudCapture")) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        player.setXRot(-65);
        player.xRotO = -65;
    }

    @SubscribeEvent
    public static void afterWeather(RenderLevelStageEvent event) {
        if (!Boolean.getBoolean("kasuga.debugCloudCapture")
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER
                || ++frames != 600) return;
        var minecraft = Minecraft.getInstance();
        var output = minecraft.gameDirectory.toPath().resolve("debug/cloud-handoff.png");
        try (var image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            Files.createDirectories(output.getParent());
            image.writeToFile(output);
            LogUtils.getLogger().info("Saved post-cloud regression frame: {}", output.toAbsolutePath());
        } catch (Exception failure) {
            LogUtils.getLogger().error("Cannot capture post-cloud regression frame", failure);
        }
        minecraft.stop();
    }
}
