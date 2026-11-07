package test.kasuga.modelling.nebula;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/** Intentionally just two labels and one rectangular frequency control. */
public final class NebulaScreen extends Screen {
    private static final Style MONO = Style.EMPTY.withFont(
            ResourceLocation.fromNamespaceAndPath("kasuga_demo", "jetbrains_mono"));
    private static final Component TITLE = Component.literal("Nebula Sim").withStyle(MONO);
    private static final Component FREQUENCY = Component.literal("Freq.").withStyle(MONO);
    private FrequencySlider slider;

    public NebulaScreen() { super(TITLE); }

    @Override protected void init() {
        slider = addRenderableWidget(new FrequencySlider(70, 52, 118, 12));
    }

    public FrequencySlider slider() { return slider; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(14, 14, 202, 80, 0xC0101119);
        graphics.renderOutline(14, 14, 188, 66, 0xFF626774);
        graphics.drawString(font, TITLE, 26, 26, 0xFFE0E4EC, false);
        graphics.drawString(font, FREQUENCY, 26, 51, 0xFFB5BAC7, false);
        slider.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { NebulaDemo.stop(); }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 1 && (x > 202 || y > 80)) return true;
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 1 && minecraft.player != null) {
            NebulaDemo.orbit(dx, dy);
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    public static final class FrequencySlider extends AbstractSliderButton {
        private FrequencySlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.literal("Freq."),
                    NebulaDemo.simulation().frequency() / NebulaSimulation.MAX_FREQUENCY);
            updateMessage();
        }

        @Override protected void updateMessage() {
            setMessage(Component.literal("Freq. " + String.format(java.util.Locale.ROOT, "%.1f Hz",
                    value * NebulaSimulation.MAX_FREQUENCY)));
        }

        @Override protected void applyValue() {
            NebulaDemo.simulation().frequency((float) value * NebulaSimulation.MAX_FREQUENCY);
        }

        @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), width = getWidth();
            int handle = x + (int) Math.round(value * (width - 8));
            graphics.fill(x, y + 5, x + width, y + 7, 0xFF3C414E);
            graphics.fill(x, y + 5, handle + 4, y + 7, 0xFFABB5CE);
            graphics.fill(handle, y + 1, handle + 8, y + 11, 0xFF101119);
            graphics.renderOutline(handle, y + 1, 8, 10,
                    isHoveredOrFocused() ? 0xFFF2F4FA : 0xFFC6CCDA);
        }
    }
}
