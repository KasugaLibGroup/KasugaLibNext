package lib.kasuga.rendering.models.mc.backend.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;

/** Invisible, non-pausing input layer. World UIs continue to render in their world pipeline. */
public final class WorldUiInputScreen extends Screen {
    private final UIBackend backend;
    private Object openedLevel;

    public WorldUiInputScreen(UIBackend backend) {
        super(Component.literal("World UI input"));
        this.backend = Objects.requireNonNull(backend);
    }

    public UIBackend backend() { return backend; }

    @Override protected void init() {
        backend.resetInput();
        openedLevel = minecraft.level;
    }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}
    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}
    @Override public boolean isPauseScreen() { return false; }

    @Override public void tick() {
        if (minecraft.level == null || minecraft.level != openedLevel || minecraft.player == null) onClose();
        else if (!minecraft.isWindowActive()) backend.resetInput();
    }

    @Override public void mouseMoved(double mouseX, double mouseY) { backend.getInputRouter().move(mouseX, mouseY); }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        backend.getInputRouter().press(mouseX, mouseY, button);
        return true;
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        backend.getInputRouter().release(mouseX, mouseY, button);
        return true;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        backend.getInputRouter().move(mouseX, mouseY);
        return true;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        backend.getInputRouter().scroll(mouseX, mouseY, deltaX, deltaY);
        return true;
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) onClose();
        else backend.getInputRouter().keyPressed(keyCode, scanCode, modifiers);
        return true;
    }

    @Override public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        backend.getInputRouter().keyReleased(keyCode, scanCode, modifiers);
        return true;
    }

    @Override public boolean charTyped(char character, int modifiers) {
        backend.getInputRouter().charTyped(character, modifiers);
        return true;
    }

    @Override public void onClose() { minecraft.setScreen(null); }
    @Override public void removed() { backend.resetInput(); openedLevel = null; }
}
