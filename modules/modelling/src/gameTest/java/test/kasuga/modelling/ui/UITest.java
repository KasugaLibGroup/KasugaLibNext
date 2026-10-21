package test.kasuga.modelling.ui;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.backend.ui.UIBackend;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

/**
 * 客户端世界空间 UI 冒烟测试。
 *
 * <p>在 {@code AFTER_ENTITIES} 阶段把同一个 {@link AlertScreen} 作为世界空间平面渲染一次；
 * 该阶段仍持有相机 model-view 和主渲染目标。屏幕放在世界坐标 {@code (10, 10, 10)}，
 * 并附带一组欧拉角旋转，用于验证 {@link UIBackend#constructGuiGraphics} 的
 * translate/rotation 组合以及射线求交。
 */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class UITest {

    private static final UIBackend UI_BACKEND = new UIBackend();

    private UITest() {
    }

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        // RenderLevelStageEvent fires for every stage (and per mapped RenderType).
        // Draw world UIs exactly once, while the camera model-view and the main
        // render target are active. AFTER_LEVEL runs after LevelRenderer has
        // popped the camera model-view back to identity, so it is not suitable.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        if (UI_BACKEND.getRenderables().isEmpty()) {
            Screen screen = new AlertScreen(() -> {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.displayClientMessage(Component.literal("触发了按钮"), true);
                }
            }, Component.literal("Test"), Component.literal("This is a test"));
            screen.init(Minecraft.getInstance(), Minecraft.getInstance().getWindow().getGuiScaledWidth(),
                    Minecraft.getInstance().getWindow().getGuiScaledHeight());
            UI_BACKEND.addRenderable(screen);
        }

        GuiGraphics guiGraphics = UIBackend.constructGuiGraphics(
                new Vector3f(10, 10, 10),
                QuaternionHelper.fromXYZAngle(30, 45, 60, true),
                null);
        UI_BACKEND.renderAllUis(guiGraphics, 0);
    }
}
