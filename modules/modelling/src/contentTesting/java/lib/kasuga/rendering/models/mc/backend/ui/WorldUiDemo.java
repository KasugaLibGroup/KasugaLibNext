package lib.kasuga.rendering.models.mc.backend.ui;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.joml.Matrix4f;

/** Development-only interactive acceptance scene, using the public world-UI API. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class WorldUiDemo {
    private static Screen panel;
    private WorldUiDemo() {}

    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ksglib").then(Commands.literal("debug")
                .then(Commands.literal("world_ui").executes(context -> open(0))
                        .then(Commands.literal("max_z").executes(context -> open(GuiGraphics.MAX_GUI_Z)))
                        .then(Commands.literal("min_z").executes(context -> open(GuiGraphics.MIN_GUI_Z)))
                        .then(Commands.literal("stop").executes(context -> {
                            if (panel != null) Constants.UI_BACKEND.removeRenderable(panel);
                            panel = null;
                            var mc = Minecraft.getInstance();
                            if (mc.screen instanceof WorldUiInputScreen screen) screen.onClose();
                            return 1;
                        })))));
    }

    private static int open(float guiZ) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || Constants.UI_BACKEND == null) return 0;
        if (panel != null) Constants.UI_BACKEND.removeRenderable(panel);
        panel = new DemoPanel(guiZ);
        panel.init(mc, 320, 180);
        var camera = mc.gameRenderer.getMainCamera();
        var center = camera.getPosition().add(camera.getLookVector().x * 2.5,
                camera.getLookVector().y * 2.5, camera.getLookVector().z * 2.5);
        var transform = new Matrix4f().translation((float) center.x, (float) center.y, (float) center.z)
                .rotate(camera.rotation()).scale(.006f, -.006f, 1).translate(-160, -90, 0);
        Constants.UI_BACKEND.addRenderable(panel, transform);
        // The client command is invoked from chat; finish that screen before opening the input layer.
        mc.setScreen(null);
        Constants.UI_BACKEND.openPointer();
        return 1;
    }

    private static final class DemoPanel extends Screen {
        private final float guiZ;
        private int clicks;
        private double scroll;
        private double dragX;
        private DemoPanel(float guiZ) { super(Component.literal("World UI")); this.guiZ = guiZ; }

        @Override protected void init() {
            addRenderableWidget(Button.builder(Component.literal("Click: 0"), button -> {
                clicks++;
                button.setMessage(Component.literal("Click: " + clicks));
            }).bounds(30, 45, 120, 20).build());
            EditBox text = new EditBox(font, 165, 45, 125, 20, Component.literal("Text"));
            text.setHint(Component.literal("Type here"));
            addRenderableWidget(text);
        }

        @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            gui.pose().pushPose();
            try {
                gui.pose().translate(0, 0, guiZ);
                gui.fill(0, 0, width, height, 0xDD18232D);
                gui.drawCenteredString(font, "World UI / GUI Z: " + Math.round(guiZ), width / 2, 16, 0xFFFFFFFF);
                gui.drawString(font, "Scroll: " + scroll, 30, 90, 0xFFFFFFFF);
                gui.drawString(font, "Drag X: " + Math.round(dragX), 30, 110, 0xFFFFFFFF);
                gui.drawString(font, "Esc: leave cursor / text input", 30, 150, 0xFF9DD8CE);
                super.render(gui, mouseX, mouseY, partialTick);
            } finally { gui.pose().popPose(); }
        }

        @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}
        @Override public boolean mouseClicked(double x, double y, int button) {
            super.mouseClicked(x, y, button);
            return true;
        }
        @Override public boolean mouseScrolled(double x, double y, double dx, double dy) { scroll += dy; return true; }
        @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
            dragX += dx;
            super.mouseDragged(x, y, button, dx, dy);
            return true;
        }
    }
}
