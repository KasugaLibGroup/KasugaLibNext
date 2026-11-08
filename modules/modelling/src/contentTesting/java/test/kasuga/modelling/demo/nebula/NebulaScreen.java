package test.kasuga.modelling.demo.nebula;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Invisible control screen used only for keyboard, mouse and lifecycle handling. */
public final class NebulaScreen extends Screen {
    public NebulaScreen() { super(Component.empty()); }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // NebulaDemo already rendered the world pass. Keep this screen transparent.
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { NebulaDemo.stop(); }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 32) {
            NebulaDemo.simulation().triggerWarp();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 1) return true;
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 1 && minecraft.player != null) {
            NebulaDemo.orbit(dx, dy);
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

}
