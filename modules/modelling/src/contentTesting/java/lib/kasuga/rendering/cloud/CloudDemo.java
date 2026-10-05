package lib.kasuga.rendering.cloud;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.EffectHandle;
import lib.kasuga.rendering.effect.builtin.cloud.CloudEffects;
import lib.kasuga.rendering.effect.builtin.cloud.CumulonimbusCloud;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Development-only placeable cumulonimbus demo; it uses the public instance API. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CloudDemo {
    private static EffectHandle<CumulonimbusCloud> cloud;
    private CloudDemo() {}

    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ksglib").then(Commands.literal("debug")
                .then(Commands.literal("cloud").executes(context -> {
                    stop(); CloudEffects.setSkyEnabled(true); CloudEffects.setEnabled(true);
                    context.getSource().sendSuccess(() -> Component.literal("天空体积云已开启，原版云已隐藏；/ksglib debug cloud stop 恢复原版云。"), false);
                    return 1;
                }).then(Commands.literal("single").executes(context -> {
                    var player = Minecraft.getInstance().player;
                    if (player == null) return 0;
                    stop();
                    var eye = player.getEyePosition(); var look = player.getLookAngle();
                    double yaw = Math.atan2(look.x, look.z);
                    var pose = CloudPose.at(eye.x + Math.sin(yaw) * 1800, Math.max(384, eye.y + 220),
                            eye.z + Math.cos(yaw) * 1800);
                    var instance = new CumulonimbusCloud(pose, CloudSettings.CUMULONIMBUS);
                    instance.wind(.6, 0, .2); cloud = CloudEffects.spawn(instance);
                    CloudEffects.setSkyEnabled(false); CloudEffects.setEnabled(true);
                    context.getSource().sendSuccess(() -> Component.literal("体积云测试已开启，原版云已隐藏；抬头观察。/ksglib debug cloud stop 可关闭并恢复原版云。"), false);
                    return 1;
                })).then(Commands.literal("sparse").executes(context -> sky(SkyCloudSettings.SCATTERED)))
                .then(Commands.literal("dense").executes(context -> sky(SkyCloudSettings.CLOUDY)))
                .then(Commands.literal("overcast").executes(context -> sky(SkyCloudSettings.OVERCAST)))
                .then(Commands.literal("stop").executes(context -> { stop(); return 1; })))));
    }
    private static int sky(SkyCloudSettings settings) {
        stop(); CloudEffects.sky().settings(settings);
        CloudEffects.setSkyEnabled(true); CloudEffects.setEnabled(true);
        return 1;
    }
    private static void stop() {
        CloudEffects.setEnabled(false);
        if (cloud != null) { cloud.remove(); cloud.effect().close(); cloud = null; }
    }
}
