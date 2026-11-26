package lib.kasuga.elementOld.components.e2d;

import lib.kasuga.elementOld.components.RenderElement2D;
import lib.kasuga.rendering.RenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

public class ElementText extends RenderElement2D {

    protected String text;

    protected Font font = Minecraft.getInstance().font;

    @Override
    protected double renderUiElement(RenderContext context) {
        this.font.drawInBatch(
                this.text,
                0,
                0,
                0xffffffff,
                false,
                context.pose().last().pose(),
                context.buffer(),
                Font.DisplayMode.NORMAL,
                0x00000000,
                context.light()
        );
        return 0.02F;
    }
}
