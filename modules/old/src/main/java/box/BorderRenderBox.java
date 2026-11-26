package lib.kasuga.widget.renderer.ui.box;

import lib.kasuga.rendering.RenderContext;
import lib.kasuga.widget.renderer.ui.style.enumaration.BorderSize;
import lombok.Getter;

public class BorderRenderBox extends RenderBox {
    @Getter BorderSize borderSize = new BorderSize();
    @Getter BorderBackground borderBackground = new BorderBackground();
    @Getter BorderSize borderImageSlice = new BorderSize();

    @Override
    public double render(RenderContext context) {

    }
}
