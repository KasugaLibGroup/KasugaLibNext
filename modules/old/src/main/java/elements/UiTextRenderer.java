package lib.kasuga.widget.renderer.ui.elements;

import lib.kasuga.rendering.RenderContext;
import lib.kasuga.widget.renderer.ui.UiElement;
import lib.kasuga.widget.renderer.ui.UiElementRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

public class UiTextRenderer extends UiElementRenderer {
    private final UiTextElement textElement;

    public UiTextRenderer(UiTextElement element) {
        super(element);
        this.textElement = element;
    }

    @Override
    public double renderSelf(RenderContext context) {

    }
}
