package lib.kasuga.rendering.models.mc.backend.ui;

import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Vanilla widget; its owning Screen calls close() from removed(). */
public final class ModelPreviewWidget extends AbstractWidget implements AutoCloseable {
    private final GuiModelPreview preview;

    public ModelPreviewWidget(int x, int y, int width, int height, GuiModelPreview preview) {
        super(x, y, width, height, Component.literal("Model preview"));
        this.preview = java.util.Objects.requireNonNull(preview);
    }

    @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        preview.render(graphics, getX(), getY(), getWidth(), getHeight(), partialTick);
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    public GuiModelPreview preview() { return preview; }
    @Override public void close() { preview.close(); }
}
